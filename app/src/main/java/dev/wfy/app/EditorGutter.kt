package dev.wfy.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Number logical source lines at their actual text baselines, including wrapped code. */
@Composable fun EditorGutter(content: String, layout: TextLayoutResult?, scroll: ScrollState, viewportHeight: Int, font: Int, caret: Int) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val starts = remember(content) { buildList { add(0); content.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }.toIntArray() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.createFromAsset(context.assets, "terminal-font.ttf"); textAlign = Paint.Align.RIGHT } }
    val fontPx = with(density) { font.sp.toPx() }
    val width = with(density) { (fontPx * .65f * starts.size.toString().length).toDp() } + 20.dp
    val height = with(density) { (layout?.size?.height ?: 1).toDp() }
    val visible by remember(layout, starts, viewportHeight, scroll) {
        derivedStateOf {
            if (layout == null || layout.lineCount == 0) emptyList()
            else {
                val first = layout.getLineForVerticalPosition((scroll.value - with(density) { 12.dp.toPx() }).coerceAtLeast(0f))
                val last = layout.getLineForVerticalPosition((scroll.value + viewportHeight).toFloat()).coerceAtMost(layout.lineCount - 1)
                (first..last).mapNotNull { visual -> starts.binarySearch(layout.getLineStart(visual)).takeIf { it >= 0 }?.let { it + 1 to visual } }
            }
        }
    }
    Canvas(Modifier.width(width).height(height).semantics { contentDescription = "Line numbers"; text = AnnotatedString(visible.joinToString("\n") { it.first.toString() }) }) {
        if (layout == null) return@Canvas
        paint.textSize = fontPx
        val active = (starts.binarySearch(caret).let { if (it >= 0) it else -it - 2 }).coerceAtLeast(0) + 1
        visible.forEach { (number, visual) ->
            paint.color = if (number == active) android.graphics.Color.rgb(218, 222, 230) else android.graphics.Color.rgb(117, 125, 138)
            drawContext.canvas.nativeCanvas.drawText(number.toString(), size.width - 10.dp.toPx(), layout.getLineBaseline(visual), paint)
        }
    }
}
