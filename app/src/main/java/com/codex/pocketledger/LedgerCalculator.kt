package com.codex.pocketledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding

@Composable
fun AmountField(value: String, label: String, allowNegative: Boolean = false,
                allowZero: Boolean = false, modifier: Modifier = Modifier,
                onCalculated: (String) -> Unit = {},
                onValueChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(value, {}, readOnly = true, singleLine = true,
            label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) AmountCalculator(value, allowNegative, allowZero, onDismiss = { open = false }) { result ->
        onValueChange(result.amount)
        result.expression?.let(onCalculated)
        open = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmountCalculator(initialAmount: String, allowNegative: Boolean, allowZero: Boolean,
                     onDismiss: () -> Unit,
                     onConfirm: (CalculatorResult) -> Unit) {
    var state by remember(initialAmount, allowNegative, allowZero) {
        mutableStateOf(CalculatorState(expression = initialAmount.replace('-', '−'),
            justEvaluated = initialAmount.isNotBlank()))
    }
    val expressionScroll = rememberScrollState()
    LaunchedEffect(state.expression) { expressionScroll.scrollTo(expressionScroll.maxValue) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("计算金额",
                    style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("(", ")").forEach { bracket ->
                        Button(onClick = { state = state.press(bracket) },
                            modifier = Modifier.width(37.dp).height(31.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface)) { Text(bracket) }
                    }
                }
            }
            Text(state.expression.ifBlank { "0" },
                modifier = Modifier.fillMaxWidth().horizontalScroll(expressionScroll),
                softWrap = false, maxLines = 1, fontSize = 16.sp)
            Text(state.error ?: state.preview?.let { "= $it" } ?: "—",
                modifier = Modifier.fillMaxWidth().height(30.dp),
                style = if (state.error == null) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.bodyMedium,
                color = if (state.error == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1)
            listOf(
                listOf("C", "×", "÷", "⌫"),
                listOf("7", "8", "9", "−"),
                listOf("4", "5", "6", "+"),
                listOf("1", "2", "3", "%"),
                listOf("0", "00", ".", "="),
            ).forEach { keys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    keys.forEach { key ->
                        val accent = key in listOf("÷", "×", "−", "+", "%")
                        Button(onClick = { state = state.press(key) },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (key == "=") MaterialTheme.colorScheme.primary
                                    else if (accent || key == "C" || key == "⌫")
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                contentColor = if (key == "=") MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface)) {
                            if (key == "⌫") BackspaceIcon() else Text(key)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = {
                    runCatching { state.finish(allowNegative, allowZero) }
                        .onSuccess(onConfirm)
                        .onFailure { state = state.copy(error = userFacingError(it, "金额有误")) }
                }, modifier = Modifier.height(36.dp)) { Text("填入金额") }
            }
        }
    }
}

@Composable
private fun BackspaceIcon() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(19.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.5f)
            lineTo(size.width * 0.37f, size.height * 0.16f)
            lineTo(size.width * 0.9f, size.height * 0.16f)
            lineTo(size.width * 0.9f, size.height * 0.84f)
            lineTo(size.width * 0.37f, size.height * 0.84f)
            close()
        }
        drawPath(path, color, style = Stroke(width = 1.6.dp.toPx()))
        drawLine(color, Offset(size.width * 0.48f, size.height * 0.38f),
            Offset(size.width * 0.72f, size.height * 0.62f), 1.6.dp.toPx())
        drawLine(color, Offset(size.width * 0.72f, size.height * 0.38f),
            Offset(size.width * 0.48f, size.height * 0.62f), 1.6.dp.toPx())
    }
}
