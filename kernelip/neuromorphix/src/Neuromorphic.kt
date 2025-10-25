/*
 * Neuromorphic.kt
 *     License: See LICENSE file for details
 */

package neuromorphix

import cyclix.*
import cyclix.RtlGenerator.fifo_in_descr
import hwast.*
import kotlin.math.ln
import javax.sound.sampled.Port
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.memberFunctions
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

private fun NmMath.log2ceil(v: Int): Int {
    require(v > 0)
    var n = v - 1
    var r = 0
    while (n > 0) { n = n shr 1; r++ }
    return maxOf(r, 1)
}



class Neuromorphic(
    val name : String,
//    val NN_model_params: SnnArch
) : hw_astc_stdif() {

    val g = cyclix.Generic(name)

    val tick = g.uglobal("tick", "0")
    private val tg = TickGen("tg0")

    // очереди FIFO
    private val inFifo  = FifoInput("in0")
    private val outFifo = FifoOutput("out0")

    private val dynVm = DynamicParamMem("vmem")

    private val wIfGen = StaticMemIfGen("wif0")


    init {
        // тик: 1мс при 100МГц
        tg.emit(g, TickGenCfg(timeslot = 1, unit = TimeUnit.MS, clkPeriodNs = 10), tick)

        // входная очередь: 32-бит слово, глубина 64, кредиты 8 бит, переключение по tick
        val fifoIf = inFifo.emit(
            g = g,
            cfg = FifoCfg(
                name = "spike_in",
                dataWidth = 32,
                depth = 64,
                creditWidth = 8,
                useTickDoubleBuffer = true
            ),
            tick = tick
        )

        // выходная очередь: 32-бит слово, глубина 64, кредиты 8 бит, переключение по tick
        val fifoOutIf = outFifo.emit(
            g = g,
            cfg = FifoCfg(
                name = "spike_out",
                dataWidth = 32,
                depth = 64,
                creditWidth = 8,
                useTickDoubleBuffer = true
            ),
            tick = tick
        )

        // Память динамических параметров: Vmemb[PostsynNeurons] по 16 бит
        val vmIf = dynVm.emit(
            g = g,
            cfg = DynParamCfg(
                name = "Vmemb",
                bitWidth = 16,
                count = 8 // model.PostsynNeuronsCount
            )
        )

        val pre = 8 //model.PresynNeuronsCount
        val post = 8 // model.PostsynNeuronsCount

        val wIf = wIfGen.emit(
            g = g,
            cfg = StaticMemCfg(
                name = "w_l1",
                wordWidth = 8, // model.weightBitWidth,
                depth = pre * post,
//                addrMode = AddrMode.CONCAT,           // или LINEAR
                preIdxWidth = NmMath.log2ceil(pre),          // для CONCAT
                postIdxWidth = NmMath.log2ceil(post),
                postsynCount = post,                  // для LINEAR (если выберешь)
                useEn = true
            )
        )

//// 1) интерфейс к статической памяти весов (как мы делали раньше)
//        val wIfGen = StaticMemIfGen()
//        val wIf = wIfGen.emit(
//            g = g,
//            cfg = StaticMemIfCfg(
//                name = "W",
//                addrWidth = /* NmMath.log2ceil(Presyn*Postsyn) */,
//                dataWidth = 16,     // ширина веса
//                exposeEn   = true
//            )
//        )

// 2) рантайм-регистры (приходят из другого модуля/рег.файла)
        val postsynCnt_r = g.uglobal("postsyn_cnt", hw_dim_static(NmMath.log2ceil(256)), "0")  // пример
        val baseAddr_r   = g.uglobal("w_base",      hw_dim_static(16), "0")             // опционально
        val rt = RegIf(postsynCount = postsynCnt_r, baseAddr = baseAddr_r)

// 3) селектор
        val sel = SynapseSelector("sel0")
        val selIf = sel.emit(
            g     = g,
            cfg   = SynSelCfg(
                name          = "sel0",
                addrWidth     = 8 /* ширина адреса W */,
                preWidth      = 8 /* NmMath.log2ceil(Presyn) */,
                postWidth     = 8 /* NmMath.log2ceil(Postsyn max) */,
                stepByTick    = false,        // можно шагать по tick
                useLinearAddr = true
            ),
            topo  = TopologySpec(TopologyKind.FULLY_CONNECTED),
            rt    = rt,
            tick  = tick,                    // если stepByTick=true
            mem   = wIf                      // селектор сам ставит adr_r/en_r
        )

        val rbCfg = RegBankCfg(
            bankName = "core_cfg",
            regs = listOf(
                RegDesc("threshold", width = 12, init = "0"),
                RegDesc("leakage",   width = 8,  init = "0"),
                RegDesc("baseAddr",  width = 32, init = "0"),
                RegDesc("postsynCount", width = 16, init = "0"),
                // пример массива (например, несколько базовых адресов для разных слоёв)
                RegDesc("layerBase", width = 32, init = "0", count = 4)
            ),
            prefix = "cfg"
        )

        // 2) генерируем банк, возвращается интерфейс для подключения
        val regBank = RegBankGen("rb0").emit(g, rbCfg)

        // 3) внутри ядра читаем зарегистрированные значения:
        val thr      = regBank["threshold"]
        val leak     = regBank["leakage"]
        val baseAddr = regBank["baseAddr"]
        val postsyn  = regBank["postsynCount"]
        val layer0   = regBank["layerBase"]    // это массив; обращаться как layer0[index]


// 4) где-то в FSM:
//  - задать selIf.preIdx_i.assign(currPre) для нужного пресинапса
//  - импульс selIf.start_i := 1, затем 0
//  - ждать selIf.done_o == 1
//  - читать wIf.dat_r как данные веса для каждого шага (пока идёт RUN)
        // дальше PhaseFSM/обработчики будут пользоваться fifoIf.rd_o, fifoIf.rd_data_o, fifoIf.empty_o
        // а внешний мир подключится к fifoIf.wr_i / wr_data_i / full_o
    }

    fun build(): Generic = g
}


// Main.kt

fun main() {
    val moduleName = "neuromorphic_core"
    println("Starting $moduleName hardware generation")

//    // Параметры SNN (как у тебя)
//    val snn = SnnArch(
//        modelName = "LIF_demo",
//        networkType = NEURAL_NETWORK_TYPE.SFNN,
//        PresynNeuronsCount = 28*25,
//        PostsynNeuronsCount = 128,
//        weightBitWidth = 16,
//        potentialBitWidth = 16,
//        leakageMaxValue = 1,
//        thresholdValue = 1,
//        resetValue = 0,
//        ThresholdMaxValue = 15
//    )

    // Сборка ядра
    val neu = Neuromorphic(moduleName) // , snn)
    val g   = neu.build()

    // Отладочный дамп AST/IR (если используешь Activecore’овские тулзы)
//    val logsDir = "logs/"
//    HwDebugWriter("$logsDir/AST.txt").use { it.WriteExec(g.proc) }

    // Экспорт в RTL
    val hdlDir = "SystemVerilog/"
    val rtl = g.export_to_rtl(DEBUG_LEVEL.FULL)
    rtl.export_to_sv(hdlDir + moduleName, DEBUG_LEVEL.FULL)

    println("Done. RTL at $hdlDir$moduleName")
}


//fun main() {


//    // ---------- 1) Спайковая транзакция ----------
//    val spike = SpikeTx()
//    spike.addField("w",     16, SpikeFieldKind.SYNAPTIC_PARAM) // вес
//    spike.addField("delay", 8,  SpikeFieldKind.DELAY)          // задержка
//    spike.addField("tag",   8,  SpikeFieldKind.SYNAPTIC_PARAM) // метка/тег
//
//
//    spike.opAddExt(
//        dstNeuronName = "Vmemb",
//        a = SpikeOperand.neuronField("Vmemb"),
//        b = SpikeOperand.field("w")
//    )
//
//
//    // пример использования нейронного поля внутри SpikeTx:
//    // acc = acc + neuron.gain   (нейронное поле — внешний операнд)
//    // (если в нейроне будет поле "gain")
//    // spike.opAdd(
//    //     dst = "acc",
//    //     a   = SpikeOperand.field("acc"),
//    //     b   = SpikeOperand.neuronField("gain")
//    // )
//
//    // ---------- 2) Нейронная транзакция ----------
//    val neuron = NeuronTx()
//    neuron.addField("Vmemb", 16, NeuronFieldKind.DYNAMIC)         // мембранный потенциал
//    neuron.addField("Vthr",  16, NeuronFieldKind.STATIC)          // порог
//    neuron.addField("Vrst",  16, NeuronFieldKind.STATIC)          // значение сброса (если нужно как поле)
//
//    // --- G: интеграция веса (пример: Vmemb = Vmemb + w) ---
////    neuron.opAdd(dst = "Vmemb", aField = "Vmemb", bField = "w")   // w — из SpikeTx → мидлварь смэпит источник
//
//// --- F_dyn: утечка ---
//    neuron.opShrImm(dst = "Vmemb", aField = "Vmemb", shift = 1)
//
//// условие: if (Vmemb >= Vthr)
//    val cond = neuron.ifGe("Vmemb", "Vthr")
//
//// emit(if cond) + сброс Vmemb := 0
//    neuron.opEmitIf(cond, resetDst = "Vmemb", resetImm = 0)
//// или без сброса: neuron.opEmitIf(cond)
//
//    // ---------- 3) Отладочный вывод ----------
//    println("== Spike fields ==")
//    for (f in spike.listFieldsSnapshot()) {
//        println("  ${f.name} : width=${f.width}, kind=${f.kind}")
//    }
//    println("== Spike ops ==")
//    spike.dumpOps()
//
//    println("== Neuron fields ==")
//    neuron.listFields()
//    println("== Neuron ops ==")
//    neuron.dumpOps()
//
//    // 4. Построить IR и вывести
//    val ir = buildTxIR(spike, neuron)
//    ir.dump()
//
//// (опционально) JSON
//    val json = ir.toJson()
//    println(json.toString(2))   // красивый отступ 2
//
//    // 4) Построить AST и напечатать
//    val ast = buildAst(spike, neuron, name = "lif_ast")
//    ast.dump()

//}



//
//
//class TickGenerator {
//    fun tick_generation(
//        tick_signal: hw_var,
//        timeslot: Int,
//        units: String,
//        clk_period: Int,
//        cyclix_gen: Generic
//    ) {  // timeslot in ms, clk_period in ns
//        // Generating Tick for timeslot processing period
//        var tick_period_val = 0
//        if (units == "ns") {
////            tick_period_val = clk_period * 1 * timeslot
//            tick_period_val = timeslot / clk_period
//            println(tick_period_val)
//        } else if (units == "us"){
////            tick_period_val = clk_period * 1000 * timeslot
//            tick_period_val = timeslot * 1000 / clk_period
//            println(tick_period_val)
//        } else if (units == "ms") {
//            tick_period_val = timeslot * 1000000 / clk_period
//            println(tick_period_val)
//        } else if (units == "s") {
//            tick_period_val = timeslot * 1000000000 / clk_period
//            println(tick_period_val)
//        }
//
//        val tick_period = cyclix_gen.uglobal("tick_period", hw_imm(timeslot))
//        val clk_counter = cyclix_gen.uglobal("clk_counter", "0")
//        val next_clk_count = cyclix_gen.uglobal("next_clk_count", "0")
//
//        tick_period.assign(tick_period_val)
//
//        cyclix_gen.begif(cyclix_gen.neq2(tick_period, clk_counter))
//        run {
//            tick_signal.assign(0)
//            next_clk_count.assign(clk_counter.plus(1))
//            clk_counter.assign(next_clk_count)
//        }; cyclix_gen.endif()
//
//        cyclix_gen.begelse()
//        run {
//            tick_signal.assign(1)
//            clk_counter.assign(0)
//        }; cyclix_gen.endif()
//    }
//}

val OP_SYN_PLUS = hwast.hw_opcode("syn_plus")


//var layers: Int = 2
//
//enum class NEURAL_NETWORK_TYPE {
//    SFNN, SCNN
//}
//
//open class SnnArch(
//    var name: String = "Default Name",
//    var nnType: NEURAL_NETWORK_TYPE = NEURAL_NETWORK_TYPE.SFNN,
//    var presyn_neurons: Int = 16,
//    var postsyn_neurons: Int = 16,
//    var outputNeur: Int = 10,
//    var weightWidth: Int = 8,
//    var potentialWidth: Int = 10,
//    var leakage: Int = 1,
//    var threshold: Int = 1,
//    var reset: Int = 0,
//    var spike_width: Int = 4,
//) {
//    fun loadModelFromJson(jsonFilePath: String) {
//        val jsonString = File(jsonFilePath).readText()
//
//        val jsonObject = JSONObject(jsonString)
//
//        val modelTopology = jsonObject.getJSONObject("model_topology")
//        this.presyn_neurons = modelTopology.optInt("input_size", this.presyn_neurons)
//        this.postsyn_neurons = modelTopology.optInt("hidden_size", this.postsyn_neurons)
//        this.outputNeur = modelTopology.optInt("output_size", this.outputNeur)
//        val lifNeurons = jsonObject.getJSONObject("LIF_neurons").getJSONObject("lif1")
//        this.threshold = lifNeurons.optInt("threshold", this.threshold)
//        this.leakage = lifNeurons.optInt("leakage", this.leakage)
//
//        val nnTypeStr = jsonObject.optString("nn_type", "SFNN")
//        this.nnType = NEURAL_NETWORK_TYPE.valueOf(nnTypeStr)
//    }
//
//    fun getArchitectureInfo(): String {
//        return "$name: (NN Type: $nnType, Presynaptic Neurons = $presyn_neurons, Postsynaptic Neurons = $postsyn_neurons)"
//    }
//}
//
//

