package com.codex.pocketledger

import java.time.LocalDate
import java.time.format.DateTimeParseException

fun parseUserDate(text: String): LocalDate = try {
    LocalDate.parse(text)
} catch (_: DateTimeParseException) {
    throw IllegalArgumentException("日期无效，请按年-月-日填写")
}

/** Never display a raw platform, JSON, SQL or arithmetic exception in the UI. */
fun userFacingError(cause: Throwable, fallback: String = "操作失败，请重试"): String = when (cause) {
    is DateTimeParseException -> "日期无效，请按年-月-日填写"
    is NumberFormatException -> "请输入有效数字"
    is ArithmeticException -> "金额超出范围"
    is SecurityException -> "文件夹权限已失效，请重新选择"
    is IllegalArgumentException -> cause.message?.takeIf { message ->
        message.any { it in '\u4e00'..'\u9fff' } && '\n' !in message
    } ?: fallback
    else -> fallback
}
