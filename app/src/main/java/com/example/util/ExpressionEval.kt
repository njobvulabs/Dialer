package com.example.util

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Shared expression evaluator for the calculator and the launcher's smart search.
 * The calculator builds a token list from its keypad; search parses free text.
 */
object ExpressionEval {

    private val PRECEDENCE = mapOf(
        "+" to 1, "-" to 1,
        "\u00D7" to 2, "\u00F7" to 2,
        "%" to 3
    )
    private val MATH_CONTEXT = MathContext(16, RoundingMode.HALF_UP)

    fun formatNumber(value: BigDecimal): String = try {
        value.stripTrailingZeros().toPlainString()
    } catch (e: ArithmeticException) {
        value.toPlainString()
    }

    fun evaluate(tokens: List<String>): BigDecimal? {
        val values = ArrayDeque<BigDecimal>()
        val ops = ArrayDeque<String>()
        try {
            for (t in tokens) {
                if (t in PRECEDENCE) {
                    while (ops.isNotEmpty() && ops.last() in PRECEDENCE &&
                        PRECEDENCE.getValue(ops.last()) >= PRECEDENCE.getValue(t)
                    ) {
                        if (!applyTop(values, ops)) return null
                    }
                    ops.addLast(t)
                } else {
                    values.addLast(t.toBigDecimal())
                }
            }
            while (ops.isNotEmpty()) {
                if (!applyTop(values, ops)) return null
            }
            return values.singleOrNull()
        } catch (e: Exception) {
            return null
        }
    }

    /** True when the text looks like arithmetic rather than a phone number. */
    fun looksLikeExpression(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty()) return false
        val body = s.removePrefix("+").removePrefix("-")
        return body.any { it in "+-*/%\u00D7\u00F7()" }
    }

    /** Parses and evaluates free-text arithmetic, returning a formatted result. */
    fun evaluateExpression(input: String): String? {
        val tokens = tokenize(input) ?: return null
        val value = Parser(tokens).parse() ?: return null
        return formatNumber(value)
    }

    private fun tokenize(input: String): List<String>? {
        val out = mutableListOf<String>()
        val s = input.trim()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    var dots = 0
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                        if (s[i] == '.' && ++dots > 1) return null
                        i++
                    }
                    val number = s.substring(start, i)
                    if (number == ".") return null
                    out += number
                }
                c == '+' || c == '-' || c == '%' -> {
                    out += c.toString()
                    i++
                }
                c == '*' -> { out += "\u00D7"; i++ }
                c == '/' -> { out += "\u00F7"; i++ }
                c == '\u00D7' || c == '\u00F7' -> {
                    out += c.toString()
                    i++
                }
                c == '(' || c == ')' -> {
                    out += c.toString()
                    i++
                }
                else -> return null
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun applyTop(values: ArrayDeque<BigDecimal>, ops: ArrayDeque<String>): Boolean {
        val op = ops.removeLast()
        if (op == "%") {
            if (values.isEmpty()) return false
            val v = values.removeLast()
            values.addLast(v.divide(100.toBigDecimal(), MATH_CONTEXT))
            return true
        }
        if (values.size < 2) return false
        val b = values.removeLast()
        val a = values.removeLast()
        when (op) {
            "+" -> values.addLast(a.add(b))
            "-" -> values.addLast(a.subtract(b))
            "\u00D7" -> values.addLast(a.multiply(b))
            "\u00F7" -> {
                if (b.compareTo(BigDecimal.ZERO) == 0) return false
                values.addLast(a.divide(b, MATH_CONTEXT))
            }
            else -> return false
        }
        return true
    }

    private class Parser(private val tokens: List<String>) {
        private var pos = 0

        fun parse(): BigDecimal? {
            val value = expr() ?: return null
            return if (pos == tokens.size) value else null
        }

        private fun expr(): BigDecimal? {
            var left = term() ?: return null
            while (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
                val op = tokens[pos++]
                val right = term() ?: return null
                left = if (op == "+") left.add(right) else left.subtract(right)
            }
            return left
        }

        private fun term(): BigDecimal? {
            var left = unary() ?: return null
            while (pos < tokens.size && (tokens[pos] == "\u00D7" || tokens[pos] == "\u00F7")) {
                val op = tokens[pos++]
                val right = unary() ?: return null
                left = if (op == "\u00D7") {
                    left.multiply(right)
                } else {
                    if (right.compareTo(BigDecimal.ZERO) == 0) return null
                    left.divide(right, MATH_CONTEXT)
                }
            }
            return left
        }

        private fun unary(): BigDecimal? {
            if (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
                val op = tokens[pos++]
                val value = unary() ?: return null
                return if (op == "-") value.negate() else value
            }
            var value = primary() ?: return null
            while (pos < tokens.size && tokens[pos] == "%") {
                pos++
                value = value.divide(100.toBigDecimal(), MATH_CONTEXT)
            }
            return value
        }

        private fun primary(): BigDecimal? {
            if (pos >= tokens.size) return null
            val token = tokens[pos]
            if (token == "(") {
                pos++
                val value = expr() ?: return null
                if (pos >= tokens.size || tokens[pos] != ")") return null
                pos++
                return value
            }
            if (token == ")") return null
            val value = token.toBigDecimalOrNull() ?: return null
            pos++
            return value
        }
    }
}
