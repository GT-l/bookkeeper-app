package com.azu.bookkeeper.ui.stats.component

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener

/** 趋势图展示模式。 */
enum class TrendMode(val label: String) {
    BAR("柱状"),
    LINE("折线")
}

private class IndexAxisFormatter(private val labels: List<String>) : ValueFormatter() {
    override fun getFormattedValue(value: Float): String = labels.getOrNull(value.toInt()).orEmpty()
}

/**
 * 趋势图（MPAndroidChart / AndroidView）：收入绿、支出红，点击某个时间桶下钻。
 */
@Composable
fun TrendChartView(
    labels: List<String>,
    incomeFen: List<Long>,
    expenseFen: List<Long>,
    mode: TrendMode = TrendMode.BAR,
    captureRequest: Long = 0L,
    onCaptured: (Bitmap) -> Unit = {},
    onBucketClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val lastCapture = remember { mutableLongStateOf(0L) }
    val listener = remember(onBucketClick) {
        object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null) onBucketClick(e.x.toInt().coerceAtLeast(0))
            }

            override fun onNothingSelected() = Unit
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(340.dp),
        factory = { context ->
            when (mode) {
                TrendMode.BAR -> BarChart(context).apply {
                    description.isEnabled = false
                    legend.textSize = 11f
                    setOnChartValueSelectedListener(listener)
                }
                TrendMode.LINE -> LineChart(context).apply {
                    description.isEnabled = false
                    legend.textSize = 11f
                    setOnChartValueSelectedListener(listener)
                }
            }.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { chart ->
            val formatter = IndexAxisFormatter(labels)
            when (chart) {
                is BarChart -> {
                    val income = incomeFen.mapIndexed { index, fen -> BarEntry(index.toFloat(), fen / 100f) }
                    val expense = expenseFen.mapIndexed { index, fen -> BarEntry(index.toFloat(), fen / 100f) }
                    val incomeSet = BarDataSet(income, "收入").apply { color = ChartPalette.colors[1] }
                    val expenseSet = BarDataSet(expense, "支出").apply { color = ChartPalette.colors[0] }
                    chart.data = BarData(incomeSet, expenseSet).apply { barWidth = 0.35f }
                    chart.groupBars(-0.5f, 0.8f, 0.1f)
                    chart.xAxis.apply {
                        position = XAxis.XAxisPosition.BOTTOM
                        valueFormatter = formatter
                        granularity = 1f
                        textSize = 10f
                    }
                    chart.axisLeft.valueFormatter = YuanFormatter()
                }
                is LineChart -> {
                    val income = incomeFen.mapIndexed { index, fen -> Entry(index.toFloat(), fen / 100f) }
                    val expense = expenseFen.mapIndexed { index, fen -> Entry(index.toFloat(), fen / 100f) }
                    val incomeSet = LineDataSet(income, "收入").apply {
                        color = ChartPalette.colors[1]
                        valueTextSize = 9f
                        lineWidth = 2f
                    }
                    val expenseSet = LineDataSet(expense, "支出").apply {
                        color = ChartPalette.colors[0]
                        valueTextSize = 9f
                        lineWidth = 2f
                    }
                    chart.data = LineData(incomeSet, expenseSet)
                    chart.axisLeft.valueFormatter = YuanFormatter()
                    chart.xAxis.apply {
                        position = XAxis.XAxisPosition.BOTTOM
                        valueFormatter = formatter
                        granularity = 1f
                        textSize = 10f
                    }
                }
            }
            chart.invalidate()
            if (captureRequest != 0L && captureRequest != lastCapture.longValue) {
                lastCapture.longValue = captureRequest
                onCaptured(captureChart(chart))
            }
        }
    )
}

private class YuanFormatter : ValueFormatter() {
    override fun getFormattedValue(value: Float): String = value.toLong().toString()
}
