package com.azu.bookkeeper.ui.stats.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener

/** MPAndroidChart 饼图经 AndroidView 包装；点击扇区回调该扇区在入参中的下标。 */
@Composable
fun PieChartView(
    slices: List<Pair<String, Float>>,
    captureRequest: Long = 0L,
    onCaptured: (Bitmap) -> Unit = {},
    onSliceClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val lastCapture = remember { mutableLongStateOf(0L) }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(340.dp),
        factory = { context ->
            PieChart(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                description.isEnabled = false
                setUsePercentValues(true)
                setExtraOffsets(10f, 10f, 10f, 10f)
                legend.textSize = 11f
                setEntryLabelTextSize(11f)
                setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        val index = (e as? PieEntry)?.data as? Int ?: return
                        onSliceClick(index)
                    }

                    override fun onNothingSelected() = Unit
                })
            }
        },
        update = { chart ->
            val entries = slices.mapIndexed { index, slice ->
                PieEntry(slice.second, slice.first).apply { data = index }
            }
            val dataSet = PieDataSet(entries, "")
            dataSet.sliceSpace = 2f
            dataSet.valueTextSize = 11f
            dataSet.colors = if (entries.isEmpty()) {
                listOf(android.graphics.Color.LTGRAY)
            } else {
                ChartPalette.colors.take(entries.size)
            }
            chart.data = PieData(dataSet).apply {
                setValueFormatter(PercentFormatter(chart))
            }
            chart.invalidate()
            if (captureRequest != 0L && captureRequest != lastCapture.longValue) {
                lastCapture.longValue = captureRequest
                onCaptured(captureChart(chart))
            }
        }
    )
}

internal fun captureChart(view: android.view.View): Bitmap {
    val bitmap = Bitmap.createBitmap(
        view.width.coerceAtLeast(1),
        view.height.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    view.draw(canvas)
    return bitmap
}

/** 固定配色：支出红、收入绿优先，其余为可区分的辅助色。 */
object ChartPalette {
    val colors: List<Int> = listOf(
        android.graphics.Color.parseColor("#F44336"),
        android.graphics.Color.parseColor("#4CAF50"),
        android.graphics.Color.parseColor("#2962FF"),
        android.graphics.Color.parseColor("#FF9800"),
        android.graphics.Color.parseColor("#9C27B0"),
        android.graphics.Color.parseColor("#00BCD4"),
        android.graphics.Color.parseColor("#795548"),
        android.graphics.Color.parseColor("#607D8B"),
        android.graphics.Color.parseColor("#CDDC39"),
        android.graphics.Color.parseColor("#E91E63")
    )
}
