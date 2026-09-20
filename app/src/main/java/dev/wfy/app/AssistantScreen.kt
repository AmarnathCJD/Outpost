package dev.wfy.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

@Composable fun AssistantScreen(vm: WorkspaceModel) {
    val model = vm.assistants
    val chat = model.active ?: return
    val clipboard = LocalClipboardManager.current
    val list = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    val following by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.text) { if (following && chat.messages.isNotEmpty()) list.animateScrollToItem(chat.messages.size - 1) }
    BackHandler { model.close() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().background(Panel), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.close() }) { Icon(Icons.Outlined.ArrowBack, "Back to sessions") }
            Column(Modifier.weight(1f)) {
                Text(if (chat.provider == "codex") "Codex" else "Claude Code", fontSize = 16.sp)
                Text(chat.project + if (chat.running) " · Working" else " · Saved conversation", color = Muted, fontSize = 12.sp)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Conversation options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Copy conversation ID") }, enabled = chat.threadId.isNotEmpty(), onClick = { clipboard.setText(AnnotatedString(chat.threadId)); menu = false; vm.notify("Conversation ID copied") })
                    DropdownMenuItem(text = { Text("Open in terminal") }, enabled = chat.threadId.isNotEmpty() && !chat.running && vm.connected, onClick = { menu = false; vm.openAssistantTerminal(chat) })
                    DropdownMenuItem(text = { Text("New conversation") }, enabled = !chat.running && vm.connected, onClick = { menu = false; vm.startAssistant(chat.provider, fresh = true) })
                }
            }
        }
        HorizontalDivider(color = Accent, thickness = 2.dp)
        if (chat.threadId.isNotEmpty()) Text("${chat.threadId.take(8)}… · ${if (chat.mode == "read-only") "Read only" else "Workspace edits"}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("assistant-messages"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (chat.messages.isEmpty()) item {
                Text(if (chat.threadId.isEmpty()) "What are we working on?" else "Continue your laptop conversation", fontSize = 22.sp)
                Text(if (chat.threadId.isEmpty()) "Uses the host’s existing ${chat.provider} login and configuration. Replies and conversation IDs are saved on the host." else "Your next message resumes this exact conversation. Earlier CLI messages remain in the provider’s history.", color = Muted, modifier = Modifier.padding(top = 10.dp), fontSize = 14.sp)
            }
            items(chat.messages, key = { it.id }) { message ->
                var expanded by remember(message.id) { mutableStateOf(false) }
                val tool = message.role == "tool"
                Column(Modifier.fillMaxWidth().background(if (message.role == "user") Panel else Ink).padding(if (message.role == "user") 12.dp else 0.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (message.role) { "user" -> "You"; "assistant" -> if (chat.provider == "codex") "Codex" else "Claude"; "tool" -> "Tool activity"; else -> "Permissions" }, color = LinkBlue, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (tool) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "Details", fontSize = 12.sp) }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.ContentCopy, "Copy message", Modifier.size(16.dp), tint = Muted) }
                    }
                    if (message.role == "assistant") AssistantMessageBody(message.text)
                    else SelectionContainer { Text(if (tool && !expanded) message.text.lineSequence().first().take(180) else message.text, fontSize = if (tool) 12.sp else 15.sp, fontFamily = if (tool) CodeFont else MaterialTheme.typography.bodyMedium.fontFamily, lineHeight = 22.sp) }
                }
            }
            if (chat.running) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text(" " + chat.status.ifBlank { "Working on the host..." }, color = Muted, fontSize = 13.sp) } }
        }
        val problem = model.error ?: chat.error.takeIf { it.isNotBlank() }
        if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, maxLines = 5, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(model.draft, model::editDraft, modifier = Modifier.weight(1f).testTag("assistant-input"), placeholder = { Text("Message ${if (chat.provider == "codex") "Codex" else "Claude"}") }, maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { model.send() }))
            if (chat.running) IconButton(onClick = model::stop, enabled = vm.connected) { Icon(Icons.Outlined.Stop, "Stop reply") }
            else IconButton(onClick = model::send, enabled = vm.connected && model.draft.isNotBlank() && !model.sending) { Icon(Icons.Outlined.Send, "Send message") }
        }
    }
}

@Composable private fun AssistantMessageBody(text: String) {
    val segments = remember(text) { text.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        segments.forEachIndexed { index, segment ->
            if (index % 2 == 1) {
                val language = segment.substringBefore('\n').trim().take(30)
                val code = segment.substringAfter('\n', segment).trimEnd()
                Column(Modifier.fillMaxWidth().background(Panel).padding(12.dp)) {
                    if (language.isNotEmpty()) Text(language, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
                    SelectionContainer { Text(code, fontFamily = CodeFont, fontSize = 13.sp, lineHeight = 20.sp) }
                }
            } else if (segment.isNotBlank()) {
                val rich = remember(segment) {
                    buildAnnotatedString {
                        var offset = 0
                        Regex("\\*\\*([^*]+)\\*\\*|`([^`]+)`").findAll(segment).forEach { match ->
                            append(segment.substring(offset, match.range.first))
                            if (match.groups[1] != null) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[1]) }
                            else withStyle(SpanStyle(fontFamily = CodeFont)) { append(match.groupValues[2]) }
                            offset = match.range.last + 1
                        }
                        append(segment.substring(offset))
                    }
                }
                SelectionContainer { Text(rich, fontSize = 15.sp, lineHeight = 23.sp) }
            }
        }
    }
}
