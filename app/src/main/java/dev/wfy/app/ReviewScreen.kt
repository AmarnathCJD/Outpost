package dev.wfy.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

val Added = Color(0xFF89D185)
val Removed = Color(0xFFF48771)

@Composable fun ChangeCount(additions: Int, deletions: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("+$additions", color = Added, fontFamily = CodeFont, fontSize = 13.sp)
        Text("-$deletions", color = Removed, fontFamily = CodeFont, fontSize = 13.sp)
    }
}

@Composable fun ReviewError(vm: WorkspaceModel, retry: () -> Unit) {
    vm.reviewError?.let { error ->
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(error, color = Warm, fontSize = 14.sp)
            TextButton(onClick = retry) { Text("Try again") }
        }
    }
    if (vm.reviewLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
}

@Composable fun PullRequestsScreen(vm: WorkspaceModel) {
    val pull = vm.selectedPull
    if (pull != null) { PullDetailScreen(vm, pull); return }
    var openNumber by remember { mutableStateOf(false) }
    LaunchedEffect(vm.project, vm.pullFilter) { if (vm.connected) vm.loadPulls() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(vm.pullQuery, { vm.pullQuery = it }, placeholder = { Text("Search pull requests", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.weight(1f), trailingIcon = { IconButton(onClick = vm::loadPulls, enabled = !vm.reviewLoading) { Icon(Icons.Outlined.Search, "Search pull requests") } })
            IconButton(onClick = { openNumber = true }) { Icon(Icons.Outlined.Tag, "Open PR by number", tint = LinkBlue) }
        }
        WorkbenchTabs(listOf("Open", "Closed", "Merged", "All"), vm.pullFilter.replaceFirstChar { it.uppercase() }) { vm.pullFilter = it.lowercase() }
        ReviewError(vm, vm::loadPulls)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (!vm.reviewLoading && vm.reviewError == null && vm.pulls.isEmpty()) item { EmptyState(Icons.Outlined.Merge, "No pull requests found", "Try another state or search. Use # to open a specific pull request.") }
            items(vm.pulls, key = { it.optInt("number") }) { p ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !vm.reviewLoading) { vm.openPull(p.getInt("number")) }.padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Merge, null, Modifier.padding(top = 2.dp).size(20.dp), tint = pullColor(p))
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.optString("title"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("#${p.optInt("number")}  ${p.optJSONObject("author")?.optString("login").orEmpty()}  ${if (p.optBoolean("isDraft")) "Draft" else p.optString("state").lowercase()}", color = Muted, fontSize = 13.sp)
                        Text("${p.optString("headRefName")} → ${p.optString("baseRefName")}", color = Muted, fontSize = 12.sp, fontFamily = CodeFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = Outline)
            }
            if (vm.pulls.size >= 100) item { Text("Showing the first 100 results. Search to narrow the list, or open a PR by number.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp)) }
        }
    }
    if (openNumber) FormDialog("Open pull request", listOf("PR number" to ""), "Open", { openNumber = false }) {
        val number = it[0].trim().removePrefix("#").toIntOrNull()
        if (number != null && number > 0) { vm.openPull(number); openNumber = false } else vm.notify("Enter a positive PR number")
    }
}

private fun pullColor(p: JSONObject) = when {
    p.optBoolean("isDraft") -> Muted
    p.optString("state") == "MERGED" -> Color(0xFFC586C0)
    p.optString("state") == "CLOSED" -> Removed
    else -> Added
}

@Composable internal fun PullDetailScreen(vm: WorkspaceModel, pull: JSONObject) {
    var section by remember(pull.optInt("number")) { mutableStateOf("Overview") }
    var diffPath by remember(pull.optInt("number")) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    BackHandler { vm.closePull() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closePull) { Icon(Icons.Outlined.ArrowBack, "Back to pull requests") }
            Text("Pull request #${pull.optInt("number")}", Modifier.weight(1f), fontSize = 16.sp)
            IconButton(onClick = {
                val uri = Uri.parse(pull.optString("url"))
                if (uri.scheme == "https" && uri.host == "github.com") runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { vm.notify("No browser is available") }
            }) { Icon(Icons.Outlined.OpenInNew, "Open PR on GitHub", tint = LinkBlue) }
        }
        WorkbenchTabs(listOf("Overview", "Files", "Checks", "Discussion", "Diff"), section) {
            section = it
            if (it == "Diff" && vm.pullDiff == null) vm.loadPullDiff()
        }
        ReviewError(vm) { if (section == "Diff") vm.loadPullDiff() else vm.openPull(pull.optInt("number")) }
        if (section == "Diff") {
            val diff = vm.pullDiff
            if (diff != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(diffPath ?: "All changed files", Modifier.weight(1f), fontSize = 13.sp, maxLines = 2)
                    if (diffPath != null) TextButton(onClick = { diffPath = null }) { Text("Show all") }
                }
                val text = remember(diff, diffPath) { filterDiff(diff.optString("diff"), diffPath) }
                DiffViewer(text, diff.optBoolean("truncated"), Modifier.weight(1f))
            }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (section) {
                "Overview" -> {
                    item { Text(pull.optString("title"), fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium) }
                    item { Text(if (pull.optBoolean("isDraft")) "Draft" else pull.optString("state").lowercase().replaceFirstChar { it.uppercase() }, color = pullColor(pull), fontSize = 14.sp) }
                    item { Text("${pull.optString("headRefName")} → ${pull.optString("baseRefName")}", fontFamily = CodeFont, fontSize = 13.sp, color = Muted) }
                    item { ChangeCount(pull.optInt("additions"), pull.optInt("deletions")); Text("${pull.optInt("changedFiles")} changed files", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp)) }
                    item { HorizontalDivider(color = Outline) }
                    item { SelectionContainer { Text(pull.optString("body").ifBlank { "No description provided." }, fontSize = 15.sp, lineHeight = 23.sp) } }
                    if (pull.optString("reviewDecision").isNotBlank()) item { Text("Review: ${pull.optString("reviewDecision").lowercase().replace('_', ' ')}", fontSize = 14.sp, color = Muted) }
                }
                "Files" -> {
                    val files = pull.optJSONArray("files")?.objects().orEmpty()
                    items(files) { file ->
                        Column(Modifier.fillMaxWidth().clickable { diffPath = file.optString("path"); section = "Diff"; if (vm.pullDiff == null) vm.loadPullDiff() }.padding(vertical = 8.dp)) {
                            Text(file.optString("path"), color = LinkBlue, fontSize = 15.sp)
                            Spacer(Modifier.height(6.dp)); ChangeCount(file.optInt("additions"), file.optInt("deletions"))
                        }
                        HorizontalDivider(color = Outline)
                    }
                    if (files.isEmpty()) item { Text("No changed files reported.", color = Muted) }
                    if (files.size < pull.optInt("changedFiles")) item { Text("GitHub returned a partial file list. Open the PR on GitHub for all files.", color = Warm, fontSize = 14.sp) }
                }
                "Checks" -> {
                    val checks = pull.optJSONArray("statusCheckRollup")?.objects().orEmpty()
                    if (checks.isEmpty()) item { Text("No checks reported for this pull request.", color = Muted) }
                    items(checks) { check ->
                        val state = check.optString("conclusion").ifBlank { check.optString("state").ifBlank { check.optString("status", "Pending") } }
                        val good = state.uppercase() in setOf("SUCCESS", "NEUTRAL", "SKIPPED")
                        val bad = state.uppercase() in setOf("FAILURE", "ERROR", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (good) Icons.Outlined.CheckCircle else if (bad) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule, null, tint = if (good) Added else if (bad) Removed else Warm)
                            Column(Modifier.padding(start = 12.dp)) { Text(check.optString("name").ifBlank { check.optString("context", "Check") }, fontSize = 15.sp); Text(state.lowercase().replace('_', ' '), color = Muted, fontSize = 13.sp) }
                        }
                    }
                }
                "Discussion" -> {
                    val comments = pull.optJSONArray("comments")?.objects().orEmpty()
                    item { Text("PR conversation comments. Inline review threads are available through Open PR on GitHub.", color = Muted, fontSize = 13.sp) }
                    if (comments.isEmpty()) item { Text("No conversation comments yet.", color = Muted) }
                    items(comments) { comment ->
                        CardBlock {
                            Text(comment.optJSONObject("author")?.optString("login") ?: "GitHub user", color = LinkBlue, fontSize = 14.sp)
                            SelectionContainer { Text(comment.optString("body"), fontSize = 15.sp, lineHeight = 23.sp) }
                            Text(comment.optString("createdAt").replace('T', ' ').removeSuffix("Z"), color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun filterDiff(diff: String, path: String?): String {
    if (path == null) return diff
    return diff.split(Regex("(?m)(?=^diff --git )")).filter { block ->
        block.lineSequence().any { it == "+++ b/$path" || it == "--- a/$path" || it == "+++ \"b/$path\"" || it == "--- \"a/$path\"" }
    }.joinToString("")
}

private data class DiffLine(val text: String, val old: String = "", val new: String = "", val kind: Char = ' ')
private fun diffLines(diff: String): List<DiffLine> {
    var old = 0; var new = 0; var hunk = false
    val header = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
    return diff.lineSequence().map { line ->
        val match = header.find(line)
        if (match != null) { old = match.groupValues[1].toInt(); new = match.groupValues[2].toInt(); hunk = true; DiffLine(line, kind = '@') }
        else if (line.startsWith("diff --git")) { hunk = false; DiffLine(line, kind = '@') }
        else if (hunk && line.startsWith('+')) DiffLine(line, new = (new++).toString(), kind = '+')
        else if (hunk && line.startsWith('-')) DiffLine(line, old = (old++).toString(), kind = '-')
        else if (hunk && line.startsWith(' ')) DiffLine(line, (old++).toString(), (new++).toString())
        else DiffLine(line)
    }.toList()
}

@Composable fun DiffViewer(diff: String, truncated: Boolean = false, modifier: Modifier = Modifier) {
    val lines = remember(diff) { diffLines(diff) }
    var wrap by remember { mutableStateOf(true) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Unified diff", Modifier.weight(1f), color = Muted, fontSize = 13.sp)
            TextButton(onClick = { wrap = !wrap }) { Text(if (wrap) "Wrap: on" else "Wrap: off", fontSize = 13.sp) }
        }
        if (truncated) Text("Large diff: preview is limited to 1 MiB. Counts cover the loaded preview.", color = Warm, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        if (diff.isEmpty()) Text("No text diff available for this selection. Binary files and some rename-only changes have no inline patch.", color = Muted, modifier = Modifier.padding(18.dp))
        if (lines.any { it.text.length > 4000 }) Text("Very long lines are shortened in this preview.", color = Warm, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("diff-lines")) {
            items(lines) { line ->
                val background = when (line.kind) { '+' -> Color(0xFF203728); '-' -> Color(0xFF40282A); '@' -> Color(0xFF223344); else -> Ink }
                Row(Modifier.fillMaxWidth().background(background).padding(vertical = 3.dp)) {
                    Text(line.old, Modifier.width(37.dp).padding(end = 5.dp), fontFamily = CodeFont, color = Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(line.new, Modifier.width(37.dp).padding(end = 5.dp), fontFamily = CodeFont, color = Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(line.text.take(4000) + if (line.text.length > 4000) " [line shortened]" else "", if (wrap) Modifier.padding(end = 8.dp) else Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp), fontFamily = CodeFont, fontSize = 14.sp, lineHeight = 21.sp, color = when (line.kind) { '+' -> Added; '-' -> Removed; '@' -> LinkBlue; else -> MaterialTheme.colorScheme.onSurface }, softWrap = wrap)
                    }
                }
            }
        }
    }
}

@Composable fun LocalDiffScreen(vm: WorkspaceModel, diff: JSONObject) {
    val path = diff.optString("path")
    val staged = diff.optBoolean("staged")
    BackHandler { vm.closeDiff() }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeDiff) { Icon(Icons.Outlined.ArrowBack, "Back to changes") }
            Column(Modifier.weight(1f)) { Text(path.substringAfterLast('/'), fontSize = 16.sp); Text(if (staged) "Staged changes" else "Working changes", fontSize = 13.sp, color = Muted) }
            IconButton(onClick = { vm.openDiff(path, staged) }, enabled = !vm.reviewLoading) { Icon(Icons.Outlined.Refresh, "Refresh diff") }
        }
        WorkbenchTabs(listOf("Working", "Staged"), if (staged) "Staged" else "Working") { vm.openDiff(path, it == "Staged") }
        ReviewError(vm) { vm.openDiff(path, staged) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChangeCount(diff.optInt("additions"), diff.optInt("deletions"))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.open(path) }, enabled = !vm.busy) { Text("Open file") }
        }
        DiffViewer(diff.optString("diff"), diff.optBoolean("truncated"), Modifier.weight(1f))
    }
}
