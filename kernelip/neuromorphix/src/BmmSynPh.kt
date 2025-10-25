package neuromorphix

import cyclix.Generic
import hwast.*

enum class SynOpKind { ADD, SUB, REPLACE /* при желании умножение, saturate и т.п. */ }

data class SynPhaseCfg(
    val name: String,
    val op: SynOpKind = SynOpKind.ADD,
    val preIdxWidth: Int   // разрядность индекса пресинаптического нейрона
)

/** Служебный интерфейс статусов обработчика (можно расширять) */
data class SynPhaseIF(
    val busy_o: hw_var,        // ядро занято обработкой спайка/синапсов
    val done_o: hw_var,        // импульс: входная очередь опустела (фаза закончена)
    val curPre_o: hw_var,      // текущий пресинаптический индекс (для отладки)
    val curPost_o: hw_var      // текущий постсинаптический индекс (из селектора)
)

/**
 * Обработчик синаптической фазы.
 * Принимает ИНТЕРФЕЙСЫ уже созданных компонентов: входной FIFO, селектор, интерфейсы статической/динамической памяти
 * и (опционально) набор рантайм-регистров с postsynCount/baseAddr, если они требуются селектору.
 */
class SynapticPhase(private val instName: String = "syn_phase") {

    fun emit(
        g: Generic,
        cfg: SynPhaseCfg,

        // входящие спайки (FIFO)
        inFifo: FifoInIF,

        // селектор синапсов (уже привязан к StaticMemIF)
        sel: SynSelIF,

        // статическая память весов (dat_r = зарегистрированный выход из контроллера)
        wmem: StaticMemIF,

        // динамические параметры пост-нейронов (например, мембранные потенциалы)
        dyn: DynParamIF
    ): SynPhaseIF {

        val name = cfg.name

        // === служебные регистры ===
        val busy   = g.uglobal("${name}_busy",   hw_dim_static(1), "0")
        val done   = g.uglobal("${name}_done",   hw_dim_static(1), "0")
        val preLat = g.uglobal("${name}_pre", hw_dim_static(cfg.preIdxWidth), "0")
        val curPre = preLat
        val curPost= sel.postIdx_o

        // внешним блокам (FSM) удобно видеть эти статусы
        val busy_o = busy
        val done_o = done
        val curPre_o  = curPre
        val curPost_o = curPost

        // === FSM обработчика ===
        val S_IDLE     = 0
        val S_FETCH    = 1
        val S_STARTSEL = 2
        val S_RUN      = 3

        val state  = g.uglobal("${name}_st",  hw_dim_static(2), "0")
        val stateN = g.uglobal("${name}_stn", hw_dim_static(2), "0")
        state.assign(stateN)

        // дефолты
        done.assign(0)
        inFifo.rd_o.assign(0)          // чтение FIFO по стробу
        sel.start_i.assign(0)          // старт селектора по импульсу

        // === IDLE: если FIFO не пуст, берём слово ===
        g.begif(g.eq2(state, S_IDLE)); run {
            busy.assign(0)

            g.begif(g.eq2(inFifo.empty_o, 0)); run {
            inFifo.rd_o.assign(1)           // запросить чтение (1 такт)
            stateN.assign(S_FETCH)
        }; g.endif()

            g.begelse(); run {
            // очередь пуста — фаза завершена
            done.assign(1)
        }; g.endif()
        }; g.endif()

        // === FETCH: зафиксировать пресинаптический индекс, запустить селектор ===
        g.begif(g.eq2(state, S_FETCH)); run {
            busy.assign(1)
            // снимаем rd_o (импульс уже отдан), забираем данные
            inFifo.rd_o.assign(0)
            preLat.assign(inFifo.rd_data_o)

            // подаём пресинаптический индекс на селектор и стартуем его
            sel.preIdx_i.assign(inFifo.rd_data_o)
            sel.start_i.assign(1)              // импульс старта
            stateN.assign(S_STARTSEL)
        }; g.endif()

        // === STARTSEL: обнуляем start, переходим в RUN ===
        g.begif(g.eq2(state, S_STARTSEL)); run {
            busy.assign(1)
            sel.start_i.assign(0)              // убрать импульс
            stateN.assign(S_RUN)
        }; g.endif()

        // === RUN: на каждом шаге селектора выполняем операцию dyn[memIdx] (op) wmem.dat_r ===
        g.begif(g.eq2(state, S_RUN)); run {
            busy.assign(1)

            // адрес пост-нейрона = sel.postIdx_o
            // 1) выставляем индекс чтения динамики (комбинационно читаем текущее значение)
            dyn.rd_idx.assign(sel.postIdx_o)

            // 2) формируем новое значение по cfg.op
            //    ВНИМАНИЕ: dyn.rd_data и wmem.dat_r – зарегистрированные сигналы;
            //    при необходимости можно вставить пайплайн/латчи.
            val newVal = when (cfg.op) {
                SynOpKind.ADD     -> dyn.rd_data.plus(wmem.dat_r)
                SynOpKind.SUB     -> dyn.rd_data.minus(wmem.dat_r)
                SynOpKind.REPLACE -> wmem.dat_r
            }

            // 3) пишем обратно в ту же ячейку
            dyn.wr_idx.assign(sel.postIdx_o)
            dyn.wr_data.assign(newVal)
            dyn.we.assign(1)

            // 4) если селектор закончил обход — снимаем we и возвращаемся в IDLE или берём следующий спайк
            g.begif(g.eq2(sel.done_o, 1)); run {
            dyn.we.assign(0)
            stateN.assign(S_IDLE)
        }; g.endif()
        }; g.endif()

        return SynPhaseIF(
            busy_o = busy_o,
            done_o = done_o,
            curPre_o = curPre_o,
            curPost_o = curPost_o
        )
    }
}