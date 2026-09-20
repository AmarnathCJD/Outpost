package dev.wfy.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable fun WorkbenchOverlay(title: String, subtitle: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.ArrowBack, "Close $title") }
                    Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
                        Text(title, fontSize = 20.sp)
                        Text(subtitle, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = Outline)
                content()
            }
        }
    }
}

@Composable fun RepositorySearch(vm: WorkspaceModel, dismiss: () -> Unit) {
    var mode by remember { mutableStateOf("Files") }
    var query by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var glob by remember { mutableStateOf("") }
    var paths by remember { mutableStateOf<List<String>>(emptyList()) }
    var matches by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.project, vm.connectionGeneration, mode, query, matchCase, wholeWord, glob, retry) {
        paths = emptyList(); matches = emptyList(); error = null
        if (mode == "Contents" && query.length < 2) { loading = false; return@LaunchedEffect }
        loading = true
        delay(300)
        try {
            val base = "project=${enc(vm.project)}&q=${enc(query)}"
            if (mode == "Files") {
                val result = JSONArray(vm.api.call("/api/quick-open?$base"))
                paths = (0 until result.length()).map { result.getString(it) }
            } else matches = JSONArray(vm.api.call("/api/search?$base&case=$matchCase&word=$wholeWord&glob=${enc(glob)}")).objects()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }
    WorkbenchOverlay("Find in workspace", vm.project, dismiss) {
        WorkbenchTabs(listOf("Files", "Contents"), mode) { mode = it }
        OutlinedTextField(query, { query = it.take(200) }, singleLine = true, placeholder = { Text(if (mode == "Files") "File name or path…" else "Find text…") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } }, modifier = Modifier.fillMaxWidth().padding(16.dp))
        if (mode == "Contents") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = matchCase, onClick = { matchCase = !matchCase }, label = { Text("Match case") })
                FilterChip(selected = wholeWord, onClick = { wholeWord = !wholeWord }, label = { Text("Whole word") })
            }
            OutlinedTextField(glob, { glob = it.take(200) }, singleLine = true, label = { Text("Files to include (optional)") }, placeholder = { Text("*.go or internal/**") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        error?.let { Text(it, color = Warm, modifier = Modifier.padding(16.dp)); TextButton(onClick = { retry++ }) { Text("Try again") } }
        val count = if (mode == "Files") paths.size else matches.size
        Text(when { mode == "Contents" && query.length < 2 -> "Enter at least 2 characters"; loading -> "Searching…"; count == 100 -> "First 100 results · narrow your search"; else -> "$count results" }, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp)) {
            if (mode == "Files") items(paths, key = { it }) { path ->
                Row(Modifier.testTag("search-result-$path").fillMaxWidth().heightIn(min = 68.dp).clickable(enabled = !vm.busy) { vm.open(path); dismiss() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Description, null, tint = LinkBlue, modifier = Modifier.size(22.dp))
                    Column(Modifier.padding(start = 12.dp)) { Text(path.substringAfterLast('/'), fontSize = 17.sp); Text(path.substringBeforeLast('/', "root"), fontSize = 14.sp, color = Muted, maxLines = 2) }
                }
                HorizontalDivider(color = Outline)
            } else items(matches) { result ->
                Column(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.open(result.getString("path"), result.getInt("line")); dismiss() }.padding(vertical = 14.dp)) {
                    Text("${result.getString("path")}:${result.getInt("line")}", color = LinkBlue, fontSize = 15.sp)
                    Text(result.getString("text"), maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = CodeFont, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
                HorizontalDivider(color = Outline)
            }
            item { Text("Search respects Git ignores and excludes hidden files. Use the explorer to open hidden configuration files.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 20.dp)) }
        }
    }
}

@Composable fun RecoveryBrowser(vm: WorkspaceModel, dismiss: () -> Unit) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var restore by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.project, refresh) {
        loading = true; error = null
        try { items = JSONArray(vm.api.call("/api/recovery?project=${enc(vm.project)}")).objects() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }
    WorkbenchOverlay("Recovery bin", vm.project, dismiss) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Deleted files stay here until you restore them.", Modifier.weight(1f), fontSize = 15.sp, color = Muted)
            IconButton(onClick = { refresh++ }, enabled = !loading) { Icon(Icons.Outlined.Refresh, "Refresh recovery bin") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = Warm, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            if (items.isEmpty() && !loading && error == null) item { EmptyState(Icons.Outlined.RestoreFromTrash, "Nothing to recover", "Files and folders deleted from Outpost appear here.") }
            items(items, key = { it.getString("id") }) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (item.optBoolean("directory")) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = Muted)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(item.getString("path"), fontSize = 16.sp)
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(item.optLong("deleted") * 1000)), color = Muted, fontSize = 13.sp)
                    }
                    TextButton(onClick = { restore = item }, enabled = !loading && !vm.busy) { Text("Restore") }
                }
                HorizontalDivider(color = Outline)
            }
        }
    }
    restore?.let { item ->
        FormDialog("Restore deleted item", listOf("Restore to path" to item.getString("path")), "Restore", { restore = null }) { values ->
            restore = null; loading = true; error = null
            scope.launch {
                try {
                    vm.api.call("/api/recovery/restore", "POST", obj("project" to vm.project, "id" to item.getString("id"), "target" to values[0]))
                    vm.notify("Restored ${values[0]}"); vm.refreshNow(); refresh++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
                finally { loading = false }
            }
        }
    }
}

@Composable fun PortsDialog(vm: WorkspaceModel, dismiss: () -> Unit) {
    var host by remember { mutableStateOf(vm.config.backendHost.ifBlank { "127.0.0.1" }) }
    var port by remember { mutableStateOf("8080") }
    var label by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    WorkbenchOverlay("Running services", "Ports forwarded through SSH", dismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Open a running API or web app on this phone. Start the service in a terminal, then forward its port here.", color = Muted, fontSize = 15.sp)
            OutlinedTextField(host, { host = it }, label = { Text("Host as seen from the SSH server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.weight(.4f))
                OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.weight(.6f))
            }
            WorkbenchButton(onClick = { vm.addForward(host, port, label) }, enabled = vm.connected && !vm.busy && host.isNotBlank() && port.toIntOrNull() in 1..65535, modifier = Modifier.fillMaxWidth()) { Text("Forward port") }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.message?.let { Text(it, color = Warm, fontSize = 14.sp) }
            Text("Docker services must be reachable from SSH: use a published localhost port or the container's private IP. Forwards bind only to this phone's loopback and close when SSH disconnects.", color = Muted, fontSize = 14.sp)
            Label("Active forwards · ${vm.forwards.size}")
            vm.forwards.forEach { forward ->
                CardBlock {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(forward.label, fontSize = 17.sp); Text("${forward.host}:${forward.port}", color = Muted, fontSize = 14.sp, fontFamily = CodeFont) }
                        IconButton(onClick = { vm.closeForward(forward) }, enabled = !vm.busy) { Icon(Icons.Outlined.Close, "Close ${forward.label}") }
                    }
                    Text(forward.url, color = LinkBlue, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkbenchOutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(forward.url))) }.onFailure { vm.notify("No browser is available to open this link") } }, enabled = vm.connected) { Text("Open browser") }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(forward.url)); vm.notify("Local URL copied") }) { Text("Copy URL") }
                    }
                }
            }
        }
    }
}
