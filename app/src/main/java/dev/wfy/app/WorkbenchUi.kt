package dev.wfy.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun WorkbenchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(4.dp),
    content: @Composable RowScope.() -> Unit
) = Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, shape = shape, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = androidx.compose.ui.graphics.Color.White), content = content)

@Composable fun WorkbenchOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(4.dp),
    content: @Composable RowScope.() -> Unit
) = OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, shape = shape, content = content)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun WorkbenchTabs(items: List<String>, active: String, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState())) {
        items.forEach { name ->
            val requester = remember { BringIntoViewRequester() }
            LaunchedEffect(active) {
                if (active == name) {
                    withFrameNanos { }
                    requester.bringIntoView()
                }
            }
            Column(Modifier.width(IntrinsicSize.Max).bringIntoViewRequester(requester).semantics { role = Role.Tab; selected = active == name }.clickable { select(name) }) {
                Text(name, modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp), color = if (active == name) MaterialTheme.colorScheme.onSurface else Muted, fontSize = 14.sp, maxLines = 1)
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (active == name) Accent else Outline))
            }
        }
    }
}
