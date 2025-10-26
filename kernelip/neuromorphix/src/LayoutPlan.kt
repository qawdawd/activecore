package neuromorphix

/* ================================================================
 *  LayoutPlan: статическая компоновка БММ + конфиги компонентов
 * ================================================================ */

// было:
// data class LayoutPlan(..., val wmem: StaticMemPlan, ...)

// стало:
interface LayoutPlan {
    val tick: TickPlan
    val fifoIn: FifoInPlan
    val fifoOut: FifoOutPlan
    val wmems: Map<String, StaticMemPlan>   // ключ = имя SPIKE-поля SYNAPTIC_PARAM (напр. "w", "tag")
    val dyn: DynArrays
    val regBank: RegBankPlan
    val selector: SynSelPlan
    val phases: PhasePlans
    val topology: TopologySpec
}

data class DefaultLayoutPlan(
    override val tick: TickPlan,
    override val fifoIn: FifoInPlan,
    override val fifoOut: FifoOutPlan,
    override val wmems: Map<String, StaticMemPlan>,
    override val dyn: DynArrays,
    override val regBank: RegBankPlan,
    override val selector: SynSelPlan,
    override val phases: PhasePlans,
    override val topology: TopologySpec
) : LayoutPlan

// ——— «тик» как источник временной разметки
data class TickPlan(
    val signalName: String,     // логическое имя тика в HDL (например, "tick")
    val cfg: TickGenCfg
)

// ——— входной/выходной FIFO
data class FifoInPlan(val role: String, val cfg: FifoCfg)
data class FifoOutPlan(val role: String, val cfg: FifoCfg)


// ——— набор динамических массивов (главный + дополнительные)
data class DynArrays(
    val main: DynParamPlan,
    val extra: List<DynParamPlan> = emptyList()
)
data class DynParamPlan(val field: String, val bitWidth: Int, val count: Int)

// ——— банк регистров (описания совпадают с твоим RegBankGen)
data class RegBankPlan(val regs: List<RegDesc>, val mapApiKeys: Map<String, String> = emptyMap())

// ——— селектор синапсов
data class SynSelPlan(val cfg: SynSelCfg)

// ——— фазы
data class PhasePlans(
    val syn: SynPhasePlan,
    val neur: NeurPhasePlan,
    val emit: EmitPlan
)

//enum class NeurOpKind { ADD, SUB, SHL, SHR }
//data class NeurOpSpec(val kind: NeurOpKind, val regKey: String? = null) // если null — оперируем только над dyn.acc

data class NeurPhasePlan(
    val ops: List<NeurOpSpec>,
    val postsynCountRegKey: String   // откуда брать фактическое N пост-нейронов (ключ в RegBank)
)

//enum class CmpKind { GT, LT, GE, LE }
data class EmitPlan(
    val cmp: CmpKind,
    val cmpRegKey: String,           // ключ регистра порога (обычно "threshold")
    val refractory: Boolean,
    val resetRegKey: String?,        // если refractory=true — ключ регистра сброса (обычно "reset")
    val outRole: String = "spike_out"
)

// Описание одного упакованного под-поля в слове памяти.
data class PackedField(
    val name: String,
    val width: Int,
    val lsb: Int,            // младший бит в слове
    val msb: Int             // старший бит (включительно)
)

// Если pack != null — это «упакованная» память, где слово содержит несколько полей.
data class StaticMemPlan(
    val role: String,
    val cfg: StaticMemCfg,
    val pack: SynPackPlan? = null     // null => «однополезная» память (классический случай)
)

data class SynPackSlice(val lsb: Int, val msb: Int)


data class SynPackPlan(
    val wordWidth: Int,
    val fields: Map<String, SynPackSlice>    // <-- исправлено: вместо slices → fields
) {
    fun sliceOf(field: String): SynPackSlice =
        fields[field] ?: error("SynPackPlan: no slice for field '$field'")
}

// План синфазы хранит и имя поля, и (опционально) план упаковки.
data class SynPhasePlan(
    val op: SynOpKind,
    val gateByTick: Boolean,
    val connects: Map<String, String>,
    val synParamField: String? = null,     // напр. "w"
    val packedSlices: SynPackPlan? = null  // если null — берём целое слово памяти
)

// helper: из списка SPIKE-полей SYNAPTIC_PARAM строим упакованный формат
// вход: список синаптических параметров из Symbols (SYNAPTIC_PARAM)
fun buildSynPack(synParams: List<SpikeFieldDesc>): SynPackPlan {
    require(synParams.isNotEmpty()) { "buildSynPack: empty synParams" }

    // фиксируем детерминированный порядок упаковки
    val ordered = synParams.sortedBy { it.name }

    var offset = 0
    val map = LinkedHashMap<String, SynPackSlice>()
    for (p in ordered) {
        val lsb = offset
        val msb = offset + p.width - 1
        map[p.name] = SynPackSlice(lsb = lsb, msb = msb)
        offset += p.width
    }
    return SynPackPlan(wordWidth = offset, fields = map)
}

/* ================================================================
 *  Построение DefaultLayoutPlan
 *  — статический план «что инстанцировать и как связать»
 * ================================================================ */

fun buildLayoutPlan(
    arch: SnnArch,
    symbols: Symbols,
    ir: TxIR? = null,
    fifoDepthIn: Int = 256,
    fifoDepthOut: Int = 256,
    tickCfg: TickGenCfg = TickGenCfg(timeslot = 1, unit = TimeUnit.US, clkPeriodNs = 10),
    packAllSynParams: Boolean = true
): LayoutPlan {

    val d = arch.d
    val synParams = symbols.spikeFields.values
        .filter { it.kind == SpikeFieldKind.SYNAPTIC_PARAM }
        .sortedBy { it.name }           // фиксируем порядок, чтобы упаковка была детерминированной
    require(synParams.isNotEmpty()) { "нет SPIKE полей SYNAPTIC_PARAM — нечего читать в синфазе" }

    val weightDepth = arch.dims.presynCount * arch.dims.postsynCount

    // ——— 1) Tick
    val tick = TickPlan(
        signalName = "tick",
        cfg = tickCfg
    )

    // ——— 2) FIFO In/Out
    val fifoIn = FifoInPlan(
        role = "spike_in",
        cfg = FifoCfg(
            name = "spike_in",
            dataWidth = d.spikeIdW,   // ширина идентификатора входящего пресинапса
            depth = fifoDepthIn,
            useTickDoubleBuffer = true
        )
    )
    val fifoOut = FifoOutPlan(
        role = "spike_out",
        cfg = FifoCfg(
            name = "spike_out",
            dataWidth = d.postsynIdxW, // пэйлоад спайка = индекс пост-нейрона
            depth = fifoDepthOut,
            useTickDoubleBuffer = true
        )
    )

// === 2) StaticMem: либо pack, либо по отдельности ===
    val wmems: Map<String, StaticMemPlan>
    val packedPlan: StaticMemPlan?
    val packedSlicesForSyn: SynPackPlan?   // <— добавим эту переменную

    if (packAllSynParams) {
        val pack = buildSynPack(synParams)     // <-- твоя функция упаковки
        val mem = StaticMemPlan(
            role = "synparams_packed",
            cfg = StaticMemCfg(
                name = "wmem_pack",
                wordWidth = pack.wordWidth,
                depth = weightDepth,
                preIdxWidth = d.presynIdxW,
                postIdxWidth = d.postsynIdxW,
                postsynCount = arch.dims.postsynCount,
                useEn = true
            ),
            pack = pack
        )
        wmems = synParams.associate { it.name to mem }
        packedPlan = mem
        packedSlicesForSyn = pack               // <— важно: прокидываем в план фаз
    } else {
        packedPlan = null
        packedSlicesForSyn = null               // <— в раздельном режиме срезов нет
        wmems = synParams.associate { desc ->
            desc.name to StaticMemPlan(
                role = "synparam:${desc.name}",
                cfg = StaticMemCfg(
                    name = "wmem_${desc.name}",
                    wordWidth = desc.width,
                    depth = weightDepth,
                    preIdxWidth = d.presynIdxW,
                    postIdxWidth = d.postsynIdxW,
                    postsynCount = arch.dims.postsynCount,
                    useEn = true
                ),
                pack = null
            )
        }
    }


    // ——— 4) Динамика (главный массив — Vmemb)
    // имя главного поля берём из Symbols (по умолчанию "Vmemb")
    val vmembName = when {
        "Vmemb" in symbols.neuronFields -> "Vmemb"
        else -> symbols.neuronFields.keys.firstOrNull()
            ?: error("LayoutPlan: нет ни одного нейронного поля для DynParam")
    }
    val vmembDesc = symbols.neuronFields[vmembName]!!
    val dyn = DynArrays(
        main = DynParamPlan(
            field = vmembDesc.name,
            bitWidth = vmembDesc.width,             // обычно d.potentialW
            count = arch.dims.postsynCount
        ),
        extra = emptyList() // сюда можно положить дополнительные динамики, если появятся в IR/AST
    )

    // ——— 5) Банк регистров: threshold/leakage/reset/… + служебные
    val regs = mutableListOf<RegDesc>()
    regs += RegDesc("threshold", d.thresholdW, init = "0")
    regs += RegDesc("leakage",   d.leakageW,   init = "1")  // в твоём примере SHR на 1 — ок положить 1
    regs += RegDesc("reset",     d.resetW,     init = "0")
    regs += RegDesc("postsynCount", d.postsynIdxW, init = arch.dims.postsynCount.toString())
    regs += RegDesc("baseAddr",     d.weightAddrW, init = "0")
    val regBank = RegBankPlan(
        regs = regs,
        mapApiKeys = mapOf(
            // логические имена → реальные имена регов (если надо ремапить)
            "threshold"     to "threshold",
            "leakage"       to "leakage",
            "reset"         to "reset",
            "postsynCount"  to "postsynCount",
            "baseAddr"      to "baseAddr"
        )
    )

    // ——— 6) Селектор синапсов
    val selector = SynSelPlan(
        cfg = SynSelCfg(
            name          = "sel0",
            addrWidth     = d.weightAddrW,
            preWidth      = d.presynIdxW,
            postWidth     = d.postsynIdxW,
            stepByTick    = false,       // по умолчанию бежим каждый такт; можно включить по tick
            useLinearAddr = true
        )
    )
    // === 6) Выбор рабочего параметра синфазы (как и раньше) ===
    val synParamFromIr: String? = ir?.ops
        ?.filter { it.phase == TxPhase.SYNAPTIC && it.dstSpace == SPACE_NEURON }
        ?.asSequence()
        ?.mapNotNull { op ->
            listOfNotNull(op.a, op.b).firstOrNull { o ->
                !o.isImm && o.space == SPACE_SPIKE && o.name != null
            }?.name
        }
        ?.firstOrNull()

    val preferred = synParamFromIr
        ?: (if ("w" in wmems) "w" else synParams.first().name)

    val synOp = ir?.ops
        ?.filter { it.phase == TxPhase.SYNAPTIC && it.dstSpace == SPACE_NEURON }
        ?.mapNotNull {
            when (it.opcode) {
                TxOpcode.ADD -> SynOpKind.ADD
                TxOpcode.SUB -> SynOpKind.SUB
                TxOpcode.SHL, TxOpcode.SHR -> null
                else -> null
            }
        }?.distinct()?.singleOrNull() ?: SynOpKind.ADD

// ... селектор и выбор preferred/synOp — как у тебя ...

    val phases = PhasePlans(
        syn = SynPhasePlan(
            op = synOp,
            gateByTick = true,
            synParamField = preferred,          // напр. "w"
            packedSlices = packedSlicesForSyn,  // <-- тут главная правка
            connects = mapOf(
                "inFifo"   to "spike_in",
                "selector" to "sel0",
                "dyn"      to "Vmemb"
            )
        ),
        neur = NeurPhasePlan(
            ops = listOf(NeurOpSpec(NeurOpKind.SHR, regKey = "leakage")),
            postsynCountRegKey = "postsynCount"
        ),
        emit = EmitPlan(
            cmp = CmpKind.GE,
            cmpRegKey = "threshold",
            refractory = true,
            resetRegKey = "reset",
            outRole = "spike_out"
        )
    )

    return DefaultLayoutPlan(
        tick = tick,
        fifoIn = fifoIn,
        fifoOut = fifoOut,
        wmems = wmems,           // в pack-режиме все ключи указывают на один и тот же StaticMemPlan
        dyn = dyn,
        regBank = regBank,
        selector = selector,
        phases = phases,
        topology = TopologySpec(kind = arch.dims.topology)
    )

}