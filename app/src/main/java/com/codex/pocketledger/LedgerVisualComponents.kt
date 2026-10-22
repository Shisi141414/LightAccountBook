package com.codex.pocketledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IncomeExpenseBalanceRow(expenseCents: Long, incomeCents: Long, monthly: Boolean) {
    val totals = listOf(
        (if (monthly) "本月支出" else "支出") to expenseCents,
        (if (monthly) "本月收入" else "收入") to incomeCents,
        (if (monthly) "本月结余" else "结余") to incomeCents - expenseCents,
    )
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        totals.forEachIndexed { index, (label, cents) ->
            if (index > 0) Spacer(Modifier.width(1.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .45f)))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SummaryAmount(cents)
            }
        }
    }
}

@Composable
private fun SummaryAmount(cents: Long) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = with(density) { maxWidth.toPx() }
        val candidates = summaryAmountCandidates(cents)
        val amount = candidates.firstOrNull {
            textMeasurer.measure(it, style, softWrap = false).size.width <= availableWidth
        } ?: candidates.last()
        val measuredWidth = textMeasurer.measure(amount, style, softWrap = false).size.width
        // Very large integer values still fit in a single column without an ellipsis.
        val size = (20f * (availableWidth / measuredWidth.coerceAtLeast(1))
            .coerceIn(0.05f, 1f)).sp
        Text(amount, style = style.copy(fontSize = size), maxLines = 1, softWrap = false)
    }
}

@Composable
fun CardActionDivider() {
    Spacer(Modifier.fillMaxWidth().height(1.dp)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = .45f)))
}

@Composable
fun SplitCardActions(onEdit: () -> Unit, onDelete: () -> Unit) {
    CardActionDivider()
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onEdit),
            contentAlignment = Alignment.Center) {
            Text("编辑", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(1.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .45f)))
        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDelete),
            contentAlignment = Alignment.Center) {
            Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun FullCardAction(label: String, onClick: () -> Unit) {
    CardActionDivider()
    Box(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CompactActionPair(onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.height(38.dp).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        RoundedCornerShape(10.dp)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(58.dp).fillMaxHeight().clickable(onClick = onEdit),
            contentAlignment = Alignment.Center) {
            Text("编辑", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(1.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.outline))
        Box(Modifier.width(58.dp).fillMaxHeight().clickable(onClick = onDelete),
            contentAlignment = Alignment.Center) {
            Text("删除", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun DialogOutlineAction(label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick,
        border = BorderStroke(1.dp, if (emphasized) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline)) {
        Text(label, color = if (emphasized) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface)
    }
}
