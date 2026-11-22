package com.azu.bookkeeper.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.azu.bookkeeper.R
import com.azu.bookkeeper.core.Constants
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Money
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.BillType
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
    private val settings: SettingsRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): androidx.work.Result {
        val bookId = settings.defaultBookId.first().takeIf { it > 0L }
            ?: bookRepository.findDefault().getOrNull()?.bookId
            ?: return androidx.work.Result.success()
        val now = DateTimeUtils.nowMillis()
        val start = DateTimeUtils.startOfMonth(now)
        val end = DateTimeUtils.endOfMonth(now)
        val spent = billRepository.sumByType(bookId, start, end)
            .getOrDefault(emptyMap())[BillType.EXPENSE] ?: 0L
        val budget = budgetRepository.findByBookAndMonth(bookId, DateTimeUtils.monthKey(now)).getOrNull()

        if (budget != null && budget.totalLimitFen > 0L && spent > budget.totalLimitFen) {
            showNotification(
                title = "预算已超支",
                text = "本月已支出 ${Money.format(spent)}，超出预算 ${Money.format(spent - budget.totalLimitFen)}",
                channel = Constants.CHANNEL_REMINDER,
                notificationId = 2001
            )
        } else {
            showNotification(
                title = "该记一笔啦",
                text = "打开记账本，记录今天的收支",
                channel = Constants.CHANNEL_REMINDER,
                notificationId = 2002
            )
        }
        return androidx.work.Result.success()
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
        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Android 13+ 未授予 POST_NOTIFICATIONS 时静默降级，不崩溃
        }
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
