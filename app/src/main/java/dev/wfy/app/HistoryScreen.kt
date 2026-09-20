package dev.wfy.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable fun CommitHistoryScreen(vm: WorkspaceModel) {
    var path by remember(vm.historyPath) { mutableStateOf(vm.historyPath) }
    LaunchedEffect(vm.project) { vm.loadHistory() }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(path, { path = it }, label = { Text("File path (optional)") }, singleLine = true, trailingIcon = { IconButton(onClick = { vm.historyPath = path; vm.loadHistory() }, enabled = !vm.reviewLoading) { Icon(Icons.Outlined.Search, "Filter history by file path") } }, modifier = Modifier.fillMaxWidth().padding(16.dp))
        ReviewError(vm) { vm.loadHistory() }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
            if (vm.history.isEmpty() && !vm.reviewLoading && vm.reviewError == null) item { EmptyState(Icons.Outlined.Commit, "No commits found", "History follows this workspace's current branch. Clear the file filter to see every commit.") }
            items(vm.history, key = { it.getString("id") }) { commit ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !vm.reviewLoading) { vm.inspectChange(commit, false) }.padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Commit, null, tint = LinkBlue, modifier = Modifier.padding(top = 2.dp).size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(commit.optString("title"), fontSize = 17.sp)
                        Text("${commit.optString("short")} · ${commit.optString("author")}", color = Muted, fontSize = 14.sp)
                        Text(commit.optString("date").replace('T', ' '), color = Muted, fontSize = 13.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = Outline)
            }
            if (vm.historyMore) item { TextButton(onClick = { vm.loadHistory(more = true) }, enabled = !vm.reviewLoading, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("Load earlier commits") } }
        }
    }
}

@Composable fun StashesScreen(vm: WorkspaceModel) {
    LaunchedEffect(vm.project) { vm.loadStashes() }
    Column(Modifier.fillMaxSize()) {
        Text("Inspect saved work before applying it. Applying keeps the stash available for recovery.", fontSize = 15.sp, color = Muted, modifier = Modifier.padding(18.dp))
        ReviewError(vm, vm::loadStashes)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp)) {
            if (vm.stashes.isEmpty() && !vm.reviewLoading && vm.reviewError == null) item { EmptyState(Icons.Outlined.Inventory2, "No saved stashes", "Use Save stash in Changes when you need to put work aside before switching branches.") }
            items(vm.stashes, key = { it.getString("id") + it.optString("ref") }) { stash ->
                Column(Modifier.fillMaxWidth().clickable(enabled = !vm.reviewLoading) { vm.inspectChange(stash, true) }.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stash.optString("title"), fontSize = 17.sp)
                    Text(stash.optString("ref"), color = LinkBlue, fontFamily = CodeFont, fontSize = 14.sp)
                    Text(when (stash.optString("kind")) { "feature" -> "Saved from ${stash.optString("origin")}"; "migration" -> "Migration work · inspect only"; else -> "Origin unverified · inspect only" }, color = if (stash.optString("kind") == "feature") Muted else Warm, fontSize = 14.sp)
                }
                HorizontalDivider(color = Outline)
            }
        }
    }
}

@Composable fun SavedChangeScreen(vm: WorkspaceModel, change: JSONObject) {
    var apply by remember { mutableStateOf(false) }
    val stash = change.optBoolean("stash")
    BackHandler { vm.closeInspection() }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeInspection) { Icon(Icons.Outlined.ArrowBack, "Back to ${if (stash) "stashes" else "history"}") }
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(change.optString("title"), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(change.optString("id").take(12), color = Muted, fontSize = 14.sp, fontFamily = CodeFont)
            }
        }
        ReviewError(vm) { vm.inspectChange(change, stash) }
        if (!vm.reviewLoading && vm.reviewError == null) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ChangeCount(change.optInt("additions"), change.optInt("deletions"))
                Spacer(Modifier.weight(1f))
                if (stash) TextButton(onClick = { apply = true }, enabled = change.optString("kind") == "feature" && !vm.git.optBoolean("migration") && !vm.git.optBoolean("protected") && vm.git.optString("branch").isNotEmpty() && !vm.busy) { Text("Apply stash") }
            }
            if (stash && (change.optString("kind") != "feature" || vm.git.optBoolean("migration"))) Text("Migration and unverified stashes require reviewing their origin in the terminal before restoring.", color = Warm, fontSize = 14.sp, modifier = Modifier.padding(18.dp))
            DiffViewer(change.optString("diff"), change.optBoolean("truncated"), Modifier.weight(1f))
        }
    }
    if (apply) ConfirmDialog("Apply saved work?", "Apply this stash to ${vm.project}. The working tree must be clean. The stash will remain saved; any conflicts can be resolved in Files or the terminal.", "Apply & keep stash", { apply = false }) { vm.applyStash(change.getString("id")); apply = false }
}
