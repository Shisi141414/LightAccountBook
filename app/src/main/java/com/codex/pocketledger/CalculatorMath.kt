package com.codex.pocketledger

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class CalculatorResult(val amount: String, val expression: String?)

/** Keep the typed expression intact; evaluate it only for the preview and final amount. */
data class CalculatorState(
    val expression: String = "",
    val justEvaluated: Boolean = false,
    val error: String? = null,
) {
    val preview: String?
        get() = runCatching { amountFromExpression(expression, true, true) }.getOrNull()

    fun press(key: String): CalculatorState {
        if (key == "C" || key == "清空") return CalculatorState()
        if (key == "⌫" || key == "退格") return copy(
            expression = expression.dropLast(1), justEvaluated = false, error = null)
        if (key == "=") return runCatching { amountFromExpression(expression, true, true) }
            .fold({ copy(justEvaluated = true, error = null) },
                { copy(error = userFacingError(it, "算式有误")) })

        val isNumberKey = key in listOf("0", "00", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".")
        if (!isNumberKey && key !in listOf("+", "−", "×", "÷", "%", "(", ")")) return this
        val base = if (justEvaluated && (isNumberKey || key == "(")) "" else expression
        val last = base.lastOrNull()
        val updated = when {
            isNumberKey && (last == ')' || last == '%') -> return copy(error = "请先选择运算符")
            key == "." -> {
                val number = base.takeLastWhile { it.isDigit() || it == '.' }
                if (number.contains('.')) return copy(error = null)
                base + if (number.isEmpty()) "0." else "."
            }
            isNumberKey -> {
                val number = base.takeLastWhile { it.isDigit() || it == '.' }
                if (number.substringAfter('.', "").length + key.length > 2 && '.' in number)
                    return copy(error = "最多输入两位小数")
                if (number.length + key.length > 16) return copy(error = "金额过大")
                when {
                    number == "0" -> base.dropLast(1) + if (key == "00") "0" else key
                    number.isEmpty() && key == "00" -> base + "0"
                    else -> base + key
                }
            }
            key == "(" -> if (last == null || last in "+−×÷(") base + key
                else return copy(error = "请先选择运算符")
            key == ")" -> if (base.count { it == '(' } > base.count { it == ')' } &&
                (last?.isDigit() == true || last == ')' || last == '%')) base + key
                else return copy(error = "请先补全括号内的算式")
            key == "%" -> if (last?.isDigit() == true || last == ')') base + key
                else return copy(error = "请先输入数字")
            key in "+−×÷" -> when {
                last == null || last == '(' -> if (key == "−") base + key
                    else return copy(error = "请先输入数字")
                last in "+−×÷" -> base.dropLast(1) + key
                last == '.' -> return copy(error = "请先输入小数位")
                else -> base + key
            }
            else -> return this
        }
        if (updated.length > 120) return copy(error = "算式过长")
        return copy(expression = updated, justEvaluated = false, error = null)
    }

    fun finish(allowNegative: Boolean, allowZero: Boolean = false): CalculatorResult {
        val amount = amountFromExpression(expression, allowNegative, allowZero)
        val meaningfulFormula = expression.any { it in "+×÷%()" } ||
            expression.drop(1).contains('−')
        return CalculatorResult(amount, expression.takeIf { meaningfulFormula })
    }
}

/** A postfix percent means division by 100: for example, 200×10% = 20. */
private class ExpressionParser(private val source: String) {
    private var pos = 0
    private val precision = MathContext(34, RoundingMode.HALF_UP)

    fun parse(): BigDecimal {
        require(source.isNotBlank()) { "请输入金额" }
        val result = sum()
        require(pos == source.length) { "算式有误" }
        return result
    }

    private fun take(char: Char): Boolean {
        if (pos >= source.length || source[pos] != char) return false
        pos++
        return true
    }

    private fun sum(): BigDecimal {
        var result = product()
        while (true) result = when {
            take('+') -> result.add(product(), precision)
            take('−') -> result.subtract(product(), precision)
            else -> return result
        }
    }

    private fun product(): BigDecimal {
        var result = signed()
        while (true) result = when {
            take('×') -> result.multiply(signed(), precision)
            take('÷') -> {
                val divisor = signed()
                require(divisor.signum() != 0) { "不能除以 0" }
                result.divide(divisor, precision)
            }
            else -> return result
        }
    }

    private fun signed(): BigDecimal = when {
        take('−') -> signed().negate()
        take('+') -> signed()
        else -> percent()
    }

    private fun percent(): BigDecimal {
        var result = atom()
        while (take('%')) result = result.movePointLeft(2)
        return result
    }

    private fun atom(): BigDecimal {
        if (take('(')) {
            val value = sum()
            require(take(')')) { "请补全括号" }
            return value
        }
        val start = pos
        while (pos < source.length && source[pos].isDigit()) pos++
        if (take('.')) while (pos < source.length && source[pos].isDigit()) pos++
        require(start < pos && source[start].isDigit()) { "算式不完整" }
        return BigDecimal(source.substring(start, pos))
    }
}

private fun amountFromExpression(source: String, allowNegative: Boolean, allowZero: Boolean): String {
    val rounded = ExpressionParser(source).parse().setScale(2, RoundingMode.HALF_UP)
    val cents = try {
        rounded.movePointRight(2).longValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("金额超出范围")
    }
    require(allowNegative || cents >= 0) { "金额不能为负数" }
    require(allowZero || cents != 0L) { "金额必须大于 0" }
    return formatCents(cents)
}

fun appendFormulaToNote(note: String, expression: String): String {
    val oldFormula = Regex("(?:^|；)算式：[0-9.()+−×÷%]+$")
    val original = note.replace(oldFormula, "").trimEnd('；')
    return if (original.isBlank()) "算式：$expression" else "$original；算式：$expression"
}
