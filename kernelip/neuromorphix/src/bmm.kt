package neuromorphix

import cyclix.Generic
import hwast.hw_imm
import hwast.hw_var
import hwast.*
import cyclix.*
import neuromorphix.NmMath



// ===== TickGen: конфиг + компонент =====
// TickGen.kt

enum class TimeUnit { NS, US, MS, S }

data class TickGenCfg(
    val timeslot: Long,   // период «тика» в выбранных единицах
    val unit: TimeUnit,   // NS/US/MS/S
    val clkPeriodNs: Long // период такта в наносекундах (например, 10нс для 100МГц)
)

class TickGen(private val name: String = "tickgen") {

    private fun unitToNs(u: TimeUnit): Long = when (u) {
        TimeUnit.NS -> 1L
        TimeUnit.US -> 1_000L
        TimeUnit.MS -> 1_000_000L
        TimeUnit.S  -> 1_000_000_000L
    }

    /**
     * Генерит однотактный импульс tick каждые N тактов.
     * - tick = 1 на один такт при совпадении счётчика.
     * - Иначе tick = 0 и счётчик инкрементится.
     */
    fun emit(
        g: Generic,
        cfg: TickGenCfg,
        tickSignal: hw_var
    ) {
        require(cfg.timeslot > 0) { "$name: timeslot must be > 0" }
        require(cfg.clkPeriodNs > 0) { "$name: clkPeriodNs must be > 0" }

        val timeslotNs = cfg.timeslot * unitToNs(cfg.unit)
        var tickCycles = timeslotNs / cfg.clkPeriodNs
        if (tickCycles <= 0L) tickCycles = 1L

        val period    = g.uglobal("${name}_period",  hw_imm(tickCycles.toInt()))
        val counter   = g.uglobal("${name}_counter", "0")
        val lastCount = g.uglobal("${name}_last",    hw_imm((tickCycles - 1).toInt()))
        val nextCnt   = g.uglobal("${name}_next",    "0")

        // tick по умолчанию 0 на каждом такте
        tickSignal.assign(0)

        // if (counter == lastCount) { tick=1; counter=0; } else { counter++; }
        g.begif(g.eq2(counter, lastCount)); run {
            tickSignal.assign(1)
            counter.assign(0)
        }; g.endif()

        g.begelse(); run {
            nextCnt.assign(counter.plus(1))
            counter.assign(nextCnt)
        }; g.endif()
    }
}


// FifoInput.kt

data class FifoCfg(
    val name: String,
    val dataWidth: Int,
    val depth: Int,
    val creditWidth: Int = 8,
    val useTickDoubleBuffer: Boolean = true   // если true — переключение «активного» банка по tick
)

data class FifoInIF(
    // внешний интерфейс (входящие спайки)
    val wr_i: hw_var,         // порт IN: запрос записи
    val wr_data_i: hw_var,    // порт IN: данные на запись
    val full_o: hw_var,       // порт OUT: FIFO полно

    // интерфейс чтения внутрь ядра
    val rd_o: hw_var,         // глобал/локал: запрос чтения (ядро ставит 1, чтобы вычитать слово)
    val rd_data_o: hw_var,    // глобал: данные на чтение
    val empty_o: hw_var,      // глобал: FIFO пуст

    // кредиты (удобно для наблюдения/управления)
    val wr_credit_o: hw_var,  // глобал: доступный размер записи «активного» банка
    val rd_credit_o: hw_var   // локал:  доступный размер чтения «пассивного» банка
)



class FifoInput(private val instName: String = "in_fifo") {

    fun emit(
        g: Generic,
        cfg: FifoCfg,
        tick: hw_var
    ): FifoInIF {
        val name = cfg.name

        // ширины
        val ptrW = NmMath.log2ceil(cfg.depth)

        // ===== Внешние порты записи =====
        val wr_i      = g.uport("wr_$name", PORT_DIR.IN,  hw_dim_static(1),        "0")
        val wr_data_i = g.uport("wr_data_$name", PORT_DIR.IN, hw_dim_static(cfg.dataWidth), "0")
        val full_o    = g.uport("full_$name", PORT_DIR.OUT, hw_dim_static(1),      "0")

        // ===== Внутренний интерфейс чтения =====
        val rd_o      = g.uglobal("rd_$name",       hw_dim_static(1),        "0")
        val rd_data_o = g.uglobal("rd_data_$name",  hw_dim_static(cfg.dataWidth), "0")
        val empty_o   = g.uglobal("empty_$name",    hw_dim_static(1),        "1")

        // ===== Регистровый массив FIFO =====
        val memDim = hw_dim_static(cfg.dataWidth).apply { add(cfg.depth, 0) }
        val mem    = g.uglobal("mem_$name", memDim, "0")

        // ===== Указатели и флаги =====
        val wptr      = g.uglobal("wptr_$name",       hw_dim_static(ptrW), "0")
        val rptr      = g.uglobal("rptr_$name",       hw_dim_static(ptrW), "0")
        val wptr_n    = g.uglobal("wptr_n_$name",     hw_dim_static(ptrW), "0")
        val rptr_n    = g.uglobal("rptr_n_$name",     hw_dim_static(ptrW), "0")
        val full_r    = g.uglobal("full_r_$name",     hw_dim_static(1),    "0")
        val full_n    = g.uglobal("full_n_$name",     hw_dim_static(1),    "0")
        val empty_n   = g.uglobal("empty_n_$name",    hw_dim_static(1),    "1")
        full_o.assign(full_r)

        // ===== Двойной банк кредитов (пишем в «активный», читаем из «пассивного») =====
        val crDim = hw_dim_static(cfg.creditWidth).apply { add(2, 0) } // [2][creditWidth]
        val credit = g.uglobal("credit_$name", crDim, "0")
        val act    = g.uglobal("act_$name",    hw_dim_static(1), "0")   // активный банк (0/1)

        if (cfg.useTickDoubleBuffer) {
            g.begif(g.eq2(tick, 1)); run {
                act.assign(g.bnot(act))
            }; g.endif()
        }

        val wr_en = g.uglobal("wr_en_$name", hw_dim_static(1), "0")
        wr_en.assign(g.land(wr_i, g.bnot(full_r)))

        // ===== Запись =====
        g.begif(g.eq2(wr_en, 1)); run {
            mem[wptr].assign(wr_data_i)
            credit[act].assign(credit[act].plus(1))
        }; g.endif()

        // ===== Доступные кредиты на запись/чтение =====
        val wr_credit_o = g.uglobal("wr_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        val rd_credit_o = g.ulocal ("rd_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        wr_credit_o.assign(credit[act])
        rd_credit_o.assign(credit[g.bnot(act)])

        // ===== Данные на чтение =====
        rd_data_o.assign(mem[rptr])

        // ===== Обновление указателей/флагов =====
        wptr.assign(wptr_n)
        rptr.assign(rptr_n)
        empty_o.assign(empty_n)
        full_r.assign(full_n)

        // ===== Алгоритм на комбинации {wr, rd} через case =====
        // cnct(wr, rd): 01=чтение, 10=запись, 11=оба
        g.begcase(g.cnct(wr_i, rd_o)); run {

            // 2'b01 — чтение
            g.begbranch(1); run {
            g.begif(g.bnot(empty_o)); run {
            rptr_n.assign(rptr.plus(1))
            full_n.assign(0)

            // кредиты в «пассивном» банке уменьшаем (но не уходим в минус)
            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[g.bnot(act)].assign(credit[g.bnot(act)].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[g.bnot(act)].assign(0)
        }; g.endif()

            g.begif(g.eq2(rptr.plus(1), wptr)); run {
            empty_n.assign(1)
        }; g.endif()
        }; g.endif()
            g.begelse(); run {
            credit[g.bnot(act)].assign(0)   // если реально пусто — кредиты на чтение = 0
        }; g.endif()
        }; g.endbranch()

            // 2'b10 — запись
            g.begbranch(2); run {
            g.begif(g.bnot(full_r)); run {
            wptr_n.assign(wptr.plus(1))
            empty_n.assign(0)
            g.begif(g.eq2(wptr.plus(1), rptr)); run {
            full_n.assign(1)
        }; g.endif()
        }; g.endif()
        }; g.endbranch()

            // 2'b11 — запись и чтение
            g.begbranch(3); run {
            wptr_n.assign(wptr.plus(1))
            rptr_n.assign(rptr.plus(1))

            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[g.bnot(act)].assign(credit[g.bnot(act)].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[g.bnot(act)].assign(0)
        }; g.endif()
        }; g.endbranch()
        }; g.endcase()

        return FifoInIF(
            wr_i = wr_i,
            wr_data_i = wr_data_i,
            full_o = full_o,
            rd_o = rd_o,
            rd_data_o = rd_data_o,
            empty_o = empty_o,
            wr_credit_o = wr_credit_o,
            rd_credit_o = rd_credit_o
        )
    }
}

//// те же утилиты/конфиги, что и у входного FIFO
//data class FifoCfg(
//    val name: String,
//    val dataWidth: Int,
//    val depth: Int,
//    val creditWidth: Int = 8,
//    val useTickDoubleBuffer: Boolean = true
//)

// для симметрии с FifoInIF
data class FifoOutIF(
    // ВНЕШНИЙ read-интерфейс
    val rd_i: hw_var,          // IN  (порт): внешний строб чтения
    val rd_data_o: hw_var,     // OUT (порт): внешние данные
    val empty_o: hw_var,       // OUT (порт): пусто для внешнего мира

    // ВНУТРЕННИЙ write-интерфейс (ядро -> FIFO)
    val we_i: hw_var,          // IN  (глобал): строб записи от ядра
    val wr_data_i: hw_var,     // IN  (глобал): данные от ядра
    val full_o: hw_var,        // OUT (глобал): бэкпрешер ядру

    // диагностические/служебные кредиты (как и у входного)
    val rd_credit_o: hw_var,
    val wr_credit_o: hw_var
)

class FifoOutput(private val instName: String = "out_fifo") {

    // если у тебя уже есть NmMath.log2ceil — используй его
    private fun NmMath.log2ceil(x: Int): Int {
        require(x > 0) { "x must be > 0" }
        var v = x - 1
        var r = 0
        while (v > 0) { v = v shr 1; r++ }
        return maxOf(r, 1)
    }

    fun emit(
        g: Generic,
        cfg: FifoCfg,
        tick: hw_var
    ): FifoOutIF {
        val name = cfg.name
        val ptrW = NmMath.log2ceil(cfg.depth)

        // ===== ВНЕШНИЕ ПОРТЫ ЧТЕНИЯ =====
        val rd_i      = g.uport("rd_$name",       PORT_DIR.IN,  hw_dim_static(1),             "0")
        val rd_data_o = g.uport("rd_data_$name",  PORT_DIR.OUT, hw_dim_static(cfg.dataWidth), "0")
        val empty_o   = g.uport("empty_$name",    PORT_DIR.OUT, hw_dim_static(1),             "1")

        // ===== ВНУТРЕННИЙ ИНТЕРФЕЙС ЗАПИСИ (ядро -> FIFO) =====
        val we_i      = g.uglobal("we_$name",      hw_dim_static(1),             "0")
        val wr_data_i = g.uglobal("wr_data_$name", hw_dim_static(cfg.dataWidth), "0")
        val full_o    = g.uglobal("full_$name",    hw_dim_static(1),             "0")

        // ===== ПАМЯТЬ FIFO =====
        val memDim = hw_dim_static(cfg.dataWidth).apply { add(cfg.depth, 0) }
        val mem    = g.uglobal("mem_$name", memDim, "0")

        // ===== УКАЗАТЕЛИ/ФЛАГИ =====
        val wptr   = g.uglobal("wptr_$name",   hw_dim_static(ptrW), "0")
        val rptr   = g.uglobal("rptr_$name",   hw_dim_static(ptrW), "0")
        val wptr_n = g.uglobal("wptr_n_$name", hw_dim_static(ptrW), "0")
        val rptr_n = g.uglobal("rptr_n_$name", hw_dim_static(ptrW), "0")

        val full_r = full_o
        val full_n = g.uglobal("full_n_$name",  hw_dim_static(1), "0")
        val empty_r= g.uglobal("empty_r_$name", hw_dim_static(1), "1")
        val empty_n= g.uglobal("empty_n_$name", hw_dim_static(1), "1")

        // наружу — зарегистрированное empty
        empty_o.assign(empty_r)

        // ===== КРЕДИТ-БАНКИ (двойная буферизация по tick) =====
        val crDim  = hw_dim_static(cfg.creditWidth).apply { add(2, 0) }  // [2][creditWidth]
        val credit = g.uglobal("credit_$name", crDim, "0")
        val act    = g.uglobal("act_$name",    hw_dim_static(1), "0")    // активный банк (0/1) — для записей

        if (cfg.useTickDoubleBuffer) {
            g.begif(g.eq2(tick, 1)); run {
                act.assign(g.bnot(act))
            }; g.endif()
        }

        // ===== ЗАПИСЬ (ядро -> FIFO) =====
        val wr_en = g.uglobal("wr_en_$name", hw_dim_static(1), "0")
        wr_en.assign(g.land(we_i, g.bnot(full_r)))

        g.begif(g.eq2(wr_en, 1)); run {
            mem[wptr].assign(wr_data_i)
            credit[act].assign(credit[act].plus(1))
        }; g.endif()

        // ===== КРЕДИТЫ НА ВЫХОД =====
        val wr_credit_o = g.uglobal("wr_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        val rd_credit_o = g.ulocal ("rd_credit_$name", hw_dim_static(cfg.creditWidth), "0")
        wr_credit_o.assign(credit[act])
        rd_credit_o.assign(credit[g.bnot(act)])

        // ===== ДАННЫЕ ДЛЯ НАРУЖНЕГО ЧТЕНИЯ =====
        rd_data_o.assign(mem[rptr])

        // ===== ОБНОВЛЕНИЕ УКАЗАТЕЛЕЙ/ФЛАГОВ =====
        wptr.assign(wptr_n)
        rptr.assign(rptr_n)
        empty_r.assign(empty_n)
        full_r.assign(full_n)

        // ===== КОМБИНАЦИЯ СОБЫТИЙ {we, rd} =====
        // 01 — только чтение; 10 — только запись; 11 — оба
        g.begcase(g.cnct(we_i, rd_i)); run {

            // 2'b01 чтение наружу
            g.begbranch(1); run {
            g.begif(g.bnot(empty_r)); run {
            rptr_n.assign(rptr.plus(1))
            full_n.assign(0)

            // кредиты «пассивного» банка уменьшаем (не уходим в минус)
            val other = g.bnot(act)
            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[other].assign(credit[other].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[other].assign(0)
        }; g.endif()

            // пусто, если догнали write pointer
            g.begif(g.eq2(rptr.plus(1), wptr)); run {
            empty_n.assign(1)
        }; g.endif()
        }; g.endif()
            g.begelse(); run {
            // реально пусто — кредитов на чтение нет
            credit[g.bnot(act)].assign(0)
        }; g.endif()
        }; g.endbranch()

            // 2'b10 запись из ядра
            g.begbranch(2); run {
            g.begif(g.bnot(full_r)); run {
            wptr_n.assign(wptr.plus(1))
            empty_n.assign(0)
            g.begif(g.eq2(wptr.plus(1), rptr)); run {
            full_n.assign(1)
        }; g.endif()
        }; g.endif()
        }; g.endbranch()

            // 2'b11 и запись, и чтение
            g.begbranch(3); run {
            wptr_n.assign(wptr.plus(1))
            rptr_n.assign(rptr.plus(1))

            val other = g.bnot(act)
            g.begif(g.gr(rd_credit_o, 0)); run {
            credit[other].assign(credit[other].minus(1))
        }; g.endif()
            g.begelse(); run {
            credit[other].assign(0)
        }; g.endif()
        }; g.endbranch()
        }; g.endcase()

        return FifoOutIF(
            rd_i = rd_i,
            rd_data_o = rd_data_o,
            empty_o = empty_o,
            we_i = we_i,
            wr_data_i = wr_data_i,
            full_o = full_o,
            rd_credit_o = rd_credit_o,
            wr_credit_o = wr_credit_o
        )
    }
}

// === Конфиг компонента динамических параметров ===
data class DynParamCfg(
    val name: String,      // логическое имя массива
    val bitWidth: Int,     // разрядность слова
    val count: Int,        // число слов (обычно = числу постсинаптических нейронов)
    val initZero: Boolean = true  // инициализировать нулём
)

// === Интерфейс к памяти динамических параметров ===
// Внешние сущности (обработчики фаз/ФСМ) будут:
//   - ставить rd_idx -> читать rd_data (комбин.)
//   - при we=1 писать wr_data в mem[wr_idx] (синхронно)
data class DynParamIF(
    val mem: hw_var,     // сам регистровый массив [count][bitWidth]
    val rd_idx: hw_var,  // индекс чтения
    val rd_data: hw_var, // данные на чтение (mem[rd_idx])
    val wr_idx: hw_var,  // индекс записи
    val wr_data: hw_var, // данные на запись
    val we: hw_var       // write enable
)

// === Генератор памяти динамических параметров ===
class DynamicParamMem(private val instName: String = "dynp") {

    fun emit(g: Generic, cfg: DynParamCfg): DynParamIF {
        val name = cfg.name

        // --- Регистровый массив: mem_<name> : [count][bitWidth]
        val memDim = hw_dim_static(cfg.bitWidth).apply { add(cfg.count, 0) }
        val mem    = g.uglobal("mem_$name", memDim, if (cfg.initZero) "0" else "undef")

        // --- Интерфейсные сигналы
        val rd_idx   = g.uglobal("rd_idx_$name",   hw_dim_static(NmMath.log2ceil(cfg.count)), "0")
        val rd_data  = g.uglobal("rd_data_$name",  hw_dim_static(cfg.bitWidth),        "0")

        val wr_idx   = g.uglobal("wr_idx_$name",   hw_dim_static(NmMath.log2ceil(cfg.count)), "0")
        val wr_data  = g.uglobal("wr_data_$name",  hw_dim_static(cfg.bitWidth),        "0")
        val we       = g.uglobal("we_$name",       hw_dim_static(1),                   "0")

        // --- Комбин. чтение
        rd_data.assign(mem[rd_idx])

        // --- Синхронная запись (регистровая, под we)
        g.begif(g.eq2(we, 1)); run {
            mem[wr_idx].assign(wr_data)
        }; g.endif()

        return DynParamIF(
            mem = mem,
            rd_idx = rd_idx,
            rd_data = rd_data,
            wr_idx = wr_idx,
            wr_data = wr_data,
            we = we
        )
    }
}



// === конфиг интерфейса статической (синаптической) памяти ===
data class StaticMemCfg(
    val name: String,         // базовое имя, напр. "wmem_l1"
    val wordWidth: Int,       // разрядность слова (weightBitWidth)
    val depth: Int,           // число слов (presyn * postsyn)
    // адресация:
//    val addrMode: AddrMode = AddrMode.CONCAT,
    val preIdxWidth: Int? = null,   // для CONCAT: ширина пресинапт. индекса
    val postIdxWidth: Int? = null,  // для CONCAT: ширина постсинапт. индекса
    val postsynCount: Int? = null,  // для LINEAR: множитель
    // сигнал enable на чтение
    val useEn: Boolean = true
)

// === интерфейс, который вернёт компонент ===
// наружу (порты): adr_o, en_o?, dat_i
// внутрь (регистры): adr_r, en_r, dat_r
data class StaticMemIF(
    // внутренние регистры, которыми управляет ядро
    val adr_r: hw_var,
    val en_r: hw_var?,
    val dat_r: hw_var,
    // внешние порты для подключения к контроллеру памяти
    val adr_o: hw_var,
    val en_o: hw_var?,
    val dat_i: hw_var,
    // сервис: готовый addrWidth
    val addrWidth: Int
)


// === генератор интерфейса к статической памяти (read-only) ===
class StaticMemIfGen(private val instName: String = "wmem_if") {

    fun emit(g: Generic, cfg: StaticMemCfg): StaticMemIF {
        val name = cfg.name

        // --- адресная ширина
        val addrW = NmMath.log2ceil(cfg.depth)

        // --- внешние порты: адрес и данные
        val adr_o = g.uport("adr_${name}", PORT_DIR.OUT, hw_dim_static(addrW), "0")
        val dat_i = g.uport("dat_${name}", PORT_DIR.IN,  hw_dim_static(cfg.wordWidth), "0")

        // --- опционально EN (наружу)
        val en_o  = if (cfg.useEn)
            g.uport("en_${name}",  PORT_DIR.OUT, hw_dim_static(1), "0")
        else null

        // --- внутренние регистры (ядро пишет адрес и EN; ядро читает dat_r)
        val adr_r = g.uglobal("adr_${name}_r", hw_dim_static(addrW), "0")
        val dat_r = g.uglobal("dat_${name}_r", hw_dim_static(cfg.wordWidth), "0")
        val en_r  = if (cfg.useEn) g.uglobal("en_${name}_r",  hw_dim_static(1), "0") else null

        // --- регистрируем входные данные (при желании можно сделать опц.)
        dat_r.assign(dat_i)

        // --- выводим наружу адрес/enable
        adr_o.assign(adr_r)
        if (en_o != null && en_r != null) en_o.assign(en_r)

        return StaticMemIF(
            adr_r = adr_r,
            en_r = en_r,
            dat_r = dat_r,
            adr_o = adr_o,
            en_o = en_o,
            dat_i = dat_i,
            addrWidth = addrW
        )
    }

}


// ===== топология связности =====
enum class TopologyKind {
    FULLY_CONNECTED,
    RECURRENT,
    SPARSE,
    CONV
}

// Можно расширять полями под конкретные топологии (stride, kernel, списки смежности и т.п.)
data class TopologySpec(
    val kind: TopologyKind = TopologyKind.FULLY_CONNECTED
)

// ===== рантайм-регистры/параметры, приходящие "снаружи" =====
// Здесь мы НЕ создаём регистры — просто принимаем ссылки на уже созданные hw_var
data class RegIf(
    val postsynCount: hw_var,     // реальное число постсинаптических нейронов для обхода
    val baseAddr:     hw_var? = null // опционально: базовый адрес в памяти весов
)

// ===== интерфейс селектора наружу =====
data class SynSelIF(
    // вход управления
    val start_i:   hw_var,     // импульс "начать обход для указанного preIdx"
    val preIdx_i:  hw_var,     // индекс пресинапс. нейрона

    // выходы статуса
    val busy_o:    hw_var,     // селектор занят (идёт обход)
    val done_o:    hw_var,     // единичный импульс по завершению обхода

    // отладка/наблюдение
    val postIdx_o: hw_var      // текущий пост-индекс
)

// ===== конфигурация селектора =====
data class SynSelCfg(
    val name:          String,
    val addrWidth:     Int,        // ширина адреса в StaticMem
    val preWidth:      Int,        // ширина preIdx
    val postWidth:     Int,        // максимальная ширина postIdx-счётчика
    val stepByTick:    Boolean = false, // инкрементировать по tick (true) или каждый такт (false)
    val useLinearAddr: Boolean = true   // адресация линейная (pre*postsyn+post) или конкатенацией
)

// ===== селектор синапсов =====
class SynapseSelector(private val instName: String = "syn_sel") {

    // было: private fun addrConcat(...): hw_expr = g.cnct(...)
    private fun addrConcat(g: Generic, preIdx: hw_var, postIdx: hw_var) =
        g.cnct(preIdx, postIdx)

    // было: private fun addrLinear(...): hw_expr = g.add(g.mul(...), postIdx)
    private fun addrLinear(g: Generic, preIdx: hw_var, postIdx: hw_var, postsynCount: hw_var) =
        g.add(g.mul(preIdx, postsynCount), postIdx)

    /**
     * Генерация селектора:
     * - принимает TopologySpec, RegIf, tick (опц.), интерфейс статической памяти (StaticMemIf)
     * - создаёт простую FSM: IDLE→RUN→(DONE)
     * - в RUN перебирает postIdx от 0 до (postsynCount-1), выдаёт адрес в memIf.adr_r и ставит memIf.en_r=1
     */
    fun emit(
        g: Generic,
        cfg: SynSelCfg,
        topo: TopologySpec,
        rt: RegIf,
        tick: hw_var?,                     // если cfg.stepByTick=true — используем этот тик
        mem: StaticMemIF                   // интерфейс к памяти (адрес/вкл/данные)
    ): SynSelIF {

        val name = cfg.name

        // ===== управляющие входы (локальные/globals, чтобы подключить их извне нейроморфика)
        val start_i  = g.uglobal("start_$name", hw_dim_static(1), "0")
        val preIdx_i = g.uglobal("preidx_$name", hw_dim_static(cfg.preWidth), "0")

        // ===== статус/наблюдение
        val busy_o   = g.uglobal("busy_$name", hw_dim_static(1), "0")
        val done_o   = g.uglobal("done_$name", hw_dim_static(1), "0")
        val postIdx  = g.uglobal("postidx_$name", hw_dim_static(cfg.postWidth), "0")
        val preLatched = g.uglobal("prelatched_$name", hw_dim_static(cfg.preWidth), "0")

        // внутренний enable шага
        val step_en = g.uglobal("step_en_$name", hw_dim_static(1), "0")
        step_en.assign(
            if (cfg.stepByTick) {
                if (tick != null) g.eq2(tick, 1) else hw_imm(0)   // защита от null
            } else {
                hw_imm(1)
            }
        )

        // == FSM ==
        val S_IDLE = 0
        val S_RUN  = 1
        val state  = g.uglobal("state_$name", hw_dim_static(1), "0")
        val state_n= g.uglobal("state_n_$name", hw_dim_static(1), "0")

        // дефолты
        state.assign(state_n)
        done_o.assign(0)

        // mem.en_r: по умолчанию 0, включаем только на акт. шаг
        mem.en_r?.assign(0)

        // Локальный сигнал «делаем шаг»
        val doStep = g.land(step_en, busy_o)

        // ===== IDLE: захват preIdx, обнуление postIdx =====
        g.begif(g.eq2(state, S_IDLE)); run {
            busy_o.assign(0)

            g.begif(g.eq2(start_i, 1)); run {
            preLatched.assign(preIdx_i)
            postIdx.assign(0)
            busy_o.assign(1)
            state_n.assign(S_RUN)
        }; g.endif()
        }; g.endif()

        // ===== RUN: перебор пост-индексов, генерация адреса =====
        g.begif(g.eq2(state, S_RUN)); run {
            busy_o.assign(1)

            // вычисляем адрес для текущей пары (preLatched, postIdx)
            val addrExpr = when (topo.kind) {
                TopologyKind.FULLY_CONNECTED -> {
                    if (cfg.useLinearAddr) {
                        val adrLin = addrLinear(g, preLatched, postIdx, rt.postsynCount)
                        // прибавляем baseAddr, если она задана
                        if (rt.baseAddr != null) g.add(adrLin, rt.baseAddr) else adrLin
                    } else {
                        // конкатенация (корректно, если глубины — степени двойки)
                        val adrCat = addrConcat(g, preLatched, postIdx)
                        if (rt.baseAddr != null) g.add(adrCat, rt.baseAddr) else adrCat
                    }
                }
                // заглушки на будущее: здесь ты подставишь свою адресацию
                TopologyKind.RECURRENT,
                TopologyKind.SPARSE,
                TopologyKind.CONV -> {
                    // пока просто линейная (временная заглушка)
                    val adrLin = addrLinear(g, preLatched, postIdx, rt.postsynCount)
                    if (rt.baseAddr != null) g.add(adrLin, rt.baseAddr) else adrLin
                }
            }

            // выдаём адрес и импульс чтения
            mem.adr_r.assign(addrExpr)
            mem.en_r?.assign(1)

            // шаг по postIdx по разрешению
            g.begif(g.eq2(doStep, 1)); run {
            val last = g.eq2(postIdx, g.sub(rt.postsynCount, hw_imm(1)))
            g.begif(last); run {
            // завершаем обход
            done_o.assign(1)
            busy_o.assign(0)
            state_n.assign(S_IDLE)
        }; g.endif()
            g.begelse(); run {
            postIdx.assign(postIdx.plus(1))
        }; g.endif()
        }; g.endif()
        }; g.endif()

        return SynSelIF(
            start_i   = start_i,
            preIdx_i  = preIdx_i,
            busy_o    = busy_o,
            done_o    = done_o,
            postIdx_o = postIdx
        )
    }
}
