import hwast.DEBUG_LEVEL
import neuromorphix.*

// Main.kt
//
//fun main() {
//    val moduleName = "neuromorphic_core"
//    println("Starting $moduleName hardware generation")
//
////    // Параметры SNN (как у тебя)
////    val snn = SnnArch(
////        modelName = "LIF_demo",
////        networkType = NEURAL_NETWORK_TYPE.SFNN,
////        PresynNeuronsCount = 28*25,
////        PostsynNeuronsCount = 128,
////        weightBitWidth = 16,
////        potentialBitWidth = 16,
////        leakageMaxValue = 1,
////        thresholdValue = 1,
////        resetValue = 0,
////        ThresholdMaxValue = 15
////    )
//
//    // Сборка ядра
//    val neu = Neuromorphic(moduleName) // , snn)
//    val g   = neu.build()
//
//    // Отладочный дамп AST/IR (если используешь Activecore’овские тулзы)
////    val logsDir = "logs/"
////    HwDebugWriter("$logsDir/AST.txt").use { it.WriteExec(g.proc) }
//
//    // Экспорт в RTL
//    val hdlDir = "SystemVerilog/"
//    val rtl = g.export_to_rtl(DEBUG_LEVEL.FULL)
//    rtl.export_to_sv(hdlDir + moduleName, DEBUG_LEVEL.FULL)
//
//    println("Done. RTL at $hdlDir$moduleName")
//}



fun main() {


    // ---------- 1) Спайковая транзакция ----------
    val spike = SpikeTx()
    spike.addField("w",     16, SpikeFieldKind.SYNAPTIC_PARAM) // вес
    spike.addField("delay", 8,  SpikeFieldKind.DELAY)          // задержка
    spike.addField("tag",   8,  SpikeFieldKind.SYNAPTIC_PARAM) // метка/тег


    spike.opAddExt(
        dstNeuronName = "Vmemb",
        a = SpikeOperand.neuronField("Vmemb"),
        b = SpikeOperand.field("w")
    )


    // пример использования нейронного поля внутри SpikeTx:
    // acc = acc + neuron.gain   (нейронное поле — внешний операнд)
    // (если в нейроне будет поле "gain")
    // spike.opAdd(
    //     dst = "acc",
    //     a   = SpikeOperand.field("acc"),
    //     b   = SpikeOperand.neuronField("gain")
    // )

    // ---------- 2) Нейронная транзакция ----------
    val neuron = NeuronTx()
    neuron.addField("Vmemb", 16, NeuronFieldKind.DYNAMIC)         // мембранный потенциал
    neuron.addField("Vthr",  16, NeuronFieldKind.STATIC)          // порог
    neuron.addField("Vrst",  16, NeuronFieldKind.STATIC)          // значение сброса (если нужно как поле)

    // --- G: интеграция веса (пример: Vmemb = Vmemb + w) ---
//    neuron.opAdd(dst = "Vmemb", aField = "Vmemb", bField = "w")   // w — из SpikeTx → мидлварь смэпит источник

// --- F_dyn: утечка ---
    neuron.opShrImm(dst = "Vmemb", aField = "Vmemb", shift = 1)

// условие: if (Vmemb >= Vthr)
    val cond = neuron.ifGe("Vmemb", "Vthr")

// emit(if cond) + сброс Vmemb := 0
    neuron.opEmitIf(cond, resetDst = "Vmemb", resetImm = 0)
// или без сброса: neuron.opEmitIf(cond)

    // ---------- 3) Отладочный вывод ----------
    println("== Spike fields ==")
    for (f in spike.listFieldsSnapshot()) {
        println("  ${f.name} : width=${f.width}, kind=${f.kind}")
    }
    println("== Spike ops ==")
    spike.dumpOps()

    println("== Neuron fields ==")
    neuron.listFields()
    println("== Neuron ops ==")
    neuron.dumpOps()

    // 4. Построить IR и вывести
    val ir = buildTxIR(spike, neuron)
    ir.dump()

// (опционально) JSON
    val json = ir.toJson()
    println(json.toString(2))   // красивый отступ 2

    // 4) Построить AST и напечатать
    val ast = buildAst(spike, neuron, name = "lif_ast")
    ast.dump()

}
