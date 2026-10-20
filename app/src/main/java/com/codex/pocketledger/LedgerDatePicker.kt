package com.codex.pocketledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

@Composable
fun CalendarDateField(value: String, label: String, modifier: Modifier = Modifier,
                      allowClear: Boolean = false, compactDisplay: Boolean = false,
                      onValueChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val display = runCatching { LocalDate.parse(value) }.getOrNull()?.let {
        if (compactDisplay) "%04d/%02d/%02d".format(it.year, it.monthValue, it.dayOfMonth)
        else "${it.year} 年 ${it.monthValue} 月 ${it.dayOfMonth} 日"
    }.orEmpty()
    CalendarInputField(display, label, modifier) { open = true }
    if (open) CalendarPickerDialog(
        initial = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.now(),
        onDismiss = { open = false },
        onConfirm = { onValueChange(it.toString()); open = false },
        onClear = if (allowClear) {{ onValueChange(""); open = false }} else null,
    )
}

@Composable
fun CalendarMonthField(value: String, label: String, modifier: Modifier = Modifier,
                       onValueChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val month = runCatching { YearMonth.parse(value) }.getOrNull()
    CalendarInputField(month?.let { "${it.year} 年 ${it.monthValue} 月" }.orEmpty(), label,
        modifier) { open = true }
    if (open) CalendarPickerDialog(
        initial = month?.atDay(1) ?: LocalDate.now(),
        onDismiss = { open = false },
        onConfirm = { onValueChange(YearMonth.from(it).toString()); open = false },
        monthOnly = true,
    )
}

@Composable
private fun CalendarInputField(value: String, label: String, modifier: Modifier,
                               onOpen: () -> Unit) {
    Box(modifier) {
        OutlinedTextField(value, onValueChange = {}, label = { Text(label) },
            modifier = Modifier.fillMaxWidth(), readOnly = true, singleLine = true)
        Box(Modifier.matchParentSize().clickable(onClick = onOpen))
    }
}

@Composable
private fun CalendarPickerDialog(initial: LocalDate, onDismiss: () -> Unit,
                                 onConfirm: (LocalDate) -> Unit, onClear: (() -> Unit)? = null,
                                 monthOnly: Boolean = false) {
    var selected by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(YearMonth.from(initial)) }
    var mode by remember { mutableStateOf(if (monthOnly) "month" else "day") }
    var yearPage by remember { mutableIntStateOf(Math.floorDiv(initial.year, 12) * 12) }

    fun showMonth(month: YearMonth) {
        visible = month
        selected = month.atDay(selected.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
    }

    fun navigate(direction: Long) {
        when (mode) {
            "year" -> yearPage += 12 * direction.toInt()
            "month" -> showMonth(visible.plusYears(direction))
            else -> showMonth(visible.plusMonths(direction))
        }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (monthOnly) "选择月份" else "选择日期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(32.dp).clickable { navigate(-1) }, contentAlignment = Alignment.Center) {
                        Text("‹", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.headlineMedium)
                    }
                    CalendarHeaderButton("${visible.year} 年", Modifier.weight(1f)) {
                        yearPage = Math.floorDiv(visible.year, 12) * 12
                        mode = "year"
                    }
                    CalendarHeaderButton("${visible.monthValue} 月", Modifier.weight(1f)) {
                        mode = "month"
                    }
                    Box(Modifier.size(32.dp).clickable { navigate(1) }, contentAlignment = Alignment.Center) {
                        Text("›", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.headlineMedium)
                    }
                }
                when (mode) {
                    "year" -> CalendarYearGrid(yearPage, visible.year) { year ->
                        showMonth(YearMonth.of(year, visible.monthValue))
                        mode = if (monthOnly) "month" else "day"
                    }
                    "month" -> CalendarMonthGrid(visible.monthValue) { month ->
                        showMonth(YearMonth.of(visible.year, month))
                        mode = if (monthOnly) "month" else "day"
                    }
                    else -> CalendarDayGrid(visible, selected) { day ->
                        selected = day
                        visible = YearMonth.from(day)
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val today = LocalDate.now()
                    selected = today
                    visible = YearMonth.from(today)
                    mode = if (monthOnly) "month" else "day"
                }) { Text("今天") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onClear != null) TextButton(onClick = onClear) { Text("清除") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onConfirm(selected) }) { Text("确定") }
                }
            }
        },
    )
}

@Composable
private fun CalendarHeaderButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(42.dp)
        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CalendarDayGrid(month: YearMonth, selected: LocalDate,
                            onSelect: (LocalDate) -> Unit) {
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val rows = maxOf(5, (month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7)
    Column {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val day = first.plusDays((row * 7 + column).toLong())
                    val chosen = day == selected
                    val today = day == LocalDate.now()
                    val textColor = when {
                        chosen -> MaterialTheme.colorScheme.onPrimary
                        YearMonth.from(day) != month -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Box(Modifier.weight(1f).height(42.dp).clickable { onSelect(day) },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(36.dp)
                            .background(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                CircleShape)
                            .then(if (today && !chosen) Modifier.border(BorderStroke(1.dp,
                                MaterialTheme.colorScheme.primary), CircleShape) else Modifier),
                            contentAlignment = Alignment.Center) {
                            Text(day.dayOfMonth.toString(), color = textColor,
                                fontWeight = if (chosen || today) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarYearGrid(firstYear: Int, selectedYear: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { column ->
                    val year = firstYear + row * 3 + column
                    CalendarGridChoice(year.toString(), year == selectedYear,
                        Modifier.weight(1f)) { onSelect(year) }
                }
            }
        }
    }
}

@Composable
private fun CalendarMonthGrid(selectedMonth: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { column ->
                    val month = row * 3 + column + 1
                    CalendarGridChoice("${month} 月", month == selectedMonth,
                        Modifier.weight(1f)) { onSelect(month) }
                }
            }
        }
    }
}

@Composable
private fun CalendarGridChoice(label: String, selected: Boolean, modifier: Modifier,
                               onClick: () -> Unit) {
    Box(modifier.height(48.dp)
        .background(if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
