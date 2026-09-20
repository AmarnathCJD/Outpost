package dev.wfy.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun SessionsScreen(vm: WorkspaceModel) {
    var custom by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Pair<String, String>?>(null) }
    var forget by remember { mutableStateOf<TerminalSession?>(null) }
    var saveCommand by remember { mutableStateOf(false) }
    var newChat by remember { mutableStateOf(false) }
    var removeChat by remember { mutableStateOf<AssistantChat?>(null) }
    var chatError by remember { mutableStateOf<String?>(null) }
    var refreshChats by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.project, vm.connected, refreshChats) {
        if (vm.connected) try { vm.assistants.refresh(vm.project, checkProviders = true); chatError = null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { chatError = e.message }
    }
    val serverSessions = vm.sessions.filter { it.server == vm.serverIdentity }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageTitle(vm.project.ifEmpty { "Server" }, "Sessions", "Sessions keep running when you disconnect.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI conversations", Modifier.weight(1f), fontSize = 18.sp)
            IconButton(onClick = { refreshChats++ }, enabled = vm.connected) { Icon(Icons.Outlined.Refresh, "Refresh assistants") }
            TextButton(onClick = { newChat = true }, enabled = vm.connected && vm.project.isNotEmpty()) { Text("New chat") }
        }
        if (chatError != null) Text(chatError!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        vm.assistants.providers.forEach { provider ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(if (provider.optString("id") == "codex") "Codex" else "Claude Code", fontSize = 14.sp)
                    Text(provider.optString("status"), fontSize = 12.sp, color = Muted)
                }
                if (provider.optBoolean("installed") && !provider.optBoolean("ready")) TextButton(onClick = { vm.startSession(provider.getString("id") + "-login", provider.getString("id") + " login") }, enabled = vm.connected) { Text("Sign in") }
            }
        }
        vm.assistants.chats.forEach { chat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ActionRow(Icons.Outlined.ChatBubbleOutline, chat.title, (if (chat.running) "Working · " else "") + if (chat.threadId.isEmpty()) "New conversation" else "Resume ${chat.threadId.take(8)}…", { vm.openAssistant(chat.id) }, vm.connected) }
                IconButton(onClick = { removeChat = chat }, enabled = !chat.running && vm.connected) { Icon(Icons.Outlined.DeleteOutline, "Remove conversation", Modifier.size(18.dp), tint = Muted) }
            }
        }
        Label("Running sessions · ${serverSessions.size}")
        if (serverSessions.isEmpty()) EmptyState(Icons.Outlined.Terminal, "No saved sessions", "Start a terminal or coding session. It will appear here for reconnection.")
        serverSessions.reversed().forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ActionRow(if (s.title.contains("Codex") || s.title.contains("Claude")) Icons.Outlined.Code else Icons.Outlined.Terminal, s.title, s.project.ifEmpty { "Server" } + " · " + s.host, { vm.resumeSession(s) }, vm.connected) }
                IconButton(onClick = { forget = s }) { Icon(Icons.Outlined.StopCircle, "End session", tint = Muted, modifier = Modifier.size(18.dp)) }
            }
        }
        Label("Start a session")
        HorizontalDivider(color = Panel)
        ActionRow(Icons.Outlined.Code, "Codex", "Continue your last chat in this workspace", { vm.startSession("codex", "Codex") }, vm.connected && vm.project.isNotEmpty())
        HorizontalDivider(color = Panel)
        ActionRow(Icons.Outlined.ChatBubbleOutline, "Claude Code", "Continue your last chat in this workspace", { vm.startSession("claude", "Claude Code") }, vm.connected && vm.project.isNotEmpty())
        HorizontalDivider(color = Panel)
        if (vm.project.isEmpty()) Text("Open a project to start a coding assistant.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Label("Run on your server")
        ActionRow(Icons.Outlined.Terminal, "New terminal", "Host shell · full keyboard controls", { vm.startSession("shell", "Terminal") }, vm.connected)
        if (vm.project.isNotEmpty()) {
            listOf("Go mod tidy" to "go mod tidy", "Run service" to "go run .", "Build" to "go build ./...", "Test" to "go test ./...", "Race test" to "go test -race ./...", "Format Go" to "gofmt -w .").chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { item -> WorkbenchOutlinedButton(onClick = { task = item }, enabled = vm.connected, modifier = Modifier.weight(1f)) { Text(item.first, fontSize = 14.sp) } } }
            }
        }
        ActionRow(Icons.Outlined.PlayArrow, "Custom command", "Migrations, seeders, logs, SQL tools, anything", { custom = true }, vm.connected)
        val saved = vm.serverSettings.optJSONObject("tasks")
        if (saved != null && saved.length() > 0) {
            Label("Saved commands")
            saved.keys().asSequence().sorted().forEach { name -> ActionRow(Icons.Outlined.PlayArrow, name, saved.getString(name), { task = name to saved.getString(name) }, vm.connected) }
        }
        TextButton(onClick = { saveCommand = true }, enabled = vm.connected) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("Save a command") }
        Spacer(Modifier.height(24.dp))
    }
    if (custom) FormDialog("Run a command", listOf("Command" to ""), "Run", { custom = false }) { vm.startSession("task", it[0].take(40), it[0]); custom = false }
    task?.let { t -> ConfirmDialog(t.first, "Run `${t.second}` in ${vm.project}? Output opens in a persistent terminal.", "Run", { task = null }) { vm.startSession("task", t.first, t.second); task = null } }
    forget?.let { s -> ConfirmDialog("End ${s.title}?", "This stops the server session and any processes running inside it. Saved files are kept.", "End session", { forget = null }) { vm.endSession(s); forget = null } }
    if (saveCommand) FormDialog("Save a run command", listOf("Name" to "", "Command" to ""), "Save", { saveCommand = false }) { vm.saveTask(it[0], it[1]); saveCommand = false }
    removeChat?.let { chat -> ConfirmDialog("Remove this chat?", "Removes the Outpost history. The original CLI conversation remains on your laptop.", "Remove", { removeChat = null }) { vm.action { vm.assistants.remove(chat) }; removeChat = null } }
    if (newChat) {
        var provider by remember { mutableStateOf("codex") }
        var id by remember { mutableStateOf("") }
        var readOnly by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { newChat = false }, title = { Text("New conversation") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("codex", "claude").forEach { name -> FilterChip(selected = provider == name, onClick = { provider = name }, label = { Text(if (name == "codex") "Codex" else "Claude") }) } }
                OutlinedTextField(id, { id = it }, label = { Text("Existing conversation ID (optional)") }, supportingText = { Text("Paste an ID from the laptop to continue the same context.") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(readOnly, { readOnly = it }); Text("Read only / plan") }
            }
        }, confirmButton = { TextButton(onClick = { vm.startAssistant(provider, fresh = true, threadId = id, mode = if (readOnly) "read-only" else "workspace-write"); newChat = false }) { Text(if (id.isBlank()) "Create chat" else "Resume by ID") } }, dismissButton = { TextButton(onClick = { newChat = false }) { Text("Cancel") } })
    }
}
