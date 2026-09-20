package dev.wfy.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

fun editorIndent(text: String, path: String): String {
    val leading = text.lineSequence().take(300).map { it.takeWhile { c -> c == ' ' || c == '\t' } }.filter { it.isNotEmpty() }.toList()
    if (leading.any { it.startsWith('\t') } || path.endsWith(".go")) return "\t"
    return if (leading.any { it.length % 4 == 2 } || path.substringAfterLast('.') in setOf("json", "yml", "yaml", "js", "ts", "tsx")) "  " else "    "
}
fun editorInsert(value: TextFieldValue, text: String, caret: Int = text.length): TextFieldValue {
    val start = value.selection.min
    return TextFieldValue(value.text.replaceRange(start, value.selection.max, text), TextRange(start + caret.coerceIn(0, text.length)))
}
fun editorPair(value: TextFieldValue, left: String, right: String): TextFieldValue {
    val start = value.selection.min
    val selected = value.text.substring(start, value.selection.max)
    val next = value.text.replaceRange(start, value.selection.max, left + selected + right)
    return TextFieldValue(next, TextRange(start + left.length, start + left.length + selected.length))
}
fun editorIndentLines(value: TextFieldValue, unit: String, remove: Boolean): TextFieldValue {
    if (value.selection.collapsed && !remove) return editorInsert(value, unit)
    val text = value.text
    val first = text.lastIndexOf('\n', (value.selection.min - 1).coerceAtLeast(-1)) + 1
    val lastSelected = if (!value.selection.collapsed && value.selection.max > 0 && text.getOrNull(value.selection.max - 1) == '\n') value.selection.max - 1 else value.selection.max
    val last = text.indexOf('\n', lastSelected).let { if (it < 0) text.length else it }
    data class Edit(val at: Int, val removed: Int, val inserted: String)
    val edits = mutableListOf<Edit>()
    var position = first
    while (position <= last) {
        val count = if (!remove) 0 else if (text.getOrNull(position) == '\t') 1 else (0 until (if (unit == "\t") 4 else unit.length)).takeWhile { text.getOrNull(position + it) == ' ' }.size
        edits += Edit(position, count, if (remove) "" else unit)
        val newline = text.indexOf('\n', position)
        if (newline < 0 || newline >= last) break
        position = newline + 1
    }
    val updated = buildString {
        var copied = 0
        for (edit in edits) { append(text, copied, edit.at); append(edit.inserted); copied = edit.at + edit.removed }
        append(text, copied, text.length)
    }
    fun moved(offset: Int): Int = offset + edits.sumOf { edit ->
        if (offset < edit.at) 0 else edit.inserted.length - minOf(edit.removed, offset - edit.at)
    }
    return TextFieldValue(updated, TextRange(moved(value.selection.start), moved(value.selection.end)))
}
/** Transform only a single completed keyboard edit; preserve IME composition and paste. */
fun editorKeyboardEdit(before: TextFieldValue, after: TextFieldValue, unit: String, smart: Boolean): TextFieldValue {
    if (!smart || after.composition != null) return after
    val start = before.selection.min
    val end = before.selection.max
    val insertedLength = after.text.length - (before.text.length - (end - start))
    if (insertedLength == 1 && after.text == before.text.replaceRange(start, end, after.text.substring(start, start + 1))) {
        val char = after.text[start]
        if (char == '\n') {
            val lineStart = before.text.lastIndexOf('\n', start - 1) + 1
            val base = before.text.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
            val left = before.text.getOrNull(start - 1)
            val close = mapOf('{' to '}', '[' to ']', '(' to ')')[left]
            val extra = if (close != null) unit else ""
            val suffix = if (close != null && before.text.getOrNull(end) == close) "\n$base" else ""
            return editorInsert(before, "\n$base$extra$suffix", 1 + base.length + extra.length)
        }
        val pairs = mapOf('{' to '}', '[' to ']', '(' to ')', '"' to '"', '\'' to '\'', '`' to '`')
        if (before.selection.collapsed && char in pairs.values && before.text.getOrNull(start) == char) return before.copy(selection = TextRange(start + 1), composition = null)
        val close = pairs[char]
        if (close != null && (before.selection.collapsed.not() || before.text.getOrNull(start)?.let { it.isWhitespace() || it in ")]},;" } != false)) {
            if (char !in "\"'`" || before.text.getOrNull(start - 1)?.let { it.isLetterOrDigit() || it == '\\' } != true) return editorPair(before, char.toString(), close.toString())
        }
    }
    if (before.selection.collapsed && start > 0 && after.text == before.text.removeRange(start - 1, start)) {
        val right = mapOf('{' to '}', '[' to ']', '(' to ')', '"' to '"', '\'' to '\'', '`' to '`')[before.text[start - 1]]
        if (right != null && before.text.getOrNull(start) == right) return TextFieldValue(before.text.removeRange(start - 1, start + 1), TextRange(start - 1))
    }
    return after
}
fun editorMove(value: TextFieldValue, direction: Int, select: Boolean): TextFieldValue {
    val cursor = value.selection.end
    val next = if (!select && !value.selection.collapsed) { if (direction < 0) value.selection.min else value.selection.max }
        else if ((direction < 0 && cursor > 0) || (direction > 0 && cursor < value.text.length)) Character.offsetByCodePoints(value.text, cursor, direction) else cursor
    return value.copy(selection = if (select) TextRange(value.selection.start, next) else TextRange(next), composition = null)
}
