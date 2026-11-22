package com.azu.bookkeeper.ui.stats

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.core.DateTimeUtils
import com.azu.bookkeeper.core.Result
import com.azu.bookkeeper.core.getOrDefault
import com.azu.bookkeeper.core.getOrNull
import com.azu.bookkeeper.domain.model.Bill
import com.azu.bookkeeper.domain.model.BillType
import com.azu.bookkeeper.domain.model.StatisticsBundle
import com.azu.bookkeeper.domain.repository.BillRepository
import com.azu.bookkeeper.domain.repository.BookRepository
import com.azu.bookkeeper.domain.repository.CategoryRepository
import com.azu.bookkeeper.domain.repository.SettingsRepository
import com.azu.bookkeeper.domain.repository.TagRepository
import com.azu.bookkeeper.domain.usecase.stats.GetStatisticsUseCase
import com.azu.bookkeeper.domain.usecase.stats.StatsGranularity
import com.azu.bookkeeper.ui.stats.component.TrendMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 统计时间范围。 */
enum class StatsRange(val label: String) {
    TODAY("今日"),
    WEEK("本周"),
    MONTH("本月"),
    YEAR("本年"),
    CUSTOM("自定义");

    companion object {
        val ALL: List<StatsRange> = entries.toList()
    }
}

/** 图表点击后的下钻条件。 */
data class StatsDrillDown(
    val title: String,
    val type: BillType? = null,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val tagId: Long? = null,
    val startMillis: Long = 0L,
    val endMillis: Long = Long.MAX_VALUE
)

/** 统计页状态源；转账与余额调整一律不计入收支统计。 */
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getStatistics: GetStatisticsUseCase,
    private val billRepository: BillRepository,
    private val bookRepository: BookRepository,
    private val categoryRepository: CategoryRepository,
    private val tagRepository: TagRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    data class UiState(
        val bookId: Long = 0L,
        val bookName: String = "",
        val range: StatsRange = StatsRange.MONTH,
        val granularity: StatsGranularity = StatsGranularity.DAY,
        val trendMode: TrendMode = TrendMode.BAR,
        val tab: Int = 0,
        val customStart: Long = DateTimeUtils.startOfMonth(DateTimeUtils.nowMillis()),
        val customEnd: Long = DateTimeUtils.endOfMonth(DateTimeUtils.nowMillis()),
        val bundle: StatisticsBundle = StatisticsBundle(),
        val categoryNames: Map<Long, String> = emptyMap(),
        val tagNames: Map<Long, String> = emptyMap(),
        val bills: List<Bill> = emptyList(),
        val drill: StatsDrillDown? = null,
        val drillBills: List<Bill> = emptyList(),
        val captureRequest: Long = 0L,
        val message: String? = null
    ) {
        val bounds: Pair<Long, Long>
            get() = rangeBounds(range, customStart, customEnd)

        fun withCustomRange(start: Long, end: Long): UiState = copy(
            customStart = start,
            customEnd = end,
            range = StatsRange.CUSTOM
        )
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bookId = settings.defaultBookId.first().takeIf { it > 0L }
                ?: bookRepository.findDefault().getOrNull()?.bookId ?: 0L
            val bookName = bookRepository.findById(bookId).getOrNull()?.name.orEmpty()
            _uiState.update { it.copy(bookId = bookId, bookName = bookName) }
            load()
        }
    }

    fun setRange(range: StatsRange) {
        val granularity = when (range) {
            StatsRange.YEAR -> StatsGranularity.MONTH
            StatsRange.WEEK -> StatsGranularity.DAY
            else -> StatsGranularity.DAY
        }
        _uiState.update { it.copy(range = range, granularity = granularity, drill = null, drillBills = emptyList()) }
        load()
    }

    fun setCustomRange(startMillis: Long, endMillis: Long) {
        _uiState.update { it.withCustomRange(startMillis, endMillis).copy(drill = null, drillBills = emptyList()) }
        load()
    }

    fun setGranularity(granularity: StatsGranularity) {
        _uiState.update { it.copy(granularity = granularity) }
        load()
    }

    fun setTrendMode(mode: TrendMode) = _uiState.update { it.copy(trendMode = mode) }

    fun setTab(tab: Int) = _uiState.update { it.copy(tab = tab.coerceIn(0, TAB_TITLES.lastIndex)) }

    fun requestScreenshot() = _uiState.update { it.copy(captureRequest = System.currentTimeMillis()) }

    fun saveScreenshot(bitmap: Bitmap) {
        viewModelScope.launch {
            val uri: Uri? = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "bookkeeper_stats_${System.currentTimeMillis()}.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    }
                    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { target ->
                        context.contentResolver.openOutputStream(target)?.use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                        target
                    }
                }.getOrNull()
            }
            _uiState.update { it.copy(message = if (uri != null) "图表截图已保存到相册" else "截图保存失败，请检查存储权限") }
        }
    }

    fun drillCategory(categoryId: Long, type: BillType) {
        val state = _uiState.value
        val name = state.categoryNames[categoryId].orEmpty()
        drill(StatsDrillDown("${type.label} · $name", type = type, categoryId = categoryId))
    }

    fun drillType(type: BillType) {
        drill(StatsDrillDown(type.label, type = type))
    }

    fun drillAccount(accountId: Long) {
        val name = _uiState.value.bundle.accounts.firstOrNull { it.accountId == accountId }?.name.orEmpty()
        drill(StatsDrillDown("账户 · $name", accountId = accountId))
    }

    fun drillTag(tagId: Long) {
        val name = _uiState.value.tagNames[tagId].orEmpty()
        drill(StatsDrillDown("标签 · $name", tagId = tagId))
    }

    fun drillBucket(index: Int) {
        val state = _uiState.value
        val point = state.bundle.trends.getOrNull(index) ?: return
        val granularity = state.granularity
        val end = when (granularity) {
            StatsGranularity.DAY -> DateTimeUtils.endOfDay(point.bucketStart)
            StatsGranularity.WEEK -> DateTimeUtils.startOfWeek(point.bucketStart) + 7L * 86_400_000L - 1L
            StatsGranularity.MONTH -> DateTimeUtils.endOfMonth(point.bucketStart)
        }
        drill(StatsDrillDown("时间 · ${DateTimeUtils.format(point.bucketStart, "yyyy-MM-dd")}", startMillis = point.bucketStart, endMillis = end))
    }

    fun closeDrill() = _uiState.update { it.copy(drill = null, drillBills = emptyList()) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    private fun drill(target: StatsDrillDown) {
        val state = _uiState.value
        val filtered = state.bills.filter { bill ->
            (target.type == null || bill.billType == target.type) &&
                (target.categoryId == null || bill.categoryId == target.categoryId) &&
                (target.accountId == null || bill.accountFrom == target.accountId || bill.accountTo == target.accountId) &&
                (target.tagId == null || target.tagId in bill.tagIds) &&
                bill.billTime in target.startMillis..target.endMillis
        }
        _uiState.update { it.copy(drill = target, drillBills = filtered) }
    }

    private suspend fun load() {
        val state = _uiState.value
        if (state.bookId <= 0L) return
        val (start, end) = state.bounds
        when (val result = getStatistics(state.bookId, start, end, state.granularity)) {
            is Result.Success -> _uiState.update { it.copy(bundle = result.data) }
            is Result.Error -> _uiState.update { it.copy(message = result.error.userMessage) }
            Result.Loading -> Unit
        }
        val bills = billRepository.findByTimeRange(state.bookId, start, end)
            .getOrDefault(emptyList())
            .filter { !it.isAdjustment && it.billType != BillType.TRANSFER }
        val categoryNames = (
            categoryRepository.observeByBook(state.bookId, com.azu.bookkeeper.domain.model.CategoryKind.EXPENSE).first() +
                categoryRepository.observeByBook(state.bookId, com.azu.bookkeeper.domain.model.CategoryKind.INCOME).first()
            ).associate { it.categoryId to it.name }
        val tagNames = tagRepository.observeByBook(state.bookId).first().associate { it.tagId to it.name }
        _uiState.update { it.copy(bills = bills, categoryNames = categoryNames, tagNames = tagNames) }
    }

    companion object {
        val TAB_TITLES = listOf("概览", "支出饼图", "收入饼图", "趋势图", "分类明细", "账户分析", "标签统计")
    }
}

private fun rangeBounds(range: StatsRange, customStart: Long, customEnd: Long): Pair<Long, Long> {
    val now = DateTimeUtils.nowMillis()
    return when (range) {
        StatsRange.TODAY -> DateTimeUtils.startOfDay(now) to DateTimeUtils.endOfDay(now)
        StatsRange.WEEK -> DateTimeUtils.startOfWeek(now) to DateTimeUtils.endOfDay(now)
        StatsRange.MONTH -> DateTimeUtils.startOfMonth(now) to DateTimeUtils.endOfMonth(now)
        StatsRange.YEAR -> {
            val year = DateTimeUtils.localDateTime(now).year
            val start = DateTimeUtils.parse("$year-01-01", "yyyy-MM-dd") ?: DateTimeUtils.startOfMonth(now)
            val end = DateTimeUtils.parse("$year-12-31", "yyyy-MM-dd") ?: DateTimeUtils.endOfMonth(now)
            start to DateTimeUtils.endOfDay(end)
        }
        StatsRange.CUSTOM -> customStart to customEnd
    }
}
