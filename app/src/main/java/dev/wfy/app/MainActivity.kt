package dev.wfy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject

val Ink = Color(0xFF1F1F1F)
val Panel = Color(0xFF181818)
val Accent = Color(0xFF0078D4)
val Muted = Color(0xFF9D9D9D)
val Warm = Color(0xFFE2C08D)
val Outline = Color(0xFF2B2B2B)
val LinkBlue = Color(0xFF4DAAFC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: WorkspaceModel = viewModel()
            val density = androidx.compose.ui.platform.LocalDensity.current
            // Reduce text without shrinking dp-based touch targets or overriding Android accessibility settings.
            val scale = (vm.config.uiScale.toFloatOrNull()?.coerceIn(.9f, 1.3f) ?: 1f) * .94f
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale * scale)) {
                OutpostTheme { OutpostApp(vm) }
            }
        }
    }
}
@Composable fun OutpostTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = LinkBlue, onPrimary = Ink, primaryContainer = Color(0xFF173D5A), onPrimaryContainer = Color.White, secondary = LinkBlue, secondaryContainer = Color(0xFF173D5A), onSecondaryContainer = Color.White, tertiary = Warm, background = Ink, surface = Ink, surfaceContainer = Panel, surfaceVariant = Panel, surfaceTint = LinkBlue, onSurface = Color(0xFFCCCCCC), onSurfaceVariant = Muted, outline = Color(0xFF3C3C3C)), shapes = Shapes(extraSmall = RoundedCornerShape(3.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(10.dp), extraLarge = RoundedCornerShape(12.dp)), typography = OutpostTypography, content = content)
}
@Composable fun OutpostApp(vm: WorkspaceModel = viewModel()) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_START) vm.discovery.refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm.message) {
        val message = vm.message
        if (message != null && (message.startsWith("Saved on server") || message.startsWith("Connected securely") || message.endsWith("saved securely"))) {
            kotlinx.coroutines.delay(4000)
            if (vm.message == message) vm.message = null
        }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect { view.keepScreenOn = vm.config.keepScreenOn }
    DisposableEffect(Unit) { onDispose { view.keepScreenOn = false } }
    Scaffold(containerColor = Ink, bottomBar = {
        if (vm.file == null && vm.activeSession == null && vm.assistants.active == null) Column {
        HorizontalDivider(color = Outline)
        NavigationBar(Modifier.height(66.dp), containerColor = Panel, tonalElevation = 0.dp, windowInsets = WindowInsets(0,0,0,0)) {
            listOf("Work" to Icons.Outlined.Dashboard, "Files" to Icons.Outlined.FolderOpen, "Git" to Icons.Outlined.AccountTree, "Sessions" to Icons.Outlined.Terminal, "Settings" to Icons.Outlined.Tune).forEachIndexed { index, (label, icon) ->
                NavigationBarItem(modifier = Modifier.testTag("nav-$index"), selected = vm.tab == index, onClick = { vm.tab = index }, icon = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.width(22.dp).height(2.dp).background(if (vm.tab == index) LinkBlue else Panel)); Spacer(Modifier.height(6.dp)); Icon(icon, null, Modifier.size(22.dp)) } }, label = { Text(label, fontSize = 12.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Panel, selectedIconColor = Color.White, selectedTextColor = Color.White))
            }
        }
        Row(Modifier.fillMaxWidth().background(Accent).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Code, null, Modifier.size(14.dp), tint = Color.White)
            Text(if (vm.connected) " SSH: ${vm.config.host}" else " Not connected", Modifier.weight(1f), color = Color.White, fontSize = 12.sp, maxLines = 1)
            if (vm.project.isNotEmpty()) { Icon(Icons.Outlined.AccountTree, null, Modifier.size(12.dp), tint = Color.White); Text(" " + vm.git.optString("branch"), color = Color.White, fontSize = 12.sp, maxLines = 1) }
        }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.busy || vm.connecting) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent) else Spacer(Modifier.height(2.dp))
            vm.message?.let { message -> MessageStrip(message) { vm.message = null } }
            when {
                vm.assistants.active != null -> AssistantScreen(vm)
                vm.activeSession != null -> TerminalScreen(vm, vm.activeSession!!)
                vm.file != null -> EditorScreen(vm)
                else -> {
                    AppHeader(vm)
                    when (vm.tab) { 0 -> WorkScreen(vm); 1 -> FilesScreen(vm); 2 -> GitScreen(vm); 3 -> SessionsScreen(vm); 4 -> SettingsScreen(vm) }
                }
            }
        }
    }
    if (vm.showSearch) RepositorySearch(vm) { vm.showSearch = false }
    if (vm.showRecovery) RecoveryBrowser(vm) { vm.showRecovery = false }
    if (vm.showPorts) PortsDialog(vm) { vm.showPorts = false }
    vm.hostPrompt?.let { prompt ->
        AlertDialog(onDismissRequest = { prompt.result.complete(false) }, icon = { Icon(Icons.Outlined.Security, null) }, title = { Text(if (prompt.changed) "Server identity changed" else "Verify your server") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (prompt.changed) "The SSH key differs from the saved key. Verify this change on your server before replacing it." else "Compare this fingerprint with your server’s SSH host key before trusting this connection.")
                Text(prompt.fingerprint, fontFamily = CodeFont, fontSize = 15.sp)
                Text("On the server: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", fontFamily = CodeFont, fontSize = 14.sp, color = Muted)
            }
        }, confirmButton = { TextButton(onClick = { prompt.result.complete(true) }) { Text("Trust this key") } }, dismissButton = { TextButton(onClick = { prompt.result.complete(false) }) { Text("Cancel") } })
    }
}
@Composable fun AppHeader(vm: WorkspaceModel) {
    var commands by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_outpost_mark), null, Modifier.size(24.dp), tint = Color.Unspecified)
        Text("Outpost", Modifier.padding(start = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(14.dp))
        Surface(Modifier.weight(1f).testTag("command-palette").clickable { commands = true }, shape = RoundedCornerShape(5.dp), color = Ink, border = BorderStroke(1.dp, Outline)) { Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Search, "Open command palette", Modifier.size(14.dp), tint = Muted); Text(vm.project.ifEmpty { "Commands" }, Modifier.padding(start = 6.dp), fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        TextButton(onClick = { vm.settingsSection = "Connection"; vm.tab = 4 }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Icon(if (vm.connected) Icons.Outlined.Link else Icons.Outlined.LinkOff, if (vm.connected) "SSH connected" else "Connection settings", Modifier.size(19.dp), tint = if (vm.connected) LinkBlue else Muted)
        }
    }
    HorizontalDivider(color = Outline)
    if (commands) CommandPalette(vm) { commands = false }
}
private data class PaletteCommand(val name: String, val enabled: Boolean = true, val action: () -> Unit)
@Composable private fun CommandPalette(vm: WorkspaceModel, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val workspaceReady = vm.connected && vm.project.isNotEmpty()
    val commands = listOf(
        PaletteCommand("Explorer: Open workspaces") { vm.tab = 0 },
        PaletteCommand("Explorer: Browse files", workspaceReady) { vm.tab = 1 },
        PaletteCommand("Explorer: Quick open / search", workspaceReady) { vm.showSearch = true },
        PaletteCommand("Explorer: Recovery bin", workspaceReady) { vm.showRecovery = true },
        PaletteCommand("Source Control: Commit history", workspaceReady) { vm.gitSection = "History"; vm.tab = 2 },
        PaletteCommand("Source Control: Saved stashes", workspaceReady) { vm.gitSection = "Stashes"; vm.tab = 2 },
        PaletteCommand("Ports: Open running service", vm.connected) { vm.showPorts = true },
        PaletteCommand("Source Control: View changes", workspaceReady) { vm.gitSection = "Changes"; vm.tab = 2 },
        PaletteCommand("Source Control: Pull requests", workspaceReady) { vm.gitSection = "Pull requests"; vm.tab = 2 },
        PaletteCommand("Terminal: New session", vm.connected) { vm.startSession("shell", "Terminal") },
        PaletteCommand("Sessions: Resume a session") { vm.tab = 3 },
        PaletteCommand("AI: Start Codex", workspaceReady) { vm.startSession("codex", "Codex") },
        PaletteCommand("AI: Start Claude Code", workspaceReady) { vm.startSession("claude", "Claude Code") },
        PaletteCommand("Hosts: Discover or pair a laptop") { vm.settingsSection = "Hosts"; vm.tab = 4 },
        PaletteCommand("Preferences: Connection") { vm.settingsSection = "Connection"; vm.tab = 4 },
        PaletteCommand("Preferences: Git") { vm.settingsSection = "Git"; vm.tab = 4 },
        PaletteCommand("Preferences: Environment") { vm.settingsSection = "Environment"; vm.tab = 4 },
        PaletteCommand("Preferences: Saved commands") { vm.settingsSection = "Commands"; vm.tab = 4 },
        PaletteCommand("Preferences: Editor") { vm.settingsSection = "Editor"; vm.tab = 4 }
    ).filter { it.name.contains(query.trim().removePrefix(">").trim(), ignoreCase = true) }
    AlertDialog(onDismissRequest = dismiss, containerColor = Panel, title = { Text("Command palette", fontSize = 17.sp) }, text = {
        Column {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Type a command…", fontSize = 15.sp) }, leadingIcon = { Text(">", color = LinkBlue, fontFamily = CodeFont) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("command-query"))
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                commands.forEach { command ->
                    TextButton(onClick = { command.action(); dismiss() }, enabled = command.enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(3.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                        Text(command.name, Modifier.weight(1f), fontSize = 14.sp, color = if (command.enabled) Color(0xFFCCCCCC) else Muted.copy(alpha = .5f))
                    }
                }
                if (commands.isEmpty()) Text("No matching commands", color = Muted, modifier = Modifier.padding(12.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
@Composable fun PageTitle(eyebrow: String, title: String, subtitle: String = "", action: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp)) {
        if (eyebrow.isNotEmpty()) Text(eyebrow, color = Muted, fontSize = 13.sp, fontFamily = CodeFont)
        Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f).padding(top = 5.dp), fontSize = 20.sp, letterSpacing = (-.3).sp, fontWeight = FontWeight.Medium); action?.invoke() }
        if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 7.dp), lineHeight = 20.sp)
    }
}
@Composable fun Label(text: String) { Text(text.uppercase(), color = Muted, fontSize = 12.sp, fontFamily = CodeFont, letterSpacing = 1.sp, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)) }
@Composable private fun MessageStrip(message: String, dismiss: () -> Unit) {
    var expanded by remember(message) { mutableStateOf(false) }
    Surface(color = Color(0xFF252526)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = LinkBlue, modifier = Modifier.padding(start = 14.dp).size(16.dp))
            Text(message, Modifier.weight(1f).clickable { expanded = true }.padding(horizontal = 10.dp, vertical = 10.dp), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Dismiss notification", Modifier.size(18.dp), tint = Muted) }
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("Output") }, text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(message, Modifier.verticalScroll(rememberScrollState()), fontFamily = CodeFont, fontSize = 14.sp) } }, confirmButton = { TextButton(onClick = { expanded = false; dismiss() }) { Text("Dismiss") } })
}
@Composable fun CardBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) { Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), color = Panel, border = BorderStroke(1.dp, Outline)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) } }
@Composable fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) Accent else Muted, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(title, fontSize = 16.sp); Text(subtitle, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)) }
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}
@Composable fun EmptyState(icon: ImageVector, title: String, description: String, button: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium); Text(description, color = Muted, fontSize = 15.sp, lineHeight = 21.sp); if (button != null) WorkbenchOutlinedButton(onClick = onClick, shape = RoundedCornerShape(6.dp)) { Text(button) } }
}
@Composable private fun WelcomeAction(icon: ImageVector, title: String, hint: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = click).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = LinkBlue, modifier = Modifier.size(20.dp))
        Text(title, Modifier.weight(1f).padding(start = 12.dp), color = LinkBlue, fontSize = 16.sp)
        Text(hint, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
}
@Composable fun FormDialog(title: String, fields: List<Pair<String, String>>, confirm: String, dismiss: () -> Unit, submit: (List<String>) -> Unit) {
    val values = remember { fields.map { mutableStateOf(it.second) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { fields.forEachIndexed { i, field -> OutlinedTextField(values[i].value, { values[i].value = it }, label = { Text(field.first) }, modifier = Modifier.fillMaxWidth(), minLines = if (field.first.contains("body", true)) 3 else 1) } } }, confirmButton = { TextButton(onClick = { submit(values.map { it.value }) }, enabled = values.firstOrNull()?.value?.isNotBlank() != false) { Text(confirm) } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable fun ConfirmDialog(title: String, description: String, confirm: String, dismiss: () -> Unit, submit: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(description) }, confirmButton = { TextButton(onClick = submit) { Text(confirm) } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }
@Composable fun RequireWorkspace(vm: WorkspaceModel, content: @Composable () -> Unit) {
    if (vm.project.isEmpty()) Column(Modifier.padding(22.dp)) { PageTitle("", "No workspace open"); EmptyState(Icons.Outlined.FolderOpen, "Select a repository", "Open a workspace to browse its files and Git changes.", "Open workspaces", { vm.tab = 0 }) } else content()
}
