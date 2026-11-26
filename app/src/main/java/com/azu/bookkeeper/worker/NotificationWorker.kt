package com.azu.bookkeeper.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.repository.AccountRepository
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.BudgetRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/** 预算超支提醒 + 每日记账提醒。 */
@HiltWorker
class NotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val billRepository: BillRepository,
    private val budgetRepository: BudgetRepository,
    private val bookRepository: BookRepository,
    private val settings: SettingsRepository,
    private val accountRepository: AccountRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): WorkResult {
        val bookId = settings.defaultBookId.first().takeIf { it > 0L }
            ?: bookRepository.findDefault().getOrNull()?.bookId
            ?: return WorkResult.success()
        val now = DateTimeUtils.nowMillis()
        val start = DateTimeUtils.startOfMonth(now)
        val end = DateTimeUtils.endOfMonth(now)
        val spent = billRepository.sumByType(bookId, start, end)
            .getOrDefault(emptyMap())[BillType.EXPENSE] ?: 0L
        val budget = budgetRepository.findByBookAndMonth(bookId, DateTimeUtils.monthKey(now)).getOrNull()
        // 总预算兜底：用户只设了分类预算时，总预算 = 各分类预算之和（与预算页同一规则）。
        val totalLimitFen = budget?.effectiveTotalLimitFen ?: 0L

        if (totalLimitFen > 0L && spent > totalLimitFen) {
            showNotification(
                title = "预算已超支",
                text = "本月已支出 ${Money.format(spent)}，超出预算 ${Money.format(spent - totalLimitFen)}",
                channel = Constants.CHANNEL_REMINDER,
                notificationId = 2001
            )
        } else {
            showNotification(
                title = "该记一笔啦",
                text = "打开少花点儿，记录今天的收支",
                channel = Constants.CHANNEL_REMINDER,
                notificationId = 2002
            )
        }
        // 信用卡还款日提醒：还款日在 3 天内时提醒（每天最多一条，避免打扰）
        checkCardDueReminder(bookId)

        return WorkResult.success()
    }

    /**
     * 信用卡还款日提醒。
     *
     * 主流做法：在账单日/还款日临近时提前提醒，只推一次而非每天轰炸。
     * 这里按「距还款日 ≤ 3 天」触发，并用 notificationId 固定为同一条，
     * 系统在当天只会保留最新一条，不会堆积。
     */
    private suspend fun checkCardDueReminder(bookId: Long) {
        val accounts = runCatching { accountRepository.observeAll(bookId).first() }
            .getOrDefault(emptyList())
        val today = java.time.LocalDate.now().dayOfMonth
        accounts.firstOrNull { account ->
            account.hasCardReminder && kotlin.math.abs(account.dueDay - today) <= 3
        }?.let { account ->
            showNotification(
                title = "信用卡还款提醒",
                text = "${account.name} 还款日快到了（${account.dueDay}号），记得还款",
                channel = Constants.CHANNEL_REMINDER,
                notificationId = 2003
            )
        }
    }

    private fun showNotification(title: String, text: String, channel: String, notificationId: Int) {
        createChannel(channel)
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_quick_add)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // 统一守卫：部分 OEM ROM 在无通知权限时不抛 SecurityException 而是阻塞主线程（ANR）
        com.azu.bookkeeper.core.NotifyGuard.safeNotify(applicationContext, notificationId, notification)
    }

    private fun createChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val name = applicationContext.getString(R.string.notification_channel_reminder)
        val existing = manager.getNotificationChannel(channelId)
        if (existing == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
