package dev.wfy.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable fun TerminalScreen(vm: WorkspaceModel, session: TerminalSession) {
    val context = LocalContext.current
    var status by remember(session.id) { mutableStateOf("Connecting…") }
    var bridge by remember { mutableStateOf<TerminalBridge?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var draft by rememberSaveable(session.id) { mutableStateOf("") }
    var multiline by rememberSaveable(session.id) { mutableStateOf(false) }
    var sendingDraft by remember(session.id) { mutableStateOf(false) }
    var sessionsMenu by remember { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val web = remember(session.id) {
        object : WebView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w > 0 && h > 0) post { evaluateJavascript("window.setViewport && window.setViewport($w,$h)", null) }
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.rgb(31, 31, 31))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    val uri = request?.url
                    if (uri?.host == "outpost.local" && uri.scheme == "https") {
                        val path = uri.path.orEmpty().removePrefix("/")
                        if (path in setOf("terminal.html", "xterm.js", "xterm.css", "addon-fit.js", "terminal-font.ttf")) {
                            val mime = when { path.endsWith(".js") -> "application/javascript"; path.endsWith(".css") -> "text/css"; path.endsWith(".ttf") -> "font/ttf"; else -> "text/html" }
                            return WebResourceResponse(mime, "UTF-8", context.assets.open(path))
                        }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
    }
    DisposableEffect(session.id, retry, vm.connectionGeneration, vm.connected) {
        val b = TerminalBridge(web, vm, session, { value -> web.post { status = value } }, { url -> web.post { loginUrl = url } }, { web.post { if (web.isAttachedToWindow) { inputFocus.requestFocus(); keyboard?.show() } } })
        bridge = b
        web.addJavascriptInterface(b, "Native")
        web.loadUrl("https://outpost.local/terminal.html")
        onDispose { b.close(); web.removeJavascriptInterface("Native") }
    }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
    BackHandler { vm.activeSession = null }
    fun submit() {
        if (sendingDraft) return
        if (!status.startsWith("Live")) { vm.notify("Terminal is reconnecting. Your input is retained."); return }
        val sent = draft
        if (sent.toByteArray(Charsets.UTF_8).size > 60 * 1024) { vm.notify("Send at most 60 KiB at once, or save a script in the editor."); return }
        if (sent.isEmpty()) { bridge?.input("\r"); return }
        val sender = bridge ?: return
        sendingDraft = true
        sender.pasteAndEnter(sent) { accepted ->
            sendingDraft = false
            if (accepted && draft == sent) draft = ""
            if (!accepted) vm.notify("Input could not be sent. Reconnect the terminal and retry.")
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().background(Panel), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.activeSession = null }) { Icon(Icons.Outlined.ArrowBack, "Leave terminal") }
            Column(Modifier.weight(1f)) { Text(session.title, fontSize = 16.sp, maxLines = 1); Text(status, color = Muted, fontSize = 12.sp) }
            IconButton(onClick = { if (!vm.connected) vm.connect() else retry++ }) { Icon(Icons.Outlined.Refresh, "Reconnect terminal") }
            Box {
                IconButton(onClick = { sessionsMenu = true }) { Icon(Icons.Outlined.UnfoldMore, "Switch terminal session") }
                DropdownMenu(expanded = sessionsMenu, onDismissRequest = { sessionsMenu = false }) {
                    vm.sessions.filter { it.server == vm.serverIdentity }.forEach { other ->
                        DropdownMenuItem(text = { Text(other.title + if (other.project.isNotEmpty()) " / ${other.project}" else "") }, enabled = other.id != session.id, onClick = {
                            sessionsMenu = false
                            if (draft.isNotBlank()) vm.notify("Send or clear the current message before switching sessions") else vm.resumeSession(other)
                        })
                    }
                }
            }
        }
        HorizontalDivider(color = Accent, thickness = 2.dp)
        AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (loginUrl != null && session.title.contains("login", true)) TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl))) }.onFailure { vm.notify("No browser available to open the login page") }
        }) { Text("Open sign-in page", fontSize = 14.sp) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.weight(1f).testTag("terminal-input").focusRequester(inputFocus).onPreviewKeyEvent { event ->
                if (event.key == Key.Enter && !event.isShiftPressed && !multiline) { if (event.type == KeyEventType.KeyDown) submit(); true } else false
            }, placeholder = { Text(if (multiline) "Paste a script or message" else "Type a command, then Enter", fontSize = 14.sp) }, singleLine = !multiline, maxLines = if (multiline) 4 else 1, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = if (multiline) ImeAction.Default else ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }))
            IconButton(onClick = { submit() }, enabled = draft.isNotEmpty() && status.startsWith("Live")) { Icon(Icons.Outlined.Send, "Send to terminal", Modifier.size(20.dp)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { submit() }, enabled = status.startsWith("Live")) { Text("ENTER", fontSize = 13.sp) }
            TextButton(onClick = { multiline = !multiline }) { Text(if (multiline) "SINGLE LINE" else "MULTILINE", fontSize = 12.sp) }
            listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL C" to "\u0003", "CTRL D" to "\u0004", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C").forEach { (label, value) -> TextButton(onClick = { bridge?.input(value) }, enabled = status.startsWith("Live"), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label, fontSize = 13.sp) } }
        }
    }
}

class TerminalBridge(private val web: WebView, private val vm: WorkspaceModel, private val session: TerminalSession, private val status: (String) -> Unit, private val loginLink: (String) -> Unit, private val focusComposer: () -> Unit) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var live = false
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @JavascriptInterface fun ready() {
        if (closed) return
        if (!vm.connected) { status("Waiting for SSH · tap reconnect"); return }
        web.post { web.evaluateJavascript("window.setFont(${vm.config.fontSize.toIntOrNull()?.coerceIn(10, 24) ?: 14});window.setViewport(${web.width},${web.height})", null) }
        socket = vm.api.client.newWebSocket(vm.api.request("/api/terminal/${session.id}").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { if (closed) { webSocket.close(1000, "Closed"); return }; socket = webSocket; live = true; status("Live · ${session.project.ifEmpty { "server" }}"); webSocket.send(obj("cols" to cols, "rows" to rows).toString()) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { detectLink(bytes.utf8()); output(bytes.base64()) }
            override fun onMessage(webSocket: WebSocket, text: String) { detectLink(text); output(Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { live = false; if (!closed) status("Disconnected · tap reconnect. Server session is preserved.") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { live = false; if (!closed) status("Session detached · tap reconnect") }
        })
    }
    private var loginBuffer = ""
    private fun detectLink(text: String) {
        if (!session.title.contains("login", true)) return
        loginBuffer = (loginBuffer + text).takeLast(16384)
        val plain = loginBuffer.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
        Regex("https://[^\\s\\u001B]+" ).findAll(plain).forEach { match ->
            val url = match.value.trimEnd('.', ')', ']')
            val host = runCatching { Uri.parse(url).host }.getOrNull()
            if (host in setOf("auth.openai.com", "chatgpt.com", "github.com", "claude.ai", "console.anthropic.com", "platform.claude.com")) loginLink(url)
        }
    }
    private fun output(base64: String) { if (!closed) web.post { if (!closed) web.evaluateJavascript("window.receive(${JSONObject.quote(base64)})", null) } }
    @JavascriptInterface fun input(data: String): Boolean = !closed && live && socket?.send(data.toByteArray(Charsets.UTF_8).toByteString()) == true
    @JavascriptInterface fun focusInput() { if (!closed) focusComposer() }
    fun pasteAndEnter(data: String, done: (Boolean) -> Unit) { web.post {
        if (closed || !live) { done(false); return@post }
        web.evaluateJavascript("window.sendDraft(${JSONObject.quote(data)})") { result -> done(result == "true") }
    } }
    @JavascriptInterface fun resize(c: Int, r: Int) { cols = c.coerceIn(10, 500); rows = r.coerceIn(2, 300); socket?.send(obj("cols" to cols, "rows" to rows).toString()) }
    fun close() { closed = true; live = false; socket?.close(1000, "Phone detached") }
}
