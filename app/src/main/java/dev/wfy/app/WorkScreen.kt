package dev.wfy.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun WorkScreen(vm: WorkspaceModel) {
    var clone by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pinnedOnly by remember { mutableStateOf(false) }
    val preferences = vm.bookmarks.filter { it.server == vm.serverIdentity }.associateBy { it.project }
    val repositories = vm.projects.filter { it.optString("name").contains(query, true) && (!pinnedOnly || preferences[it.optString("name")]?.pinned == true) }
        .sortedWith(compareBy(workspaceOrder(preferences)) { it.optString("name") })
    val recent = vm.projects.firstOrNull { it.optString("name") == vm.project }
        ?: vm.projects.maxByOrNull { preferences[it.optString("name")]?.opened ?: 0L }
    val ready = vm.connected && !vm.busy
    val localDrafts = vm.drafts.filter { it.server == vm.serverIdentity }
    LazyColumn(Modifier.fillMaxSize().testTag("work-list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            PageTitle("WORKBENCH", "Your workspace")
            // Connection is a compact toolbar with a direct action, not a promotional card.
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (vm.connected) Icons.Outlined.Lan else Icons.Outlined.LinkOff, null, tint = LinkBlue, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(vm.config.hostLabel.ifBlank { vm.config.host }.ifBlank { "Pair your laptop" }, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when { vm.connecting -> "Connecting…"; vm.connected -> "${vm.config.user} · SSH connected"; else -> "SSH workspace" }, color = Muted, fontSize = 14.sp)
                }
                if (vm.connected) IconButton(onClick = { vm.settingsSection = "Connection"; vm.tab = 4 }) { Icon(Icons.Outlined.Tune, "Connection settings") }
                else WorkbenchButton(onClick = { if (vm.config.host.isBlank()) { vm.settingsSection = "Hosts"; vm.tab = 4 } else vm.connect() }, enabled = !vm.connecting) { Text(if (vm.config.host.isBlank()) "Set up" else "Connect") }
            }
            TextButton(onClick = { vm.settingsSection = "Hosts"; vm.tab = 4 }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Devices, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (vm.discovery.hosts.isEmpty()) "Pair a laptop" else "Your laptops ? ${vm.discovery.hosts.count { it.online }} online") }
            HorizontalDivider(color = Outline)
        }
        if (recent != null) item {
            val name = recent.optString("name")
            Label(if (vm.project == name) "Current workspace" else "Continue working")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(46.dp).background(if (recent.optBoolean("migration")) Warm else LinkBlue))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(recent.optString("branch").ifBlank { "No commits yet" }, color = Muted, fontFamily = CodeFont, fontSize = 14.sp)
                }
            }
            if (recent.optBoolean("migration")) Text("Migration workspace · isolated from feature work", color = Warm, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkbenchButton(onClick = { vm.selectProject(name) }, enabled = ready, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open files") }
                WorkbenchOutlinedButton(onClick = { vm.gitSection = "Changes"; vm.selectProject(name, 2) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("Changes") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.gitSection = "Pull requests"; vm.selectProject(name, 2) }, enabled = ready) { Text("Pull requests") }
                TextButton(onClick = { vm.selectProject(name, 3) }, enabled = ready) { Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sessions") }
            }
        }
        if (localDrafts.isNotEmpty()) item {
            Label("Unsaved drafts · ${localDrafts.size}")
            localDrafts.forEach { draft ->
                ActionRow(Icons.Outlined.EditNote, draft.file.path.substringAfterLast('/'), "${draft.project}${draft.file.branch.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()} · unsaved", { vm.restoreDraft(draft) }, enabled = !vm.busy)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label("Repositories · ${vm.projects.size}") }
                IconButton(onClick = vm::refreshNow, enabled = ready) { Icon(Icons.Outlined.Refresh, "Refresh workspaces") }
                IconButton(onClick = { clone = true }, enabled = ready) { Icon(Icons.Outlined.Add, "Clone repository", tint = LinkBlue) }
            }
            if (vm.projects.size > 3 || query.isNotEmpty()) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Find a workspace") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !pinnedOnly, onClick = { pinnedOnly = false }, label = { Text("All") })
                FilterChip(selected = pinnedOnly, onClick = { pinnedOnly = true }, label = { Text("Pinned") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp)) })
            }
        }
        if (repositories.isEmpty()) item {
            EmptyState(Icons.Outlined.FolderOpen, if (!vm.connected) "Ready when you connect" else if (pinnedOnly) "Pin your everyday projects" else "No matching repositories", if (!vm.connected) "Your projects and running sessions will appear here." else if (pinnedOnly) "Tap the pin beside a workspace to keep it at the top." else "Clone a GitHub repository, or clear your filter.")
        }
        items(repositories, key = { it.optString("name") }) { repository ->
            val name = repository.optString("name")
            val pinned = preferences[name]?.pinned == true
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).testTag("workspace-$name").clickable(enabled = ready) { vm.selectProject(name) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (repository.optBoolean("migration")) Icons.Outlined.Storage else Icons.Outlined.FolderOpen, null, tint = if (repository.optBoolean("migration")) Warm else LinkBlue, modifier = Modifier.size(22.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(name, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(repository.optString("branch"), fontSize = 14.sp, color = Muted, fontFamily = CodeFont, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { vm.togglePin(name) }) { Icon(Icons.Outlined.PushPin, if (pinned) "Unpin $name" else "Pin $name", tint = if (pinned) LinkBlue else Muted) }
            }
            HorizontalDivider(color = Outline)
        }
        item {
            Label("Tools")
            ActionRow(Icons.Outlined.Language, "Running services", "Forward a port and open your API or web app", { vm.showPorts = true }, vm.connected)
            if (vm.project.isNotBlank()) {
                ActionRow(Icons.Outlined.ManageSearch, "Find in workspace", "Open a file by name or search its contents", { vm.showSearch = true }, vm.connected)
                ActionRow(Icons.Outlined.RestoreFromTrash, "Recovery bin", "Restore deleted files and folders", { vm.showRecovery = true }, vm.connected)
            }
            ActionRow(Icons.Outlined.Source, "Clone repository", "Add a GitHub project to this server", { clone = true }, ready)
            ActionRow(Icons.Outlined.Tune, "Workspace settings", "Connection, Git, assistants and environment", { vm.tab = 4 })
        }
    }
    if (clone) FormDialog("Clone from GitHub", listOf("Workspace name" to "", "GitHub repository URL" to "https://github.com/"), "Clone", { clone = false }) { values -> vm.clone(values[0], values[1]); clone = false }
}
