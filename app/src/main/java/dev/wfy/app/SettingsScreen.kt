package dev.wfy.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

@Composable fun ConfigField(label: String, value: String, change: (String) -> Unit, secret: Boolean = false, lines: Int = 1, help: String? = null) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = lines == 1, minLines = lines, maxLines = maxOf(lines, 6), modifier = Modifier.fillMaxWidth(), visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None, trailingIcon = if (secret) { { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (show) "Hide $label" else "Show $label") } } } else null, supportingText = help?.let { { Text(it) } })
}
@Composable fun SettingsScreen(vm: WorkspaceModel) {
    var section by vm::settingsSection
    var newCommand by remember { mutableStateOf(false) }
    var editCommand by remember { mutableStateOf<Pair<String, String>?>(null) }
    var c by remember(vm.config) { mutableStateOf(vm.config) }
    val s = vm.serverSettings
    var gitName by remember(s) { mutableStateOf(s.optString("gitName")) }
    var gitEmail by remember(s) { mutableStateOf(s.optString("gitEmail")) }
    var prBase by remember(s) { mutableStateOf(s.optString("prBase", "main")) }
    var migration by remember(s) { mutableStateOf(s.optString("migrationBranch", "migration")) }
    var protected by remember(s) { mutableStateOf(s.optJSONArray("protected")?.let { a -> (0 until a.length()).map { a.getString(it) }.distinct().joinToString(", ") } ?: "main, master") }
    var env by remember(s) { mutableStateOf(s.optJSONObject("env")?.let { e -> e.keys().asSequence().sorted().joinToString("\n") { "$it=${e.getString(it)}" } } ?: "") }
    val context = LocalContext.current
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) { val count = stream.read(chunk); if (count < 0) break; require(buffer.size() + count <= 65536) { "Key file is too large" }; buffer.write(chunk, 0, count) }
            c = c.copy(key = buffer.toString("UTF-8"), useKey = true)
        } }.onFailure { vm.notify(it.message ?: "Could not import key") }
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp)) {
        PageTitle("", "Settings")
        WorkbenchTabs(listOf("Hosts", "Connection", "Git", "AI", "Environment", "Commands", "Editor"), section) { section = it }
        key(section) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (section == "Hosts") HostsSettings(vm)
        if (section == "Connection") {
        WorkbenchOutlinedButton(onClick = { section = "Hosts" }, modifier = Modifier.fillMaxWidth()) { Text("Discover and pair a laptop") }
        if (c.pairedHostId.isNotEmpty()) {
            Text("Paired with ${c.hostLabel}. Connection details are filled from your saved pairing.", color = Muted, fontSize = 14.sp)
            TextButton(onClick = { c = c.copy(pairedHostId = "", pairedPublicKey = "", hostLabel = "", relayAccess = "", relayUrl = "") }) { Text("Switch to manual setup") }
        }
        Label("SSH connection")
        ConfigField("Server hostname or IP", c.host, { c = c.copy(host = it) })
        ConfigField("Relay URL (optional)", c.relayUrl, { c = c.copy(relayUrl = it) }, help = "Paste the wss:// phone URL from the desktop VPS relay settings. Leave empty for direct SSH.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Box(Modifier.weight(2f)) { ConfigField("SSH username", c.user, { c = c.copy(user = it) }) }; Box(Modifier.weight(1f)) { ConfigField("SSH port", c.port, { c = c.copy(port = it) }) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { FilterChip(selected = c.useKey, onClick = { c = c.copy(useKey = true) }, label = { Text("Private key") }); FilterChip(selected = !c.useKey, onClick = { c = c.copy(useKey = false) }, label = { Text("Password") }) }
        if (c.useKey) {
            WorkbenchOutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (c.key.isEmpty()) "Import private key" else "Replace imported key") }
            ConfigField("SSH private key", c.key, { c = c.copy(key = it) }, secret = true, lines = 3, help = "OpenSSH or PEM. Stored encrypted on this phone.")
            ConfigField("Key passphrase (optional)", c.passphrase, { c = c.copy(passphrase = it) }, secret = true)
        } else ConfigField("SSH password", c.password, { c = c.copy(password = it) }, secret = true)
        ConfigField("Backend port on server", c.backendPort, { c = c.copy(backendPort = it) }, help = "Default 8787. Forwarded through SSH to server localhost.")
        ConfigField("Backend host from SSH server", c.backendHost, { c = c.copy(backendHost = it) }, help = "Usually 127.0.0.1. Advanced: an address reachable from the SSH server.")
        ConfigField("Outpost server token", c.token, { c = c.copy(token = it) }, secret = true, help = "Printed by deploy/install.sh after installation.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkbenchOutlinedButton(onClick = { vm.saveConfig(c) }, enabled = !vm.busy && !vm.connecting, modifier = Modifier.weight(1f)) { Text("Save") }
            WorkbenchButton(onClick = { if (vm.connected) vm.disconnect() else vm.saveConfig(c, connectAfter = true) }, enabled = !vm.connecting && !vm.busy, modifier = Modifier.weight(1f)) { Text(if (vm.connected) "Disconnect" else "Connect") }
        }
        }
        if (section == "Git") {
        Label("GitHub & identity")
        CardBlock {
            ActionRow(Icons.Outlined.Key, "Connect GitHub", "Sign in using a browser device code", { vm.startSession("github-login", "GitHub login") }, vm.connected)
            Text("Authentication lives on your server. HTTPS remotes use the GitHub CLI credential helper. For SSH Git remotes, configure a separate Git key in the server terminal.", color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
        }
        if (vm.connected) {
            ConfigField("Git author name", gitName, { gitName = it })
            ConfigField("Git author email", gitEmail, { gitEmail = it })
            ConfigField("Feature PR target", prBase, { prBase = it })
            ConfigField("Migration branch", migration, { migration = it })
            ConfigField("Protected branches (comma separated)", protected, { protected = it }, help = "main and master are always protected in the app.")
        }
        }
        if (section == "AI") {
        Label("Coding assistants")
        CardBlock {
            ActionRow(Icons.Outlined.Code, "Connect Codex", "ChatGPT subscription · device-code login", { vm.startSession("codex-login", "Codex login") }, vm.connected)
            HorizontalDivider(color = Ink)
            ActionRow(Icons.Outlined.ChatBubbleOutline, "Connect Claude", "Sign in when your account is ready", { vm.startSession("claude-login", "Claude login") }, vm.connected)
            Text("Enable device-code login in your ChatGPT security settings. API keys can be added in the Environment section.", color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
        }
        WorkbenchOutlinedButton(onClick = { section = "Environment" }, modifier = Modifier.fillMaxWidth()) { Text("Configure API keys & environment") }
        }
        if (section == "Environment") {
        Label("Workspace environment")
        if (vm.connected) {
            EnvironmentEditor(env) { env = it }
            Text("Changes apply to new terminals, AI sessions and Git commands after saving. Existing sessions keep their environment.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
            Text("Common variables: ANTHROPIC_API_KEY, OPENAI_API_KEY, GOPROXY, GONOSUMDB, SQLSERVER_HOST, SQLSERVER_DATABASE.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        }
        if (section == "Git" || section == "Environment") {
        if (vm.connected) {
            WorkbenchButton(onClick = {
                runCatching {
                    val vars = JSONObject()
                    env.lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.forEach { line -> require(line.contains('=')) { "Environment entries must use NAME=value" }; vars.put(line.substringBefore('=').trim(), line.substringAfter('=')) }
                    vm.saveServerSettings(obj("gitName" to gitName, "gitEmail" to gitEmail, "prBase" to prBase, "migrationBranch" to migration, "protected" to JSONArray(protected.split(',').map { it.trim() }.filter { it.isNotEmpty() }), "env" to vars, "tasks" to (s.optJSONObject("tasks") ?: JSONObject())))
                }.onFailure { vm.notify(it.message ?: "Check environment format") }
            }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(if (section == "Git") "Save Git settings" else "Save environment") }
        } else Text("Connect to edit Git identity, branch rules, and server environment variables.", color = Muted, fontSize = 15.sp)
        }
        if (section == "Editor") {
        Label("Interface text size")
        Column {
            listOf("Compact" to "0.9", "Standard" to "1.0", "Comfortable" to "1.15", "Large" to "1.3").forEach { (label, scale) ->
                Row(Modifier.fillMaxWidth().clickable { c = c.copy(uiScale = scale) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = c.uiScale == scale, onClick = { c = c.copy(uiScale = scale) })
                    Text(label, fontSize = 16.sp)
                }
            }
        }
        Label("Editor & connection")
        ConfigField("Font size (10–24)", c.fontSize, { c = c.copy(fontSize = it) })
        ConfigField("Sync interval in seconds (3–60)", c.syncSeconds, { c = c.copy(syncSeconds = it) }, help = "Refreshes files and Git status. Terminal output streams live.")
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Keep screen awake", Modifier.weight(1f)); Switch(c.keepScreenOn, { c = c.copy(keepScreenOn = it) }) }
        WorkbenchOutlinedButton(onClick = { vm.saveConfig(c) }, modifier = Modifier.fillMaxWidth()) { Text("Save preferences") }
        Label("Outpost · ${BuildConfig.VERSION_NAME}")
        Text("Personal remote development workspace", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (section == "Commands") {
            Label("Run configurations")
            Text("Save build, migration, seeder and test commands. Run them from Sessions in the active workspace.", color = Muted, fontSize = 15.sp, lineHeight = 20.sp)
            val commands = vm.serverSettings.optJSONObject("tasks") ?: JSONObject()
            commands.keys().asSequence().sorted().forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ActionRow(Icons.Outlined.Terminal, name, commands.getString(name), { editCommand = name to commands.getString(name) }) }
                    IconButton(onClick = { vm.deleteTask(name) }) { Icon(Icons.Outlined.DeleteOutline, "Delete saved command") }
                }
            }
            WorkbenchOutlinedButton(onClick = { newCommand = true }, enabled = vm.connected) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("Add command") }
        }
        Spacer(Modifier.height(24.dp))
        } }
    }
    if (newCommand) FormDialog("Save a run command", listOf("Name" to "", "Command" to ""), "Save", { newCommand = false }) { vm.saveTask(it[0], it[1]); newCommand = false }
    editCommand?.let { command -> FormDialog(command.first, listOf("Command" to command.second), "Save", { editCommand = null }) { vm.saveTask(command.first, it[0]); editCommand = null } }
}

@Composable private fun EnvironmentEditor(value: String, change: (String) -> Unit) {
    var raw by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var removeIndex by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Variables", Modifier.weight(1f), fontSize = 16.sp)
        TextButton(onClick = { raw = !raw }) { Text(if (raw) "List view" else "Edit as text") }
    }
    if (raw) ConfigField("Environment variables", value, change, secret = true, lines = 5, help = "One NAME=value per line. Values may contain =.") else {
        val lines = value.lines()
        lines.forEachIndexed { index, line ->
            if (line.isNotBlank() && !line.trimStart().startsWith("#")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { editIndex = index }.padding(vertical = 12.dp)) {
                        Text(line.substringBefore('='), color = LinkBlue, fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text(if (line.contains('=')) "Value hidden · tap to edit" else "Invalid entry · tap to fix", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { removeIndex = index }) { Icon(Icons.Outlined.DeleteOutline, "Remove ${line.substringBefore('=')}", tint = Muted) }
                }
                HorizontalDivider(color = Outline)
            }
        }
        if (value.isBlank()) Text("No custom variables yet.", color = Muted, fontSize = 14.sp)
        WorkbenchOutlinedButton(onClick = { editIndex = -1 }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add variable") }
    }
    editIndex?.let { index ->
        val existing = value.lines().getOrNull(index).orEmpty()
        var name by remember(index) { mutableStateOf(existing.substringBefore('=')) }
        var secret by remember(index) { mutableStateOf(if (existing.contains('=')) existing.substringAfter('=') else "") }
        var error by remember(index) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editIndex = null }, title = { Text(if (index < 0) "Add variable" else "Edit variable") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConfigField("Name", name, { name = it })
                ConfigField("Value", secret, { secret = it }, secret = true)
                error?.let { Text(it, color = Warm, fontSize = 13.sp) }
            }
        }, confirmButton = { TextButton(onClick = {
            val key = name.trim()
            val lines = value.lines().toMutableList()
            if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) error = "Use letters, numbers and underscores; start with a letter or underscore."
            else if (lines.withIndex().any { it.index != index && it.value.substringBefore('=').trim() == key }) error = "A variable with this name already exists."
            else {
                if (index < 0) lines.add("$key=$secret") else lines[index] = "$key=$secret"
                change(lines.filter { it.isNotBlank() }.joinToString("\n")); editIndex = null
            }
        }) { Text("Apply") } }, dismissButton = { TextButton(onClick = { editIndex = null }) { Text("Cancel") } })
    }
    removeIndex?.let { index -> ConfirmDialog("Remove variable?", "This removal takes effect after you save the environment.", "Remove", { removeIndex = null }) { change(value.lines().filterIndexed { i, _ -> i != index }.joinToString("\n")); removeIndex = null } }
}
