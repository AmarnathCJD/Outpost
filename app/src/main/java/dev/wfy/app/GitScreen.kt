package dev.wfy.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

@Composable fun GitScreen(vm: WorkspaceModel) = RequireWorkspace(vm) {
    vm.localDiff?.let { LocalDiffScreen(vm, it); return@RequireWorkspace }
    vm.inspectedChange?.let { SavedChangeScreen(vm, it); return@RequireWorkspace }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 18.dp)) {
            PageTitle(vm.project, "Source control") {
                IconButton(onClick = { when (vm.gitSection) { "Pull requests" -> vm.loadPulls(); "History" -> vm.loadHistory(); "Stashes" -> vm.loadStashes(); else -> vm.refreshNow() } }, enabled = vm.connected && !vm.busy && !vm.reviewLoading) { Icon(Icons.Outlined.Refresh, "Refresh source control") }
            }
        }
        WorkbenchTabs(listOf("Changes", "Pull requests", "History", "Stashes"), vm.gitSection) { vm.gitSection = it }
        Box(Modifier.weight(1f)) {
            when (vm.gitSection) {
                "Pull requests" -> PullRequestsScreen(vm)
                "History" -> CommitHistoryScreen(vm)
                "Stashes" -> StashesScreen(vm)
                else -> GitChangesScreen(vm)
            }
        }
    }
}

@Composable private fun GitChangesScreen(vm: WorkspaceModel) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var selected by remember(vm.project) { mutableStateOf(setOf<String>()) }
    val git = vm.git
    val migration = git.optBoolean("migration")
    val protected = git.optBoolean("protected")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(14.dp))
        CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.AccountTree, null, tint = Accent); Text(git.optString("branch", "No branch"), Modifier.weight(1f).padding(horizontal = 10.dp), fontFamily = CodeFont, fontSize = 16.sp); if (protected || migration) Icon(Icons.Outlined.Lock, "Branch restrictions active", tint = Warm, modifier = Modifier.size(18.dp)) }
            Text(if (migration) "Migration workspace · push only after TM/TL approval. Feature PRs are disabled." else if (protected) "Protected branch · create a feature workspace before committing." else "Feature workspace · commit here, then open a pull request.", color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { WorkbenchOutlinedButton(onClick = { dialog = "worktree" }) { Text("New workspace", fontSize = 14.sp) }; TextButton(onClick = { dialog = "switch" }, enabled = !migration) { Text("Switch", fontSize = 14.sp) } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.gitAction("fetch") }, label = { Text("Fetch") }, leadingIcon = { Icon(Icons.Outlined.Sync, null, Modifier.size(16.dp)) })
            AssistChip(onClick = { vm.gitAction("pull") }, label = { Text("Pull · ff only") })
            AssistChip(onClick = { dialog = "stash" }, label = { Text("Save stash") })
            AssistChip(onClick = { vm.gitSection = "Stashes" }, label = { Text("Browse stashes") })
        }
        val files = git.optJSONArray("files")?.objects().orEmpty()
        LaunchedEffect(files.map { it.optString("path") }) { selected = selected.intersect(files.map { it.optString("path") }.toSet()) }
        Label("Working changes · ${files.size}")
        if (files.isEmpty()) Text("Working tree is clean. Ready for your next change.", color = Muted, fontSize = 15.sp)
        files.forEach { f -> val path = f.getString("path"); Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = path in selected, onCheckedChange = { selected = if (it) selected + path else selected - path })
            Text(f.getString("status").replace(' ', '·'), color = Warm, fontFamily = CodeFont, fontSize = 14.sp)
            Text(path, Modifier.weight(1f).clickable { vm.openDiff(path, f.optString("status").getOrNull(1) == ' ') }.padding(start = 10.dp, top = 14.dp, bottom = 14.dp), fontSize = 16.sp, color = LinkBlue)
            IconButton(onClick = { vm.openDiff(path, f.optString("status").getOrNull(1) == ' ') }) { Icon(Icons.Outlined.Difference, "View changes in $path", tint = Muted, modifier = Modifier.size(20.dp)) }
        } }
        if (files.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selected = files.map { it.getString("path") }.toSet() }) { Text("Select all") }
            TextButton(onClick = { vm.gitAction("stage", obj("paths" to JSONArray(selected.toList()))); selected = emptySet() }, enabled = selected.isNotEmpty()) { Text("Stage") }
            TextButton(onClick = { vm.gitAction("unstage", obj("paths" to JSONArray(selected.toList()))); selected = emptySet() }, enabled = selected.isNotEmpty()) { Text("Unstage") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkbenchButton(onClick = { dialog = "commit" }, enabled = !protected && !vm.busy && files.any { it.optString("status").firstOrNull() !in listOf(null, ' ', '?') }, modifier = Modifier.weight(1f)) { Text("Commit") }
            WorkbenchOutlinedButton(onClick = { dialog = "push" }, enabled = !protected && !vm.busy, modifier = Modifier.weight(1f)) { Text("Push") }
        }
        WorkbenchOutlinedButton(onClick = { dialog = "pr" }, enabled = !migration && !protected && !vm.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Merge, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open pull request") }
        Text("Tap a file to review additions and deletions. Select checkboxes to stage or unstage files.", color = Muted, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(24.dp))
    }
    when (dialog) {
        "commit" -> FormDialog("Commit staged changes", listOf("Commit message" to ""), "Commit", { dialog = null }) { vm.gitAction("commit", obj("message" to it[0])); dialog = null }
        "switch" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Switch branch") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val branches = git.optJSONArray("branches") ?: JSONArray()
                (0 until branches.length()).forEach { i -> val branch = branches.getString(i)
                    TextButton(onClick = { vm.gitAction("switch", obj("branch" to branch)); dialog = null }, enabled = branch != git.optString("branch")) { Icon(Icons.Outlined.AccountTree, null, Modifier.size(16.dp)); Spacer(Modifier.width(10.dp)); Text(branch) }
                }
            }
        }, confirmButton = { TextButton(onClick = { dialog = "newbranch" }) { Text("New branch") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        "newbranch" -> FormDialog("New feature branch", listOf("Branch name" to "feature/"), "Create", { dialog = null }) { vm.gitAction("branch", obj("branch" to it[0])); dialog = null }
        "worktree" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Separate workspace") }, text = { Text("Each workspace has its own files and branch. Migration changes stay out of feature work.") }, confirmButton = { TextButton(onClick = { dialog = "feature" }) { Text("Feature") } }, dismissButton = { TextButton(onClick = { dialog = "migration" }) { Text("Migration") } })
        "feature" -> FormDialog("Feature workspace", listOf("Workspace name" to "", "New feature branch" to "feature/"), "Create", { dialog = null }) { vm.worktree(it[0], it[1], false); dialog = null }
        "migration" -> FormDialog("Migration workspace", listOf("Workspace name" to vm.project + "-migrations"), "Create", { dialog = null }) { vm.worktree(it[0], "", true); dialog = null }
        "push" -> ConfirmDialog(if (migration) "TM/TL approval received?" else "Push this feature branch?", if (migration) "Only continue if your team manager or lead has approved these migration commits. They will be pushed to the migration branch only." else "Push ${git.optString("branch")} to origin. Your target branch changes only through a pull request.", if (migration) "Approved · push migrations" else "Push branch", { dialog = null }) { vm.gitAction("push", obj("approved" to migration)); dialog = null }
        "pr" -> FormDialog("Open GitHub pull request", listOf("PR title" to "", "PR body" to ""), "Create PR", { dialog = null }) { vm.gitAction("pr", obj("title" to it[0], "body" to it[1])); dialog = null }
        "stash" -> FormDialog("Save working changes", listOf("Stash description" to "Saved work"), "Save stash", { dialog = null }) { vm.gitAction("stash", obj("message" to it[0])); dialog = null }
    }
}
