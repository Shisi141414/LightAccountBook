package com.codex.pocketledger

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val chartColors = listOf(
    Color(0xFF6B50A1), Color(0xFF2E7D85), Color(0xFFDE8249), Color(0xFFAD567A),
    Color(0xFF608B4D), Color(0xFF4779B3), Color(0xFFC09A32), Color(0xFF865C9A),
)

@Composable
fun StatisticsScreen(data: LedgerSnapshot, modifier: Modifier) {
    var monthText by remember { mutableStateOf(YearMonth.now().toString()) }
    val month = runCatching { YearMonth.parse(monthText) }.getOrNull()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CalendarMonthField(monthText, "月份") { monthText = it }
        if (month == null || month.isAfter(YearMonth.now())) {
            Text("请输入有效且不晚于本月的月份", color = MaterialTheme.colorScheme.error)
        } else {
            val end = if (month == YearMonth.now()) LocalDate.now() else month.atEndOfMonth()
            val expenses = categorySlices(data, month, "expense")
            val income = categorySlices(data, month, "income")
            val accountBalances = data.accounts.map { it.name to data.balanceAt(it.id, end) }
            PieChartCard("${month.monthValue} 月支出类目", expenses)
            PieChartCard("${month.monthValue} 月收入类目", income)
            PieChartCard("账户余额", accountBalances.filter { it.second > 0 })
            val negativeAccounts = accountBalances.filter { it.second < 0 }
            if (negativeAccounts.isNotEmpty()) Text("负余额单列：" + negativeAccounts.joinToString("，") {
                "${it.first} ¥ ${formatCents(it.second)}"
            }, style = MaterialTheme.typography.bodySmall)
            BalanceLineCard(data, month.atDay(1), end)
        }
    }
}

private fun categorySlices(data: LedgerSnapshot, month: YearMonth, kind: String): List<Pair<String, Long>> =
    data.entries.filter { it.kind == kind && YearMonth.from(LocalDate.parse(it.date)) == month }
        .groupBy { entry ->
            val categoryId = data.effectiveCategoryId(entry.categoryId, kind)
            data.categoryParent(categoryId) ?: categoryId
        }.map { (id, rows) -> data.categoryName(id) to rows.sumOf { it.amountCents } }
        .sortedByDescending { it.second }

@Composable
private fun PieChartCard(title: String, slices: List<Pair<String, Long>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (slices.isEmpty()) {
                Text("暂无数据")
            } else {
                val total = slices.sumOf { it.second }
                Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                    val diameter = min(size.width, size.height) * 0.92f
                    val corner = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                    var startAngle = -90f
                    slices.forEachIndexed { index, slice ->
                        val sweep = slice.second.toFloat() / total * 360f
                        drawArc(chartColors[index % chartColors.size], startAngle, sweep, true,
                            topLeft = corner, size = Size(diameter, diameter))
                        startAngle += sweep
                    }
                }
                slices.forEachIndexed { index, (name, cents) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(14.dp).background(chartColors[index % chartColors.size]))
                        Text("$name  ¥ ${formatCents(cents)}  ·  ${(cents.toDouble() / total * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun balanceAxisLabel(cents: Double): String {
    val yuan = cents / 100.0
    return if (abs(yuan) >= 10_000.0) "¥" + String.format(Locale.CHINA, "%.1f万", yuan / 10_000.0)
    else "¥" + BigDecimal.valueOf(cents).movePointLeft(2).stripTrailingZeros().toPlainString()
}

@Composable
private fun BalanceLineCard(data: LedgerSnapshot, defaultStart: LocalDate, defaultEnd: LocalDate) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    var startText by remember(defaultStart) { mutableStateOf(defaultStart.toString()) }
    var endText by remember(defaultEnd) { mutableStateOf(defaultEnd.toString()) }
    var selectedIndex by remember(startText, endText) { mutableIntStateOf(-1) }
    val range = remember(startText, endText) { runCatching {
        balanceChartDates(parseUserDate(startText), parseUserDate(endText))
    } }
    val dates = range.getOrNull()
    val values = remember(data, dates) { dates?.map(data::totalBalanceAt).orEmpty() }
    val axis = remember(values) { balanceAxis(values) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("总余额变化（每日）", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarDateField(startText, "起始日期", Modifier.weight(1f), compactDisplay = true) {
                    startText = it
                }
                CalendarDateField(endText, "结束日期", Modifier.weight(1f), compactDisplay = true) {
                    endText = it
                }
            }
            if (dates == null) {
                Text(userFacingError(range.exceptionOrNull() ?: IllegalArgumentException(), "日期范围无效"),
                    color = MaterialTheme.colorScheme.error)
            } else {
                Text("最高 ¥ ${formatCents(values.max())}  ·  最低 ¥ ${formatCents(values.min())}",
                    style = MaterialTheme.typography.bodySmall)
                Canvas(Modifier.fillMaxWidth().height(220.dp).pointerInput(dates) {
                    detectTapGestures { tap ->
                        val left = 80.dp.toPx()
                        val right = size.width - 12.dp.toPx()
                        if (tap.x in left..right) {
                            selectedIndex = if (dates.size == 1) 0 else
                                ((tap.x - left) / (right - left) * (dates.size - 1))
                                    .roundToInt().coerceIn(0, dates.lastIndex)
                        }
                    }
                }) {
                    val left = 80.dp.toPx()
                    val right = size.width - 12.dp.toPx()
                    val top = 12.dp.toPx()
                    val bottom = size.height - 30.dp.toPx()
                    val y = { cents: Double ->
                        bottom - (bottom - top) * ((cents - axis.lowerCents) /
                            (axis.upperCents - axis.lowerCents)).toFloat()
                    }
                    val x = { index: Int ->
                        if (dates.size == 1) (left + right) / 2f else
                            left + (right - left) * index / dates.lastIndex
                    }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = labelColor.toArgb()
                        textSize = 11.sp.toPx()
                    }
                    axis.ticksCents.forEach { tick ->
                        val at = y(tick)
                        drawLine(gridColor, Offset(left, at), Offset(right, at), 1.dp.toPx())
                        drawIntoCanvas {
                            paint.textAlign = Paint.Align.RIGHT
                            it.nativeCanvas.drawText(balanceAxisLabel(tick), left - 7.dp.toPx(),
                                at + 4.dp.toPx(), paint)
                        }
                    }
                    drawLine(gridColor, Offset(left, top), Offset(left, bottom), 1.dp.toPx())
                    balanceXTickIndices(dates.size, (right - left) / density).forEach { index ->
                        val at = x(index)
                        drawLine(gridColor, Offset(at, bottom), Offset(at, bottom + 4.dp.toPx()), 1.dp.toPx())
                        val label = if (dates.first().month == dates.last().month &&
                            dates.first().year == dates.last().year) "${dates[index].dayOfMonth}日"
                            else "${dates[index].monthValue}/${dates[index].dayOfMonth}"
                        drawIntoCanvas {
                            paint.textAlign = Paint.Align.CENTER
                            it.nativeCanvas.drawText(label, at, size.height - 4.dp.toPx(), paint)
                        }
                    }
                    val points = values.mapIndexed { index, value -> Offset(x(index), y(value.toDouble())) }
                    points.zipWithNext().forEach { (first, second) ->
                        drawLine(lineColor, first, second, 2.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                    points.forEach { drawCircle(lineColor, 2.5.dp.toPx(), it) }
                    if (selectedIndex in points.indices) {
                        val selected = points[selectedIndex]
                        drawLine(lineColor.copy(alpha = 0.6f), Offset(selected.x, top),
                            Offset(selected.x, bottom), 1.dp.toPx())
                        drawCircle(lineColor, 6.dp.toPx(), selected)
                        drawCircle(surfaceColor, 2.5.dp.toPx(), selected)
                    }
                }
                Text(if (selectedIndex in dates.indices)
                    "${dates[selectedIndex]}  ·  ¥ ${formatCents(values[selectedIndex])}"
                    else "点击折线查看当天日期与金额",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
