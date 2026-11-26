package com.azu.bookkeeper.data.repository

/**
 * 余额净变动计算（从 BillRepositoryImpl 抽出的纯函数）。
 *
 * 为什么单独抽：原先这些方法是 BillRepositoryImpl 的私有扩展，测试只能靠反射调用，
 * 实现一改签名测试就整片失败且看不出原因。抽成纯对象后：
 * 1. 生产代码与测试调用同一份逻辑，不存在「测试复制公式」的偏差；
 * 2. 可被 JVM 单测直接覆盖，无需 mockk / 反射。
 */
internal object BalanceMath {

    /**
     * 一条账单对所有账户造成的净余额变动。
     * 支出：from 账户减少；收入：from 账户增加；转账：from 减少、to 增加。
     */
    fun netDeltas(
        billType: Int,
        amountFen: Long,
        accountFrom: Long,
        accountTo: Long
    ): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        fun add(accountId: Long, delta: Long) {
            if (accountId <= 0L || delta == 0L) return
            result[accountId] = (result[accountId] ?: 0L) + delta
        }
        when (billType) {
            com.azu.bookkeeper.domain.model.BillType.EXPENSE.code -> add(accountFrom, -amountFen)
            com.azu.bookkeeper.domain.model.BillType.INCOME.code -> add(accountFrom, amountFen)
            com.azu.bookkeeper.domain.model.BillType.TRANSFER.code -> {
                add(accountFrom, -amountFen)
                add(accountTo, amountFen)
            }
        }
        return result
    }

    /** 回滚一条账单：净变动整体取反。 */
    fun negated(deltas: Map<Long, Long>): Map<Long, Long> = deltas.mapValues { -it.value }

    /**
     * 修改账单：旧净变动取反（或按原样）+ 新净变动，抵消为 0 的账户剔除。
     */
    fun merge(
        oldDeltas: Map<Long, Long>,
        newDeltas: Map<Long, Long>,
        reverseOld: Boolean
    ): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        oldDeltas.forEach { (id, delta) ->
            result[id] = (result[id] ?: 0L) + if (reverseOld) -delta else delta
        }
        newDeltas.forEach { (id, delta) ->
            result[id] = (result[id] ?: 0L) + delta
        }
        return result.filterValues { it != 0L }
    }
}
