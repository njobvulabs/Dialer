package com.example.ui.screens.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.util.ExpressionEval
import java.math.BigDecimal

private val OPERATORS = setOf("+", "-", "\u00D7", "\u00F7")
private val PRECEDENCE = mapOf("+" to 1, "-" to 1, "\u00D7" to 2, "\u00F7" to 2, "%" to 3)

data class CalcHistoryItem(val expression: String, val result: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(viewModel: MainViewModel) {
    var tokens by remember { mutableStateOf<List<String>>(emptyList()) }
    var isError by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var historyList by remember { mutableStateOf<List<CalcHistoryItem>>(emptyList()) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    fun resetOnError() {
        if (isError) {
            isError = false
            tokens = emptyList()
        }
    }

    fun triggerHaptic() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun isNumberToken(t: String) = t.toBigDecimalOrNull() != null

    fun pressDigit(d: String) {
        triggerHaptic()
        resetOnError()
        val last = tokens.lastOrNull()
        when {
            tokens.size == 1 && last == "-" -> tokens = listOf("-$d")
            last != null && isNumberToken(last) -> tokens = tokens.dropLast(1) + (last + d)
            else -> tokens = tokens + d
        }
    }

    fun pressDot() {
        triggerHaptic()
        resetOnError()
        val last = tokens.lastOrNull()
        if (last != null && isNumberToken(last)) {
            if (!last.contains(".")) {
                tokens = tokens.dropLast(1) + (last + ".")
            }
        } else {
            tokens = tokens + "0."
        }
    }

    fun pressOperator(op: String) {
        triggerHaptic()
        resetOnError()
        val last = tokens.lastOrNull()
        when {
            tokens.isEmpty() -> if (op == "-") tokens = listOf(op)
            last == "-" && tokens.size == 1 && op != "-" -> {}
            last in OPERATORS -> tokens = tokens.dropLast(1) + op
            else -> tokens = tokens + op
        }
    }

    fun pressPercent() {
        triggerHaptic()
        resetOnError()
        val last = tokens.lastOrNull()
        if (last != null && isNumberToken(last)) {
            tokens = tokens + "%"
        }
    }

    fun toggleSign() {
        triggerHaptic()
        resetOnError()
        val last = tokens.lastOrNull()
        if (last != null && isNumberToken(last)) {
            tokens = if (last.startsWith("-")) {
                tokens.dropLast(1) + last.drop(1)
            } else {
                tokens.dropLast(1) + ("-" + last)
            }
        }
    }

    fun backspace() {
        triggerHaptic()
        if (isError) {
            isError = false
            tokens = emptyList()
            return
        }
        if (tokens.isNotEmpty()) {
            val last = tokens.last()
            if (last.length > 1 && isNumberToken(last)) {
                tokens = tokens.dropLast(1) + last.dropLast(1)
            } else {
                tokens = tokens.dropLast(1)
            }
        }
    }

    fun clear() {
        triggerHaptic()
        tokens = emptyList()
        isError = false
        lastResult = ""
    }

    fun equals() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        resetOnError()
        if (tokens.isEmpty()) return
        val exprString = tokens.joinToString(" ")
        val result = runCatching { ExpressionEval.evaluate(tokens) }.getOrNull()
        if (result == null) {
            isError = true
            lastResult = ""
            tokens = emptyList()
        } else {
            lastResult = ExpressionEval.formatNumber(result)
            historyList = listOf(CalcHistoryItem(exprString, lastResult)) + historyList
            tokens = listOf(lastResult)
        }
    }

    fun copyToClipboard(text: String) {
        if (text.isBlank() || text == "0" || text == "Error") return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Calculator Result", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied $text to clipboard", Toast.LENGTH_SHORT).show()
    }

    val displayText = if (isError) "Error" else if (tokens.isEmpty()) "0" else tokens.joinToString(" ")

    val livePreview = remember(tokens, isError) {
        if (isError) "" else {
            val last = tokens.lastOrNull()
            if (last == null || last in OPERATORS || last == "%") ""
            else runCatching { ExpressionEval.evaluate(tokens)?.let { ExpressionEval.formatNumber(it) } }.getOrNull() ?: ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        FloatingTopBar(useGlass = useGlassmorphism) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showHistory) "History" else "Calculator",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = {
                    triggerHaptic()
                    showHistory = !showHistory
                }) {
                    Icon(
                        imageVector = if (showHistory) AppIcons.Calculate else AppIcons.History,
                        contentDescription = if (showHistory) "Calculator" else "History",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Display & History Container
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clickable(enabled = !showHistory) { copyToClipboard(if (lastResult.isNotEmpty()) lastResult else displayText) }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (showHistory) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "History",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (historyList.isNotEmpty()) {
                                TextButton(onClick = { showClearHistoryConfirm = true }) {
                                    Text("Clear", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        if (historyList.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No recent calculations", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(historyList) { item ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                tokens = listOf(item.result)
                                                showHistory = false
                                            }
                                            .padding(vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = item.expression,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "= ${item.result}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = if (lastResult.isNotEmpty() && tokens.size <= 1 && tokens.firstOrNull() == lastResult) "= $lastResult" else livePreview,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        val buttons = listOf(
            listOf(
                CalcButtonKey("AC", CalcButtonType.UTILITY) { clear() },
                CalcButtonKey("\u232B", CalcButtonType.UTILITY) { backspace() },
                CalcButtonKey("%", CalcButtonType.UTILITY) { pressPercent() },
                CalcButtonKey("\u00F7", CalcButtonType.OPERATOR) { pressOperator("\u00F7") }
            ),
            listOf(
                CalcButtonKey("7", CalcButtonType.NUMBER) { pressDigit("7") },
                CalcButtonKey("8", CalcButtonType.NUMBER) { pressDigit("8") },
                CalcButtonKey("9", CalcButtonType.NUMBER) { pressDigit("9") },
                CalcButtonKey("\u00D7", CalcButtonType.OPERATOR) { pressOperator("\u00D7") }
            ),
            listOf(
                CalcButtonKey("4", CalcButtonType.NUMBER) { pressDigit("4") },
                CalcButtonKey("5", CalcButtonType.NUMBER) { pressDigit("5") },
                CalcButtonKey("6", CalcButtonType.NUMBER) { pressDigit("6") },
                CalcButtonKey("-", CalcButtonType.OPERATOR) { pressOperator("-") }
            ),
            listOf(
                CalcButtonKey("1", CalcButtonType.NUMBER) { pressDigit("1") },
                CalcButtonKey("2", CalcButtonType.NUMBER) { pressDigit("2") },
                CalcButtonKey("3", CalcButtonType.NUMBER) { pressDigit("3") },
                CalcButtonKey("+", CalcButtonType.OPERATOR) { pressOperator("+") }
            ),
            listOf(
                CalcButtonKey("\u00B1", CalcButtonType.UTILITY) { toggleSign() },
                CalcButtonKey("0", CalcButtonType.NUMBER) { pressDigit("0") },
                CalcButtonKey(".", CalcButtonType.NUMBER) { pressDot() },
                CalcButtonKey("=", CalcButtonType.EQUALS) { equals() }
            )
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            buttons.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { key ->
                        CalculatorButton(
                            key = key,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("Clear history?") },
            text = { Text("Remove all recent calculations?") },
            confirmButton = {
                TextButton(onClick = {
                    historyList = emptyList()
                    showClearHistoryConfirm = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

private enum class CalcButtonType { NUMBER, OPERATOR, UTILITY, EQUALS }

private class CalcButtonKey(
    val label: String,
    val type: CalcButtonType,
    val onClick: () -> Unit
)

@Composable
private fun CalculatorButton(
    key: CalcButtonKey,
    modifier: Modifier = Modifier
) {
    val container = when (key.type) {
        CalcButtonType.NUMBER -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        CalcButtonType.OPERATOR -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        CalcButtonType.UTILITY -> MaterialTheme.colorScheme.secondaryContainer
        CalcButtonType.EQUALS -> MaterialTheme.colorScheme.primary
    }
    val content = when (key.type) {
        CalcButtonType.NUMBER -> MaterialTheme.colorScheme.onSurface
        CalcButtonType.OPERATOR, CalcButtonType.EQUALS -> MaterialTheme.colorScheme.onPrimary
        CalcButtonType.UTILITY -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = key.onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = key.label,
            color = content,
            fontSize = if (key.label == "AC" || key.label == "\u232B" || key.label == "\u00B1") 18.sp else 24.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
