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
    val name: String
) : hw_astc_stdif() {

    private val g = cyclix.Generic(name)

    // tick
    private val tick = g.uglobal("tick", "0")
    private val tg   = TickGen("tg0")

    // компоненты
    private val inFifo  = FifoInput("in0")
    private val outFifo = FifoOutput("out0")
    private val dynVm   = DynamicParamMem("vmem")
    private val wIfGen  = StaticMemIfGen("wif0")

    init {
        // 1) Тик: 1 мс при 100 МГц
        tg.emit(
            g,
            TickGenCfg(timeslot = 1, unit = TimeUnit.MS, clkPeriodNs = 10),
            tick
        )

        // 2) Входной FIFO
        val fifoInIf = inFifo.emit(
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

        // 3) Выходной FIFO (на будущее)
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

        // 4) Динамическая память (напр., Vmemb)
        val vmIf = dynVm.emit(
            g = g,
            cfg = DynParamCfg(
                name = "Vmemb",
                bitWidth = 16,
                count = 8      // TODO: model.PostsynNeuronsCount
            )
        )

        // 5) Интерфейс к статической памяти весов
        val pre  = 8   // TODO: model.PresynNeuronsCount
        val post = 8   // TODO: model.PostsynNeuronsCount
        val wIf = wIfGen.emit(
            g = g,
            cfg = StaticMemCfg(
                name = "w_l1",
                wordWidth = 8,                 // TODO: model.weightBitWidth
                depth = pre * post,
                preIdxWidth  = NmMath.log2ceil(pre),
                postIdxWidth = NmMath.log2ceil(post),
                postsynCount = post,
                useEn = true
            )
        )

        // 6) Регистр-банк (интерфейсные регистры ядра)
        val rbCfg = RegBankCfg(
            bankName = "core_cfg",
            regs = listOf(
                RegDesc("threshold",     width = 12, init = "0"),
                RegDesc("leakage",       width = 8,  init = "0"),
                RegDesc("baseAddr",      width = 32, init = "0"),
                RegDesc("postsynCount",  width = 16, init = "0"),
                RegDesc("layerBase",     width = 32, init = "0", count = 4)
            ),
            prefix = "cfg"
        )
        val regBank = RegBankGen("rb0").emit(g, rbCfg)

        // Примеры чтения значений из банкa
        val thr     = regBank["threshold"]
        val leak    = regBank["leakage"]
        val baseAdr = regBank["baseAddr"]
        val postsynCnt = regBank["postsynCount"]

        // 7) Рантайм-интерфейс для селектора (из регистров)
        val rt = RegIf(
            postsynCount = postsynCnt,
            baseAddr = baseAdr
        )

        // 8) Селектор синапсов (ставит adr_r/en_r для wIf)
        val sel = SynapseSelector("sel0")
        val selIf = sel.emit(
            g     = g,
            cfg   = SynSelCfg(
                name          = "sel0",
                addrWidth     = wIf.addrWidth,               // ширина адреса реально из интерфейса
                preWidth      = NmMath.log2ceil(pre),
                postWidth     = NmMath.log2ceil(post),
                stepByTick    = false,
                useLinearAddr = true
            ),
            topo  = TopologySpec(TopologyKind.FULLY_CONNECTED),
            rt    = rt,
            tick  = tick,
            mem   = wIf
        )

        // 9) Обработчик синаптической фазы
        val syn = SynapticPhase("syn")
        val synIf = syn.emit(
            g      = g,
            cfg    = SynPhaseCfg(
                name = "syn",
                op = SynOpKind.ADD,
                preIdxWidth = NmMath.log2ceil(pre)   // ширина индекса пресинаптического нейрона
            ),
            inFifo = fifoInIf,   // интерфейс входного FIFO
            sel    = selIf,      // интерфейс селектора
            wmem   = wIf,        // интерфейс статической памяти весов
            dyn    = vmIf        // интерфейс динамической памяти (Vmemb)
        )

        // 10) Нейронная фаза: пример — сначала вычесть leakage, потом сдвинуть вправо на threshold
        val neur = NeuronalPhase("neur")
        val neurIf = neur.emit(
            g   = g,
            cfg = NeurPhaseCfg(
                name = "neur",
                idxWidth  = NmMath.log2ceil(post), // ширина счётчика пост-нейронов
                dataWidth = 16,                    // ширина Vmemb (как в DynParamCfg)
                ops = listOf(
                    NeurOpSpec(NeurOpKind.SUB, "leakage"),   // acc := acc - leakage
                    NeurOpSpec(NeurOpKind.SHR, "threshold")  // acc := acc >> threshold
                )
            ),
            dyn  = vmIf,          // динамическая память (Vmemb)
            regs = regBank,       // банк регистров (источники аргументов ops)
            postsynCount = postsynCnt // сколько элементов обработать (из регистрового банка)
        )

// 11) Простой триггер: запускаем нейронную фазу сразу после завершения синаптической
        val neurKick = g.uglobal("neur_kick", hw_dim_static(1), "0")

// сгенерируем однотактный импульс стартa при synIf.done_o=1
        g.begif(g.eq2(synIf.done_o, 1)); run {
            neurKick.assign(1)
        }; g.endif()
        g.begelse(); run {
            neurKick.assign(0)
        }; g.endif()

// подаём импульс на вход старта нейрофазы
        neurIf.start_i.assign(neurKick)

        // дальше к FSM: используем synIf.busy/done или свои флаги, fifoOutIf и т.д.
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

