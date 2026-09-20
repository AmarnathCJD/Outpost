package dev.wfy.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import org.json.JSONObject

@Composable fun FilesScreen(vm: WorkspaceModel) = RequireWorkspace(vm) {
    var create by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var rename by remember { mutableStateOf<JSONObject?>(null) }
    var delete by remember { mutableStateOf<JSONObject?>(null) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Name") }
    var sortMenu by remember { mutableStateOf(false) }
    var toolsMenu by remember { mutableStateOf(false) }
    val fileClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    BackHandler(vm.directory.isNotEmpty()) { vm.browse(vm.directory.substringBeforeLast('/', "")) }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        PageTitle(vm.project, "Files", if (vm.lastSync.isNotEmpty()) "Synced ${vm.lastSync} · pull changes with Git" else "Your server’s working tree") {
            IconButton(onClick = { vm.showSearch = true }) { Icon(Icons.Outlined.Search, "Search repository") }
            Box {
                IconButton(onClick = { toolsMenu = true }) { Icon(Icons.Outlined.MoreVert, "File tools") }
                DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                    DropdownMenuItem(text = { Text("New file") }, onClick = { create = "file"; toolsMenu = false })
                    DropdownMenuItem(text = { Text("New folder") }, onClick = { create = "folder"; toolsMenu = false })
                    DropdownMenuItem(text = { Text("Recovery bin") }, onClick = { vm.showRecovery = true; toolsMenu = false })
                }
            }
        }
        OutlinedTextField(search, { search = it }, placeholder = { Text("Filter this directory") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.Sort, "Sort files: $sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("Name", "Modified", "Size").forEach { order -> DropdownMenuItem(text = { Text(order) }, onClick = { sort = order; sortMenu = false }) }
                }
            }
        }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (vm.recentFiles.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.recentFiles.forEach { path -> AssistChip(onClick = { vm.open(path) }, label = { Text(path.substringAfterLast('/'), fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Outlined.History, null, Modifier.size(14.dp)) }) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.browse(vm.directory.substringBeforeLast('/', "")) }, enabled = vm.directory.isNotEmpty()) { Icon(Icons.Outlined.ArrowUpward, "Parent folder") }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("root", Modifier.clickable { vm.browse("") }.padding(8.dp), fontFamily = CodeFont, fontSize = 14.sp, color = Accent)
                vm.directory.split('/').filter { it.isNotEmpty() }.forEachIndexed { index, segment -> Text("/", color = Muted, fontSize = 13.sp); Text(segment, Modifier.clickable { vm.browse(vm.directory.split('/').take(index + 1).joinToString("/")) }.padding(8.dp), fontFamily = CodeFont, fontSize = 14.sp, color = Accent) }
            }
            IconButton(onClick = { vm.refreshNow() }) { Icon(Icons.Outlined.Refresh, "Refresh files") }
        }
        LazyColumn(Modifier.weight(1f)) {
            val filtered = vm.entries.filter { it.getString("name").contains(search, true) }.let { entries ->
                when (sort) { "Modified" -> entries.sortedByDescending { it.optLong("modified") }; "Size" -> entries.sortedByDescending { it.optLong("size") }; else -> entries.sortedBy { it.optString("name").lowercase() } }
            }.sortedBy { !it.optBoolean("directory") }
            if (filtered.isEmpty()) item { Text("This directory has no matching files.", color = Muted, modifier = Modifier.padding(vertical = 24.dp)) }
            items(filtered, key = { it.getString("path") }) { e ->
                Row(Modifier.fillMaxWidth().clickable { if (e.getBoolean("directory")) { search = ""; vm.browse(e.getString("path")) } else vm.open(e.getString("path")) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (e.getBoolean("directory")) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = if (e.getBoolean("directory")) Warm else Accent, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(e.getString("name"), fontSize = 16.sp); if (!e.getBoolean("directory")) Text(formatSize(e.optLong("size")), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
                    Box {
                        IconButton(onClick = { selected = e }) { Icon(Icons.Outlined.MoreVert, "Actions for ${e.getString("name")}", tint = Muted) }
                        DropdownMenu(expanded = selected == e, onDismissRequest = { selected = null }) {
                            DropdownMenuItem(text = { Text("Copy path") }, onClick = { fileClipboard.setText(AnnotatedString(e.getString("path"))); selected = null; vm.notify("File path copied") })
                            DropdownMenuItem(text = { Text("Rename / move") }, onClick = { rename = e; selected = null })
                            DropdownMenuItem(text = { Text("Move to recovery") }, onClick = { delete = e; selected = null })
                        }
                    }
                }
                HorizontalDivider(color = Panel)
            }
        }
    }
    create?.let { type -> FormDialog(if (type == "file") "New file" else "New folder", listOf("Path relative to project" to if (vm.directory.isEmpty()) "" else vm.directory + "/"), "Create", { create = null }) { if (type == "file") vm.newFile(it[0]) else vm.fileAction("mkdir", it[0]); create = null } }
    rename?.let { e -> FormDialog("Rename or move", listOf("New path" to e.getString("path")), "Move", { rename = null }) { vm.fileAction("rename", e.getString("path"), it[0]); rename = null } }
    delete?.let { e -> ConfirmDialog("Remove ${e.getString("name")}?", "Restore it later from the Recovery bin. Review the deletion in Git before committing.", "Move to recovery", { delete = null }) { vm.fileAction("delete", e.getString("path")); delete = null } }

}
fun formatSize(bytes: Long): String = if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / (1024 * 1024)} MB"

private class CodeColors : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        if (text.length < 150000) {
            Regex("\\b(package|import|func|type|struct|interface|return|if|else|for|range|var|const|go|defer|switch|case|break|continue|select|chan|map|nil|true|false|SELECT|FROM|WHERE|CREATE|TABLE|INSERT|INTO|ALTER)\\b").findAll(text.text).forEach { builder.addStyle(SpanStyle(color = Color(0xFF569CD6)), it.range.first, it.range.last + 1) }
            Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'").findAll(text.text).forEach { builder.addStyle(SpanStyle(color = Color(0xFFCE9178)), it.range.first, it.range.last + 1) }
            Regex("//[^\\n]*|#[^\\n]*").findAll(text.text).forEach { builder.addStyle(SpanStyle(color = Color(0xFF6A9955)), it.range.first, it.range.last + 1) }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun EditorScreen(vm: WorkspaceModel) {
    val f = vm.file ?: return
    val dirty = f.content != f.original
    var discard by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf(false) }
    var needle by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var smartTyping by rememberSaveable { mutableStateOf(true) }
    var selecting by remember { mutableStateOf(false) }
    var indent by rememberSaveable(f.path) { mutableStateOf(editorIndent(f.content, f.path)) }
    var indentMenu by remember { mutableStateOf(false) }
    var snippetMenu by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // Keep scroll state outside keyboard-dependent content and suppress focus/IME
    // relocation while the viewport resizes. Typing can reveal the caret normally.
    val scroll = rememberScrollState()
    val horizontal = rememberScrollState()
    var holdScrollUntil by remember { mutableLongStateOf(0L) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(keyboardVisible) { holdScrollUntil = android.os.SystemClock.uptimeMillis() + 500 }
    val editorBringIntoView = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (android.os.SystemClock.uptimeMillis() < holdScrollUntil) return 0f
                return when { offset < 0 -> offset; offset + size > containerSize -> offset + size - containerSize; else -> 0f }
            }
        }
    }
    var readOnly by remember { mutableStateOf(false) }
    var gotoLine by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(false) }
    var value by remember(f.path) { mutableStateOf(TextFieldValue(f.content)) }
    var textLayout by remember(f.path) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var revealOffset by remember(f.path) { mutableStateOf<Int?>(null) }
    LaunchedEffect(f.path, vm.editorLine) {
        vm.editorLine?.let { line ->
            val offset = lineOffset(f.content, line)
            value = TextFieldValue(f.content, TextRange(offset)); revealOffset = offset; vm.editorLine = null
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(f.content) {
        if (value.text != f.content) value = TextFieldValue(f.content, TextRange(value.selection.start.coerceAtMost(f.content.length), value.selection.end.coerceAtMost(f.content.length)))
    }
    fun applyEdit(next: TextFieldValue, refocus: Boolean = false) {
        value = next; vm.edit(next.text)
        if (refocus) { editorFocus.requestFocus(); keyboard?.show() }
    }
    val insert: (String) -> Unit = { text -> applyEdit(editorInsert(value, text), true) }
    val findNext: () -> Unit = {
        if (needle.isNotEmpty()) {
            val index = value.text.indexOf(needle, value.selection.max).takeIf { it >= 0 } ?: value.text.indexOf(needle)
            if (index >= 0) { value = value.copy(selection = TextRange(index, index + needle.length)); revealOffset = index } else vm.notify("No matches found")
        }
    }
    val close = { if (dirty) discard = true else vm.closeFile() }
    BackHandler { close() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.Outlined.ArrowBack, "Close editor") }
            Column(Modifier.weight(1f)) { Text(f.path.substringAfterLast('/'), maxLines = 1, fontSize = 16.sp); Text(if (dirty) "Unsaved changes" else "Saved on server", color = if (dirty) Warm else Muted, fontSize = 13.sp) }
            TextButton(onClick = { vm.saveFile() }, enabled = dirty && !vm.busy) { Text("Save") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Editor options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (readOnly) "Enable editing" else "View only") }, onClick = { readOnly = !readOnly; menu = false })
                    DropdownMenuItem(text = { Text(if (wrap) "Disable word wrap" else "Enable word wrap") }, onClick = { wrap = !wrap; menu = false })
                    DropdownMenuItem(text = { Text(if (smartTyping) "Turn off smart indentation & pairs" else "Turn on smart indentation & pairs") }, onClick = { smartTyping = !smartTyping; menu = false })
                    DropdownMenuItem(text = { Text("Select all") }, onClick = { value = value.copy(selection = TextRange(0, value.text.length)); selecting = true; menu = false; editorFocus.requestFocus() })
                    DropdownMenuItem(text = { Text("Go to line") }, onClick = { gotoLine = true; menu = false })
                    DropdownMenuItem(text = { Text("Copy file path") }, onClick = { clipboard.setText(AnnotatedString(f.path)); menu = false; vm.notify("File path copied") })
                    DropdownMenuItem(text = { Text("File commit history") }, onClick = { menu = false; if (dirty) vm.notify("Save or keep your draft before opening history") else { vm.closeFile(); vm.historyPath = f.path; vm.gitSection = "History"; vm.tab = 2 } })
                    DropdownMenuItem(text = { Text("Reload from server") }, onClick = { menu = false; if (dirty) reload = true else vm.open(f.path) })
                }
            }
        }
        if (!keyboardVisible) Row(Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState())) {
            vm.recentFiles.forEach { path ->
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 110.dp).clickable { if (path != f.path) { if (dirty) vm.notify("Save or discard your current edits before switching files") else vm.open(path) } }) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (path == f.path) Accent else Panel))
                    Row(Modifier.background(if (path == f.path) Ink else Panel).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(14.dp), tint = LinkBlue)
                        Text(path.substringAfterLast('/') + if (path == f.path && dirty) " *" else "", Modifier.padding(start = 7.dp), fontSize = 14.sp, color = if (path == f.path) Color.White else Muted)
                    }
                }
            }
        }
        if (!keyboardVisible) Text("${vm.project} / ${f.path}", Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp, color = Muted, maxLines = 1)
        if (!keyboardVisible) Row(Modifier.fillMaxWidth().background(Panel), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::undoEdit, enabled = vm.canUndo && !readOnly) { Icon(Icons.Outlined.Undo, "Undo edit", Modifier.size(20.dp)) }
            IconButton(onClick = vm::redoEdit, enabled = vm.canRedo && !readOnly) { Icon(Icons.Outlined.Redo, "Redo edit", Modifier.size(20.dp)) }
            IconButton(onClick = { find = !find }) { Icon(Icons.Outlined.FindReplace, "Find and replace", Modifier.size(20.dp)) }
            Text(if (readOnly) "VIEW ONLY" else f.path.substringAfterLast('.', "text").uppercase(), Modifier.weight(1f), color = Muted, fontSize = 14.sp)
            TextButton(onClick = { wrap = !wrap }) { Text(if (wrap) "Wrap on" else "Wrap off", fontSize = 14.sp) }
        }
        if (find) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(needle, { needle = it }, label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f)); TextButton(onClick = findNext, enabled = needle.isNotEmpty()) { Text("Next") } }
            if (!readOnly) Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(replacement, { replacement = it }, label = { Text("Replace with") }, singleLine = true, modifier = Modifier.weight(1f)); TextButton(onClick = { if (needle.isNotEmpty()) vm.edit(f.content.replace(needle, replacement)) }, enabled = needle.isNotEmpty()) { Text("Replace all") } }
        }
        HorizontalDivider(color = Panel)
        LaunchedEffect(revealOffset, textLayout) {
            val offset = revealOffset
            val layout = textLayout
            if (offset != null && layout != null && offset <= layout.layoutInput.text.length) {
                scroll.animateScrollTo(layout.getLineTop(layout.getLineForOffset(offset)).toInt())
                revealOffset = null
            }
        }
        val font = vm.config.fontSize.toIntOrNull()?.coerceIn(10, 24) ?: 15
        CompositionLocalProvider(LocalBringIntoViewSpec provides editorBringIntoView) {
        Row(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportHeight = it.height }.testTag("editor-scroll").verticalScroll(scroll).padding(vertical = 12.dp)) {
            EditorGutter(f.content, textLayout, scroll, viewportHeight, font, value.selection.end)
            Box(Modifier.weight(1f).then(if (!wrap) Modifier.horizontalScroll(horizontal) else Modifier)) {
                BasicTextField(value, { applyEdit(editorKeyboardEdit(value, it, indent, smartTyping)) }, readOnly = readOnly, onTextLayout = { textLayout = it }, modifier = Modifier.testTag("code-editor").focusRequester(editorFocus).onFocusChanged { if (it.isFocused) holdScrollUntil = android.os.SystemClock.uptimeMillis() + 900 }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else if (event.isCtrlPressed || event.isMetaPressed) when (event.key) {
                        Key.S -> { if (dirty && !vm.busy) vm.saveFile(); true }
                        Key.Z -> { if (!readOnly) { if (event.isShiftPressed) vm.redoEdit() else vm.undoEdit() }; true }
                        Key.Y -> { if (!readOnly) vm.redoEdit(); true }
                        Key.F -> { find = !find; true }
                        Key.L -> { gotoLine = true; true }
                        else -> false
                    } else if (event.key == Key.Tab && !readOnly) { applyEdit(editorIndentLines(value, indent, event.isShiftPressed)); true } else false
                }.then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = 330.dp)).padding(end = 18.dp, bottom = 96.dp), textStyle = TextStyle(color = Color(0xFFCCCCCC), fontFamily = CodeFont, fontSize = font.sp, lineHeight = (font + 6).sp), cursorBrush = SolidColor(Color.White), visualTransformation = remember { CodeColors() }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
            }
        }
        }
        if (!readOnly) {
        Row(Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { applyEdit(editorMove(value, -1, selecting), true) }) { Icon(Icons.Outlined.ChevronLeft, "Move cursor left") }
            IconButton(onClick = { applyEdit(editorMove(value, 1, selecting), true) }) { Icon(Icons.Outlined.ChevronRight, "Move cursor right") }
            TextButton(onClick = { selecting = !selecting }, colors = ButtonDefaults.textButtonColors(contentColor = if (selecting) Warm else LinkBlue)) { Text(if (selecting) "Selecting" else "Select", fontSize = 12.sp) }
            IconButton(onClick = { applyEdit(editorIndentLines(value, indent, true), true) }) { Icon(Icons.Outlined.FormatIndentDecrease, "Outdent selected lines", Modifier.size(20.dp)) }
            IconButton(onClick = { applyEdit(editorIndentLines(value, indent, false), true) }) { Icon(Icons.Outlined.FormatIndentIncrease, "Indent or insert tab", Modifier.size(20.dp)) }
            IconButton(onClick = vm::undoEdit, enabled = vm.canUndo) { Icon(Icons.Outlined.Undo, "Undo typing", Modifier.size(20.dp)) }
            IconButton(onClick = vm::redoEdit, enabled = vm.canRedo) { Icon(Icons.Outlined.Redo, "Redo typing", Modifier.size(20.dp)) }
            IconButton(onClick = { keyboard?.hide() }) { Icon(Icons.Outlined.KeyboardHide, "Hide keyboard", Modifier.size(20.dp)) }
        }
        Row(Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            listOf("{" to "}", "(" to ")", "[" to "]", "\"" to "\"", "`" to "`").forEach { (left, right) ->
                TextButton(modifier = Modifier.widthIn(min = 48.dp).testTag("pair-$left"), onClick = { applyEdit(editorPair(value, left, right), true) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(left + right, fontFamily = CodeFont, fontSize = 15.sp) }
            }
            listOf(":=", "=", ":", ";", "/", "_", ",", ".", "->").forEach { text -> TextButton(onClick = { insert(text) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(text, fontFamily = CodeFont, fontSize = 15.sp) } }
            Box {
                TextButton(onClick = { snippetMenu = true }) { Text("Snippets", fontSize = 12.sp) }
                DropdownMenu(expanded = snippetMenu, onDismissRequest = { snippetMenu = false }) {
                    mapOf("Go error check" to "if err != nil {\n${indent}return err\n}", "Go function" to "func name() {\n$indent\n}", "Go range loop" to "for _, item := range items {\n$indent\n}").forEach { (name, template) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { snippetMenu = false; insert(template) })
                    }
                }
            }
        }
        }
        val caret = value.selection.end.coerceIn(0, value.text.length)
        val line = value.text.take(caret).count { it == '\n' } + 1
        val column = caret - value.text.lastIndexOf('\n', caret - 1)
        Row(Modifier.fillMaxWidth().background(Accent).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ln $line, Col $column" + if (!value.selection.collapsed) "  (${value.selection.length} selected)" else "", Modifier.weight(1f), fontFamily = CodeFont, fontSize = 11.sp, maxLines = 1, color = Color.White)
            Box {
                Text(if (indent == "\t") "Tabs" else "Spaces: ${indent.length}", Modifier.clickable { indentMenu = true }.padding(horizontal = 8.dp, vertical = 8.dp), fontSize = 11.sp, color = Color.White)
                DropdownMenu(expanded = indentMenu, onDismissRequest = { indentMenu = false }) { listOf("Tabs" to "\t", "2 spaces" to "  ", "4 spaces" to "    ").forEach { (label, unit) -> DropdownMenuItem(text = { Text(label) }, onClick = { indent = unit; indentMenu = false }) } }
            }
            TextButton(onClick = { if (keyboardVisible) vm.saveFile() else wrap = !wrap }, enabled = !keyboardVisible || (dirty && !vm.busy), contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .5f))) { Text(if (keyboardVisible) "Save" else if (wrap) "Wrap" else "No wrap", fontSize = 11.sp) }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Keep your edits?") }, text = { Text("Keep a draft on this phone to continue later, or discard these unsaved edits. Drafts are encrypted and appear in Work.") }, confirmButton = { TextButton(onClick = { vm.closeFile(); discard = false }) { Text("Keep draft & close") } }, dismissButton = { Row { TextButton(onClick = { discard = false }) { Text("Stay") }; TextButton(onClick = { vm.closeFile(discard = true); discard = false }) { Text("Discard") } } })
    if (reload) ConfirmDialog("Reload this file?", "Unsaved edits will be replaced with the server version.", "Reload", { reload = false }) { vm.open(f.path, discardDraft = true); reload = false }
    if (gotoLine) FormDialog("Go to line", listOf("Line number" to ""), "Go", { gotoLine = false }) {
        val line = it[0].toIntOrNull()
        val count = value.text.count { char -> char == '\n' } + 1
        if (line == null || line !in 1..count) vm.notify("Enter a line between 1 and $count") else {
            val offset = if (line == 1) 0 else value.text.withIndex().filter { char -> char.value == '\n' }[line - 2].index + 1
            value = value.copy(selection = TextRange(offset)); revealOffset = offset; gotoLine = false
        }
    }
}
