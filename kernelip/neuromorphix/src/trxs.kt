package neuromorphix


/** Тип поля спайковой транзакции. */
enum class SpikeFieldKind {
    // Поле, которое будет синтезироваться в память (например, w, tag)
    SYNAPTIC_PARAM,
    // Поле, используемое для реализации синаптической задержки
    DELAY
}

/** Дескриптор поля спайковой транзакции. */
class SpikeField(
    var name: String,
    var width: Int,
    var kind: SpikeFieldKind
)

/** Ссылка на поле (для будущего использования в AST/IR). */
class SpikeFieldRef(var name: String)

/* ===== 0) Опкоды и операнды (Spike) ===== */

enum class SpikeOpCode {
    ADD,   // +
    SUB,   // -
    SHL,   // <<
    SHR    // >>
}

/** Операнд: поле/константа. Поддержка внешних (нейронных) полей. */
class SpikeOperand {
    var isImm: Boolean = false
    var fieldName: String? = null

    // внешняя ссылка
    var isExternal: Boolean = false
    var extSpace: String? = null   // например, "NEURON"
    var imm: Int = 0

    companion object {
        fun field(name: String): SpikeOperand {
            val o = SpikeOperand()
            o.isImm = false
            o.fieldName = name
            return o
        }
        fun imm(value: Int): SpikeOperand {
            val o = SpikeOperand()
            o.isImm = true
            o.imm = value
            return o
        }
        /** Ссылка на поле из NeuronTx: neuron.<name> */
        fun neuronField(name: String): SpikeOperand {
            val o = SpikeOperand()
            o.isImm = false
            o.fieldName = name
            o.isExternal = true
            o.extSpace = "NEURON"
            return o
        }
    }
}

/** Узел операции (AST-узел) для SpikeTx. */
class SpikeOperation(
    val opcode: SpikeOpCode,
    val dst: String,              // имя поля: локальное или внешнее
    val a: SpikeOperand,
    val b: SpikeOperand,
    val dstIsExternal: Boolean = false,
    val dstExtSpace: String? = null  // "NEURON" и т.п.
)
/* ===== 1) SpikeTx с поддержкой операций ===== */

class SpikeTx {

    // Поля
    private val fields = mutableListOf<SpikeField>()

    // Список операций над полями этой транзакции
    private val ops = mutableListOf<SpikeOperation>()

    fun addField(
        name: String,
        width: Int,
        kind: SpikeFieldKind = SpikeFieldKind.SYNAPTIC_PARAM
    ): SpikeFieldRef {
        for (f in fields) {
            if (f.name == name) {
                println("Ошибка: поле с именем '$name' уже существует.")
                return SpikeFieldRef(name)
            }
        }
        val field = SpikeField(name, width, kind)
        fields.add(field)
        return SpikeFieldRef(name)
    }

    fun getField(name: String): SpikeField? {
        for (f in fields) if (f.name == name) return f
        return null
    }

    fun hasField(name: String): Boolean {
        for (f in fields) if (f.name == name) return true
        return false
    }

    fun listFields() {
        println("Список полей:")
        for (f in fields) println("  ${f.name} (${f.width} бит) тип=${f.kind}")
    }

    // ===== Базовые операции: +, -, <<, >> =====
// --- ВНЕШНИЕ операции: обновление поля нейрона ---
    fun opAddExt(dstNeuronName: String, a: SpikeOperand, b: SpikeOperand) {
        ops.add(
            SpikeOperation(
                SpikeOpCode.ADD,
                dstNeuronName,
                a, b,
                dstIsExternal = true,
                dstExtSpace = "NEURON"
            )
        )
    }

    fun opSubExt(dstNeuronName: String, a: SpikeOperand, b: SpikeOperand) {
        ops.add(
            SpikeOperation(
                SpikeOpCode.SUB,
                dstNeuronName,
                a, b,
                dstIsExternal = true,
                dstExtSpace = "NEURON"
            )
        )
    }

    fun opShlExt(dstNeuronName: String, a: SpikeOperand, shiftImm: Int) {
        ops.add(
            SpikeOperation(
                SpikeOpCode.SHL,
                dstNeuronName,
                a, SpikeOperand.imm(shiftImm),
                dstIsExternal = true,
                dstExtSpace = "NEURON"
            )
        )
    }

    fun opShrExt(dstNeuronName: String, a: SpikeOperand, shiftImm: Int) {
        ops.add(
            SpikeOperation(
                SpikeOpCode.SHR,
                dstNeuronName,
                a, SpikeOperand.imm(shiftImm),
                dstIsExternal = true,
                dstExtSpace = "NEURON"
            )
        )
    }

    // --- ЛОКАЛЬНЫЕ операции ТОЛЬКО ДЛЯ ЗАДЕРЖКИ ---
    private fun requireDelay(name: String): Boolean {
        val f = getField(name)
        if (f == null) { println("Ошибка: локальное поле '$name' не найдено."); return false }
        if (f.kind != SpikeFieldKind.DELAY) {
            println("Ошибка: допустимы только операции над полями DELAY (поле '$name' имеет тип ${f.kind}).")
            return false
        }
        return true
    }

    fun opDelayAddImm(delayField: String, imm: Int) {
        if (!requireDelay(delayField)) return
        ops.add(SpikeOperation(SpikeOpCode.ADD, delayField, SpikeOperand.field(delayField), SpikeOperand.imm(imm)))
    }

    fun opDelaySubImm(delayField: String, imm: Int) {
        if (!requireDelay(delayField)) return
        ops.add(SpikeOperation(SpikeOpCode.SUB, delayField, SpikeOperand.field(delayField), SpikeOperand.imm(imm)))
    }

    fun opDelayShlImm(delayField: String, imm: Int) {
        if (!requireDelay(delayField)) return
        ops.add(SpikeOperation(SpikeOpCode.SHL, delayField, SpikeOperand.field(delayField), SpikeOperand.imm(imm)))
    }

    fun opDelayShrImm(delayField: String, imm: Int) {
        if (!requireDelay(delayField)) return
        ops.add(SpikeOperation(SpikeOpCode.SHR, delayField, SpikeOperand.field(delayField), SpikeOperand.imm(imm)))
    }

    // ===== Сервис для последующих этапов (AST/IR) =====

    /** Снимок списка всех полей спайковой транзакции (для мидлвари). */
    fun listFieldsSnapshot(): List<SpikeField> = fields.toList()

    fun listOps(): List<SpikeOperation> = ops.toList()

    fun dumpOps() {
        println("Операции SpikeTx:")
        for (op in ops) {
            val aStr = if (op.a.isImm) "imm=${op.a.imm}" else "field=${op.a.fieldName}"
            val bStr = if (op.b.isImm) "imm=${op.b.imm}" else "field=${op.b.fieldName}"

            val dstInfo = buildString {
                append("dst=${op.dst}")
                if (op.dstIsExternal)
                    append(" (external space=${op.dstExtSpace})")
            }

            println("  opcode=${op.opcode}, $dstInfo, a=($aStr), b=($bStr)")
        }
    }
}

// NeuronTx.kt — минимальный класс нейронной транзакции (согласовано)

// NeuronTx.kt — минимальный класс нейронной транзакции (с EMIT и REFRACTORY)

/** Тип поля нейронной транзакции. */
enum class NeuronFieldKind {
    DYNAMIC,          // динамический параметр (Vmemb и т.п.)
    STATIC,           // статический параметр (Vthr, leakK, Vrst)
    EMIT_PREDICATE,   // предикат/флаг эмиссии спайка
    REFRACTORY        // параметры/состояния для пост-эмиссии (рефракторный период)
}

/** Дескриптор поля нейронной транзакции. */
class NeuronField(
    var name: String,
    var width: Int,
    var kind: NeuronFieldKind
)

/** Ссылка на поле (для будущего использования в AST/IR). */
class NeuronFieldRef(var name: String)

/** Опкоды базовых операций. */
enum class NeuronOpCode {
    ADD,   // +
    SUB,   // -
    SHL,   // <<
    SHR,   // >>
    EMIT,  // эмиссия спайка по предикату
    CMP_GE, // dstPredicate = (a >= b)
    EMIT_IF
}

// Операторы сравнения
// Операторы сравнения
enum class NeuronCmp {
    GT, LT, GE, LE,
    EQ, NE   // <- добавили для внутреннего использования (== и !=)
}
//enum class NeuronCmp {
//    GT, LT, GE, LE
//}

// Условие для if: (a ? b)
class NeuronCond(
    var cmp: NeuronCmp,
    var a: NeuronOperand,
    var b: NeuronOperand
)

/** Операнд операции: поле или константа. */
class NeuronOperand {
    var isImm: Boolean = false
    var fieldName: String? = null
    var imm: Int = 0

    companion object {
        fun field(name: String): NeuronOperand {
            val o = NeuronOperand()
            o.isImm = false
            o.fieldName = name
            return o
        }
        fun imm(value: Int): NeuronOperand {
            val o = NeuronOperand()
            o.isImm = true
            o.imm = value
            return o
        }
    }
}

/** Узел операции. */
class NeuronOperation(
    val opcode: NeuronOpCode,
    val dst: String,           // назначение: имя поля ИЛИ служебное имя для EMIT (напр. "spike_out")
    val a: NeuronOperand,      // аргумент A: для EMIT — это предикат (поле)
    val b: NeuronOperand,       // аргумент B: не используется для EMIT (можно 0)
    val cmp: NeuronCmp? = null,   // GT/LT/GE/LE для EMIT_IF
    val resetDst: String? = null, // поле для сброса (или null)
    val resetImm: Int? = null     // константа сброса (или null)
)

/**
 * Нейронная транзакция: контейнер полей и список операций.
 * Типы полей: DYNAMIC / STATIC / EMIT_PREDICATE / REFRACTORY.
 */
class NeuronTx {

    // Поля
    private val fields = mutableListOf<NeuronField>()

    // Операции над полями
    private val ops = mutableListOf<NeuronOperation>()

    /**
     * Добавить поле в транзакцию.
     * name — имя поля
     * width — разрядность (бит)
     * kind — тип поля (по умолчанию DYNAMIC)
     */
    fun addField(name: String, width: Int, kind: NeuronFieldKind = NeuronFieldKind.DYNAMIC): NeuronFieldRef {
        // Проверка уникальности имени
        for (f in fields) {
            if (f.name == name) {
                println("Ошибка: поле с именем '$name' уже существует.")
                return NeuronFieldRef(name)
            }
        }
        val field = NeuronField(name, width, kind)
        fields.add(field)
        return NeuronFieldRef(name)
    }

    /** Получить поле по имени (или null). */
    fun getField(name: String): NeuronField? {
        for (f in fields) if (f.name == name) return f
        return null
    }

    /** Есть ли поле с таким именем. */
    fun hasField(name: String): Boolean {
        for (f in fields) if (f.name == name) return true
        return false
    }

    /** Показать все поля (для отладки). */
    fun listFields() {
        println("Поля NeuronTx:")
        for (f in fields) {
            println("  ${f.name} (${f.width} бит) тип=${f.kind}")
        }
    }

    // ===== Базовые операции: +, -, <<, >> =====

    fun opAdd(dst: String, aField: String, bField: String) {
        if (!hasField(dst) || !hasField(aField) || !hasField(bField)) {
            println("Ошибка: одно из полей не найдено (dst/a/b)."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.ADD, dst, NeuronOperand.field(aField), NeuronOperand.field(bField)))
    }
    fun opAddImm(dst: String, aField: String, imm: Int) {
        if (!hasField(dst) || !hasField(aField)) {
            println("Ошибка: поле dst или a не найдено."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.ADD, dst, NeuronOperand.field(aField), NeuronOperand.imm(imm)))
    }

    fun opSub(dst: String, aField: String, bField: String) {
        if (!hasField(dst) || !hasField(aField) || !hasField(bField)) {
            println("Ошибка: одно из полей не найдено (dst/a/b)."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.SUB, dst, NeuronOperand.field(aField), NeuronOperand.field(bField)))
    }
    fun opSubImm(dst: String, aField: String, imm: Int) {
        if (!hasField(dst) || !hasField(aField)) {
            println("Ошибка: поле dst или a не найдено."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.SUB, dst, NeuronOperand.field(aField), NeuronOperand.imm(imm)))
    }

    fun opShlImm(dst: String, aField: String, shift: Int) {
        if (!hasField(dst) || !hasField(aField)) {
            println("Ошибка: поле dst или a не найдено."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.SHL, dst, NeuronOperand.field(aField), NeuronOperand.imm(shift)))
    }

    fun opShrImm(dst: String, aField: String, shift: Int) {
        if (!hasField(dst) || !hasField(aField)) {
            println("Ошибка: поле dst или a не найдено."); return
        }
        ops.add(NeuronOperation(NeuronOpCode.SHR, dst, NeuronOperand.field(aField), NeuronOperand.imm(shift)))
    }

    // ===== Сравнение: fired = (aField >= bField) =====
    fun opCmpGe(dstPredicate: String, aField: String, bField: String) {
        // проверки существования полей
        if (!hasField(dstPredicate) || !hasField(aField) || !hasField(bField)) {
            println("Ошибка: одно из полей не найдено (dstPredicate/aField/bField)."); return
        }

        // предупреждения/валидация типа предиката
        val dst = getField(dstPredicate)
        if (dst != null) {
            if (dst.kind != NeuronFieldKind.EMIT_PREDICATE) {
                println("Предупреждение: поле '$dstPredicate' имеет тип ${dst.kind}, ожидается EMIT_PREDICATE.")
            }
            if (dst.width != 1) {
                println("Предупреждение: поле-предикат '$dstPredicate' имеет ширину ${dst.width}, обычно ожидается 1 бит.")
            }
        }

        ops.add(
            NeuronOperation(
                NeuronOpCode.CMP_GE,
                dstPredicate,
                NeuronOperand.field(aField),
                NeuronOperand.field(bField)
            )
        )
    }
    // ===== Сравнение: fired = (aField >= imm) =====
    fun opCmpGeImm(dstPredicate: String, aField: String, imm: Int) {
        if (!hasField(dstPredicate) || !hasField(aField)) {
            println("Ошибка: поле dstPredicate или aField не найдено."); return
        }

        val dst = getField(dstPredicate)
        if (dst != null) {
            if (dst.kind != NeuronFieldKind.EMIT_PREDICATE) {
                println("Предупреждение: поле '$dstPredicate' имеет тип ${dst.kind}, ожидается EMIT_PREDICATE.")
            }
            if (dst.width != 1) {
                println("Предупреждение: поле-предикат '$dstPredicate' имеет ширину ${dst.width}, обычно ожидается 1 бит.")
            }
        }

        ops.add(
            NeuronOperation(
                NeuronOpCode.CMP_GE,
                dstPredicate,
                NeuronOperand.field(aField),
                NeuronOperand.imm(imm)
            )
        )
    }

    // ===== Секция helpers условий (добавить внутрь NeuronTx) =====
    private fun cond(cmp: NeuronCmp, a: NeuronOperand, b: NeuronOperand): NeuronCond =
        NeuronCond(cmp, a, b)

    fun ifGt(aField: String, bField: String): NeuronCond {
        if (!hasField(aField) || !hasField(bField)) {
            println("Ошибка: поле для сравнения не найдено (ifGt: $aField|$bField).");
        }
        return cond(NeuronCmp.GT, NeuronOperand.field(aField), NeuronOperand.field(bField))
    }
    fun ifGtImm(aField: String, imm: Int): NeuronCond {
        if (!hasField(aField)) { println("Ошибка: поле для сравнения не найдено (ifGtImm: $aField).") }
        return cond(NeuronCmp.GT, NeuronOperand.field(aField), NeuronOperand.imm(imm))
    }

    fun ifLt(aField: String, bField: String): NeuronCond {
        if (!hasField(aField) || !hasField(bField)) {
            println("Ошибка: поле для сравнения не найдено (ifLt: $aField|$bField).");
        }
        return cond(NeuronCmp.LT, NeuronOperand.field(aField), NeuronOperand.field(bField))
    }
    fun ifLtImm(aField: String, imm: Int): NeuronCond {
        if (!hasField(aField)) { println("Ошибка: поле для сравнения не найдено (ifLtImm: $aField).") }
        return cond(NeuronCmp.LT, NeuronOperand.field(aField), NeuronOperand.imm(imm))
    }

    fun ifGe(aField: String, bField: String): NeuronCond {
        if (!hasField(aField) || !hasField(bField)) {
            println("Ошибка: поле для сравнения не найдено (ifGe: $aField|$bField).");
        }
        return cond(NeuronCmp.GE, NeuronOperand.field(aField), NeuronOperand.field(bField))
    }
    fun ifGeImm(aField: String, imm: Int): NeuronCond {
        if (!hasField(aField)) { println("Ошибка: поле для сравнения не найдено (ifGeImm: $aField).") }
        return cond(NeuronCmp.GE, NeuronOperand.field(aField), NeuronOperand.imm(imm))
    }

    fun ifLe(aField: String, bField: String): NeuronCond {
        if (!hasField(aField) || !hasField(bField)) {
            println("Ошибка: поле для сравнения не найдено (ifLe: $aField|$bField).");
        }
        return cond(NeuronCmp.LE, NeuronOperand.field(aField), NeuronOperand.field(bField))
    }
    fun ifLeImm(aField: String, imm: Int): NeuronCond {
        if (!hasField(aField)) { println("Ошибка: поле для сравнения не найдено (ifLeImm: $aField).") }
        return cond(NeuronCmp.LE, NeuronOperand.field(aField), NeuronOperand.imm(imm))
    }

    // ===== Операция эмиссии спайка =====
    /**
     * Эмиссия спайка при истинном предикате.
     * @param predicateField имя поля-предиката (должно существовать, обычно kind=EMIT_PREDICATE)
     * @param outName логическое имя «приёмника» спайка (для IR/бэкенда), по умолчанию "spike_out"
     *
     * Представление в операции:
     *   opcode = EMIT
     *   dst    = outName
     *   a      = predicateField
     *   b      = imm(0)  // зарезервировано, не используется
     */
    /** 1) Просто эмиссия по предикату (без сброса). */
    fun opEmit(predicateField: String, outName: String = "spike_out") {
        if (!hasField(predicateField)) {
            println("Ошибка: предикат '$predicateField' не найден."); return
        }
        val pf = getField(predicateField)
        if (pf != null && pf.kind != NeuronFieldKind.EMIT_PREDICATE) {
            println("Предупреждение: поле '$predicateField' имеет тип ${pf.kind}, ожидается EMIT_PREDICATE.")
        }
        // Соглашение: dst = "spike_out" => только эмиссия
        ops.add(
            NeuronOperation(
                NeuronOpCode.EMIT,
                outName,                             // dst: логическое имя выхода
                NeuronOperand.field(predicateField), // a: предикат
                NeuronOperand.imm(0)                 // b: 0 => без сброса
            )
        )
    }

    /**
     * Соглашение для бэкенда:
     *   opcode = EMIT
     *   dst    = имя поля, которое надо сбросить (напр. "Vmemb")
     *   a      = поле-предикат (например, "fired")
     *   b      = imm(resetImm)  // константа сброса
     *
     * При этом сама эмиссия тоже происходит (как и в простом EMIT).
     */
    fun opEmit(predicateField: String, dstReset: String, resetImm: Int) {
        if (!hasField(predicateField) || !hasField(dstReset)) {
            println("Ошибка: предикат или поле для сброса не найдено."); return
        }
        val pf = getField(predicateField)
        if (pf != null && pf.kind != NeuronFieldKind.EMIT_PREDICATE) {
            println("Предупреждение: поле '$predicateField' имеет тип ${pf.kind}, ожидается EMIT_PREDICATE.")
        }
        ops.add(
            NeuronOperation(
                NeuronOpCode.EMIT,
                dstReset,                             // dst: поле для сброса (а не "spike_out")
                NeuronOperand.field(predicateField),  // a: предикат
                NeuronOperand.imm(resetImm)           // b: константа сброса
            )
        )
    }

    fun opEmitIf(
        cond: NeuronCond,
        resetDst: String? = null,
        resetImm: Int? = null,
        outName: String = "spike_out"
    ) {
        if (resetDst != null && !hasField(resetDst)) {
            println("Ошибка: поле для сброса '$resetDst' не найдено.");
            return
        }
        ops.add(
            NeuronOperation(
                NeuronOpCode.EMIT_IF,
                dst = outName,
                a = cond.a,
                b = cond.b,
                cmp = cond.cmp,
                resetDst = resetDst,
                resetImm = resetImm
            )
        )
    }


    // ===== Сервис для последующих этапов =====

    fun listOps(): List<NeuronOperation> = ops.toList()

    fun dumpOps() {
        println("Операции NeuronTx:")
        for (op in ops) {
            val aStr = if (op.a.isImm) "${op.a.imm}" else op.a.fieldName
            val bStr = if (op.b.isImm) "${op.b.imm}" else op.b.fieldName
            if (op.opcode == NeuronOpCode.EMIT_IF) {
                val cmpStr = when (op.cmp) {
                    NeuronCmp.GT -> ">"
                    NeuronCmp.LT -> "<"
                    NeuronCmp.GE -> ">="
                    NeuronCmp.LE -> "<="
                    NeuronCmp.EQ -> "=="
                    NeuronCmp.NE -> "!="
                    else -> "?"
                }
                val resetInfo = if (op.resetDst != null && op.resetImm != null)
                    ", reset ${op.resetDst} := ${op.resetImm}" else ""
                println("  EMIT_IF ${op.dst}  if ($aStr $cmpStr $bStr)$resetInfo")
            } else {
                println("  ${op.opcode}  ${op.dst} = $aStr , $bStr")
            }
        }
    }
}




// ===== IR: фазы/опкоды/операнды =====
enum class TxPhase { SYNAPTIC, NEURONAL }

enum class TxOpcode {
    ADD, SUB, SHL, SHR,
    CMP_GE,        // если используешь старую связку CMP+EMIT
    EMIT,          // "
    EMIT_IF        // новый «атомарный» emit с условием и опц. сбросом
}

// Пространства имён полей
const val SPACE_SPIKE = "SPIKE"
const val SPACE_NEURON = "NEURON"

// Операнд IR
data class TxOperand(
    val isImm: Boolean,
    val name: String? = null,          // имя поля, если не imm
    val imm: Int? = null,              // значение, если imm
    val space: String? = null          // SPIKE / NEURON (для полей)
)

// Операция IR
data class TxOp(
    val phase: TxPhase,
    val opcode: TxOpcode,
    val dst: String,                   // имя поля/выхода
    val dstSpace: String? = null,      // куда пишем (SPIKE/NEURON/лог. "spike_out")
    val a: TxOperand? = null,
    val b: TxOperand? = null,
    // для EMIT_IF:
    val cmp: NeuronCmp? = null,        // GT/LT/GE/LE
    val resetDst: String? = null,      // поле сброса (или null)
    val resetImm: Int? = null          // значение сброса (или null)
)

// Целостный IR «на такт»
data class TxIR(val ops: MutableList<TxOp> = mutableListOf())


// ===== Вспомогательные адаптеры к IR-операндам =====
private fun SpikeOperand.toTxOperand(): TxOperand =
    if (isImm) TxOperand(true, null, imm, null)
    else TxOperand(false, fieldName, null, if (isExternal) (extSpace ?: SPACE_SPIKE) else SPACE_SPIKE)

private fun NeuronOperand.toTxOperand(): TxOperand =
    if (isImm) TxOperand(true, null, imm, null)
    else TxOperand(false, fieldName, null, SPACE_NEURON)

// ===== Построение IR из SpikeTx =====
fun SpikeTx.toIR(): List<TxOp> {
    val out = mutableListOf<TxOp>()
    for (op in listOps()) {
        val opcode = when (op.opcode) {
            SpikeOpCode.ADD -> TxOpcode.ADD
            SpikeOpCode.SUB -> TxOpcode.SUB
            SpikeOpCode.SHL -> TxOpcode.SHL
            SpikeOpCode.SHR -> TxOpcode.SHR
        }
        val dstSpace = if (op.dstIsExternal) (op.dstExtSpace ?: SPACE_NEURON) else SPACE_SPIKE
        out += TxOp(
            phase = TxPhase.SYNAPTIC,
            opcode = opcode,
            dst = op.dst,
            dstSpace = dstSpace,
            a = op.a.toTxOperand(),
            b = op.b.toTxOperand()
        )
    }
    return out
}

// ===== Построение IR из NeuronTx =====
fun NeuronTx.toIR(): List<TxOp> {
    val out = mutableListOf<TxOp>()
    for (op in listOps()) {
        when (op.opcode) {
            NeuronOpCode.ADD, NeuronOpCode.SUB, NeuronOpCode.SHL, NeuronOpCode.SHR -> {
                val opcode = when (op.opcode) {
                    NeuronOpCode.ADD -> TxOpcode.ADD
                    NeuronOpCode.SUB -> TxOpcode.SUB
                    NeuronOpCode.SHL -> TxOpcode.SHL
                    NeuronOpCode.SHR -> TxOpcode.SHR
                    else -> error("unreachable")
                }
                out += TxOp(
                    phase = TxPhase.NEURONAL,
                    opcode = opcode,
                    dst = op.dst,
                    dstSpace = SPACE_NEURON,
                    a = op.a.toTxOperand(),
                    b = op.b.toTxOperand()
                )
            }

            NeuronOpCode.CMP_GE -> {
                out += TxOp(
                    phase = TxPhase.NEURONAL,
                    opcode = TxOpcode.CMP_GE,
                    dst = op.dst,                 // имя предикатного поля
                    dstSpace = SPACE_NEURON,
                    a = op.a.toTxOperand(),
                    b = op.b.toTxOperand()
                )
            }

            NeuronOpCode.EMIT -> {
                out += TxOp(
                    phase = TxPhase.NEURONAL,
                    opcode = TxOpcode.EMIT,
                    dst = op.dst,                 // "spike_out" или поле для сброса
                    dstSpace = SPACE_NEURON,
                    a = op.a.toTxOperand(),       // предикат-поле
                    b = op.b.toTxOperand()        // imm(0) или imm(reset)
                )
            }

            NeuronOpCode.EMIT_IF -> {
                out += TxOp(
                    phase = TxPhase.NEURONAL,
                    opcode = TxOpcode.EMIT_IF,
                    dst = op.dst,                 // логическое имя выхода (обычно "spike_out")
                    dstSpace = SPACE_NEURON,
                    a = op.a.toTxOperand(),       // левый операнд сравнения
                    b = op.b.toTxOperand(),       // правый операнд сравнения
                    cmp = op.cmp,                 // GT/LT/GE/LE
                    resetDst = op.resetDst,
                    resetImm = op.resetImm
                )
            }
        }
    }
    return out
}

// ===== Сборка общего IR =====
fun buildTxIR(spike: SpikeTx, neuron: NeuronTx): TxIR {
    val ir = TxIR()
    ir.ops += spike.toIR()
    ir.ops += neuron.toIR()
    return ir
}

// Текстовый дамп (читабельно для отладки/репортов)
fun TxIR.dump() {
    println("==== TxIR dump ====")
    for ((i, op) in ops.withIndex()) {
        val aStr = op.a?.let { if (it.isImm) "#${it.imm}" else "${it.space}.${it.name}" } ?: "-"
        val bStr = op.b?.let { if (it.isImm) "#${it.imm}" else "${it.space}.${it.name}" } ?: "-"
        when (op.opcode) {
            TxOpcode.EMIT_IF -> {
                val cmpStr = when (op.cmp) {
                    NeuronCmp.GT -> ">"
                    NeuronCmp.LT -> "<"
                    NeuronCmp.GE -> ">="
                    NeuronCmp.LE -> "<="
                    else -> "?"
                }
                val rst = if (op.resetDst != null && op.resetImm != null)
                    " reset ${op.resetDst}:=${op.resetImm}" else ""
                println("[%02d] %-9s %-8s dst=%s.%s  if (%s %s %s)%s"
                    .format(i, op.phase, op.opcode, op.dstSpace, op.dst, aStr, cmpStr, bStr, rst))
            }
            else -> {
                println("[%02d] %-9s %-9s dst=%s.%s  a=%s  b=%s"
                    .format(i, op.phase, op.opcode, op.dstSpace, op.dst, aStr, bStr))
            }
        }
    }
}

// JSON (минимально, через org.json уже у тебя подключён)
fun TxIR.toJson(): org.json.JSONObject {
    val root = org.json.JSONObject()
    val arr = org.json.JSONArray()
    for (op in ops) {
        val jo = org.json.JSONObject()
        jo.put("phase", op.phase.name)
        jo.put("opcode", op.opcode.name)
        jo.put("dst", op.dst)
        jo.put("dstSpace", op.dstSpace)

        fun enc(o: TxOperand?): org.json.JSONObject? {
            if (o == null) return null
            val x = org.json.JSONObject()
            x.put("isImm", o.isImm)
            if (o.isImm) x.put("imm", o.imm) else {
                x.put("name", o.name)
                x.put("space", o.space)
            }
            return x
        }

        enc(op.a)?.let { jo.put("a", it) }
        enc(op.b)?.let { jo.put("b", it) }

        op.cmp?.let { jo.put("cmp", it.name) }
        op.resetDst?.let { jo.put("resetDst", it) }
        op.resetImm?.let { jo.put("resetImm", it) }

        arr.put(jo)
    }
    root.put("ops", arr)
    return root
}

fun AstModule.dump() {
    println("==== AST: $name ====")
    for (blk in blocks) {
        println("-- phase: ${blk.phase} --")
        for ((i, s) in blk.stmts.withIndex()) {
            fun e(e: AstExpr): String = when (e) {
                is AstExpr.Imm -> "#${e.value}"
                is AstExpr.FieldRef -> "${e.space}.${e.name}"
                is AstExpr.Bin -> "(${e(e.a)} ${e.op} ${e(e.b)})"
                is AstExpr.Cmp -> "(${e(e.a)} ${e.op} ${e(e.b)})"
            }
            when (s) {
                is AstStmt.Assign ->
                    println("[%02d] %s := %s".format(i, e(s.dst), e(s.expr)))
                is AstStmt.ExternalAssign ->
                    println("[%02d] %s := %s   // external".format(i, e(s.dst), e(s.expr)))
                is AstStmt.DelayAssign ->
                    println("[%02d] %s := %s   // delay".format(i, e(s.dst), e(s.expr)))
                is AstStmt.EmitIf -> {
                    val rst = if (s.resetDst != null && s.resetImm != null)
                        " reset ${e(s.resetDst)} := #${s.resetImm}" else ""
                    println("[%02d] EMIT_IF cond=%s out=%s%s"
                        .format(i, e(s.cond), s.outName, rst))
                }
            }
        }
    }
}


// ===== AST: выражения =====
sealed class AstExpr {
    data class Imm(val value: Int) : AstExpr()
    data class FieldRef(val space: String, val name: String) : AstExpr()   // space: "SPIKE"/"NEURON"
    data class Bin(val op: BinOp, val a: AstExpr, val b: AstExpr) : AstExpr()
    data class Cmp(val op: CmpOp, val a: AstExpr, val b: AstExpr) : AstExpr()
}

enum class BinOp { ADD, SUB, SHL, SHR }
enum class CmpOp { GT, GE, LT, LE, EQ, NE }

// ===== AST: операторы/инструкции =====
sealed class AstStmt {
    // Присваивание в локальное поле (SPIKE)
    data class Assign(val dst: AstExpr.FieldRef, val expr: AstExpr) : AstStmt()

    // Присваивание во внешнее поле (обычно NEURON) из спайковой фазы
    data class ExternalAssign(val dst: AstExpr.FieldRef, val expr: AstExpr) : AstStmt()

    // Операции с задержкой (локальные DELAY-поля)
    data class DelayAssign(val dst: AstExpr.FieldRef, val expr: AstExpr) : AstStmt()

    // Эмиссия с условием, с опциональным сбросом
    data class EmitIf(
        val cond: AstExpr.Cmp,
        val outName: String = "spike_out",
        val resetDst: AstExpr.FieldRef? = null,
        val resetImm: Int? = null
    ) : AstStmt()
}

// ===== AST: блоки и корень =====
enum class AstPhase { SYNAPTIC, NEURONAL }

data class AstBlock(val phase: AstPhase, val stmts: MutableList<AstStmt> = mutableListOf())

data class AstModule(
    val name: String = "tx_module",
    val blocks: MutableList<AstBlock> = mutableListOf(
        AstBlock(AstPhase.SYNAPTIC),
        AstBlock(AstPhase.NEURONAL)
    )
) {
    fun syn(): AstBlock = blocks.first { it.phase == AstPhase.SYNAPTIC }
    fun neu(): AstBlock = blocks.first { it.phase == AstPhase.NEURONAL }
}


// Утилиты для упаковки операндов в AstExpr
private fun spikeOpndToExpr(o: SpikeOperand): AstExpr =
    if (o.isImm) AstExpr.Imm(o.imm)
    else AstExpr.FieldRef(
        space = if (o.isExternal) (o.extSpace ?: "SPIKE") else "SPIKE",
        name = o.fieldName ?: error("SpikeOperand.fieldName == null")
    )

private fun neuronOpndToExpr(o: NeuronOperand): AstExpr =
    if (o.isImm) AstExpr.Imm(o.imm)
    else AstExpr.FieldRef(space = "NEURON", name = o.fieldName ?: error("NeuronOperand.fieldName == null"))

private fun spikeOpToBin(op: SpikeOpCode): BinOp = when (op) {
    SpikeOpCode.ADD -> BinOp.ADD
    SpikeOpCode.SUB -> BinOp.SUB
    SpikeOpCode.SHL -> BinOp.SHL
    SpikeOpCode.SHR -> BinOp.SHR
}
private fun neuronOpToBin(op: NeuronOpCode): BinOp = when (op) {
    NeuronOpCode.ADD -> BinOp.ADD
    NeuronOpCode.SUB -> BinOp.SUB
    NeuronOpCode.SHL -> BinOp.SHL
    NeuronOpCode.SHR -> BinOp.SHR
    else -> error("not a binary op: $op")
}

// Собираем AST из SpikeTx (в синоптический блок)
fun SpikeTx.toAst(module: AstModule) {
    val syn = module.syn()
    for (op in listOps()) {
        val a = spikeOpndToExpr(op.a)
        val b = spikeOpndToExpr(op.b)

        val bin = AstExpr.Bin(spikeOpToBin(op.opcode), a, b)

        val dstRef = AstExpr.FieldRef(
            space = if (op.dstIsExternal) (op.dstExtSpace ?: "NEURON") else "SPIKE",
            name = op.dst
        )

        // логика твоей постановки:
        // — если пишем во внешний нейрон — ExternalAssign;
        // — если dst — локальный DELAY → DelayAssign (мидлварь уже проверяет тип);
        // — иначе обычное Assign.
        if (op.dstIsExternal) {
            syn.stmts += AstStmt.ExternalAssign(dstRef, bin)
        } else {
            // здесь можно проверить, что это delay-поле (если хочется жестко валидировать)
            syn.stmts += AstStmt.DelayAssign(dstRef, bin)
        }
    }
}

// Собираем AST из NeuronTx (в нейронный блок)
fun NeuronTx.toAst(module: AstModule) {
    val neu = module.neu()
    for (op in listOps()) {
        when (op.opcode) {
            NeuronOpCode.ADD, NeuronOpCode.SUB, NeuronOpCode.SHL, NeuronOpCode.SHR -> {
                val a = neuronOpndToExpr(op.a)
                val b = neuronOpndToExpr(op.b)
                val bin = AstExpr.Bin(neuronOpToBin(op.opcode), a, b)
                val dst = AstExpr.FieldRef("NEURON", op.dst)
                neu.stmts += AstStmt.Assign(dst, bin)
            }

            NeuronOpCode.CMP_GE -> {
                val a = neuronOpndToExpr(op.a)
                val b = neuronOpndToExpr(op.b)
                val cmp = AstExpr.Cmp(CmpOp.GE, a, b)
                // сохраняем как Assign в предикат — это тоже нормальный AST-узел:
                val dst = AstExpr.FieldRef("NEURON", op.dst)
                neu.stmts += AstStmt.Assign(dst, cmp) // потребитель (emit) может прочитать предикат из поля
            }

            NeuronOpCode.EMIT -> {
                // интерпретация из твоего соглашения:
                //  a = предикат-поле (NeuronOperand.field)
                //  dst = "spike_out" (без сброса) ИЛИ имя поля для сброса
                //  b = imm(0) или imm(reset)
                val pred = AstExpr.FieldRef("NEURON", op.a.fieldName ?: error("emit predicate name null"))
                val zeroOrReset = neuronOpndToExpr(op.b)
                val cond = AstExpr.Cmp(CmpOp.NE, pred, AstExpr.Imm(0))  // предикат ≠ 0

                val (resetDst, resetImm) =
                    if (op.dst == "spike_out") null to null
                    else AstExpr.FieldRef("NEURON", op.dst) to (zeroOrReset as? AstExpr.Imm)?.value

                neu.stmts += AstStmt.EmitIf(
                    cond = cond,
                    outName = "spike_out",
                    resetDst = resetDst,
                    resetImm = resetImm
                )
            }

            NeuronOpCode.EMIT_IF -> {
                // если позже добавишь «атомарный» emit-if
                val a = neuronOpndToExpr(op.a)
                val b = neuronOpndToExpr(op.b)
                val cmpOp = when (op.cmp) {
                    NeuronCmp.GT -> CmpOp.GT
                    NeuronCmp.GE -> CmpOp.GE
                    NeuronCmp.LT -> CmpOp.LT
                    NeuronCmp.LE -> CmpOp.LE
                    NeuronCmp.EQ -> CmpOp.EQ
                    NeuronCmp.NE -> CmpOp.NE
                    else -> CmpOp.GE
                }
                val cond = AstExpr.Cmp(cmpOp, a, b)
                val resetDst = op.resetDst?.let { AstExpr.FieldRef("NEURON", it) }
                neu.stmts += AstStmt.EmitIf(
                    cond = cond,
                    outName = op.dst,
                    resetDst = resetDst,
                    resetImm = op.resetImm
                )
            }
        }
    }
}

// Построение единого AST (корня)
fun buildAst(spike: SpikeTx, neuron: NeuronTx, name: String = "lif_module"): AstModule {
    val m = AstModule(name)
    spike.toAst(m)
    neuron.toAst(m)
    return m
}