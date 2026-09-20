package dev.wfy.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
data class HostPrompt(val fingerprint: String, val changed: Boolean, val result: CompletableDeferred<Boolean>)
data class TerminalSession(val id: String, val title: String, val project: String, val host: String, val server: String = "")
data class OpenFile(val path: String, val original: String, val content: String, val revision: String, val branch: String = "")

class WorkspaceModel(app: Application) : AndroidViewModel(app) {
    private val vault = Vault(app)
    var config by mutableStateOf(ConnectionConfig()); private set
    val tunnel = Tunnel(vault)
    val api = Api(tunnel) { config.token }
    val assistants = AssistantModel(api, vault, viewModelScope, { serverIdentity }, { connected })
    private val navigation = runCatching { JSONObject(vault.read("navigation") ?: "{}") }.getOrDefault(JSONObject())
    private val navigationWrites = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var externalRevision = ""
    var connected by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    var connectionGeneration by mutableStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var hostPrompt by mutableStateOf<HostPrompt?>(null); private set
    var projects by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var project by mutableStateOf(""); private set
    var directory by mutableStateOf(""); private set
    var entries by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var recentFiles by mutableStateOf<List<String>>(emptyList()); private set
    var searchResults by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var file by mutableStateOf<OpenFile?>(null); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private val undoEdits = ArrayDeque<String>()
    private val redoEdits = ArrayDeque<String>()
    private var lastEditTime = 0L
    var git by mutableStateOf(JSONObject()); private set
    var serverSettings by mutableStateOf(JSONObject()); private set
    var sessions by mutableStateOf<List<TerminalSession>>(emptyList()); private set
    var activeSession by mutableStateOf<TerminalSession?>(null)
    var tab by mutableStateOf(0)
    var settingsSection by mutableStateOf("Connection")
    var gitSection by mutableStateOf("Changes")
    var pullFilter by mutableStateOf("open")
    var pullQuery by mutableStateOf("")
    var pulls by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var selectedPull by mutableStateOf<JSONObject?>(null); private set
    var pullDiff by mutableStateOf<JSONObject?>(null); private set
    var localDiff by mutableStateOf<JSONObject?>(null); private set
    var reviewLoading by mutableStateOf(false); private set
    var reviewError by mutableStateOf<String?>(null); private set
    private var reviewJob: Job? = null
    var lastSync by mutableStateOf(""); private set
    private var reconnect = false
    private var connectionJob: Job? = null
    private var refreshRevision = 0L
    val serverIdentity get() = workspaceIdentity(config.host, config.port, config.user, config.backendHost, config.backendPort) + config.pairedHostId.takeIf { it.isNotEmpty() }?.let { "|laptop:$it" }.orEmpty()
    var bookmarks by mutableStateOf<List<WorkspaceBookmark>>(emptyList()); private set
    var drafts by mutableStateOf<List<EditorDraft>>(emptyList()); private set
    var forwards by mutableStateOf<List<ServiceForward>>(emptyList()); private set
    var editorLine by mutableStateOf<Int?>(null)
    var showSearch by mutableStateOf(false)
    var showRecovery by mutableStateOf(false)
    var showPorts by mutableStateOf(false)
    var history by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var historyMore by mutableStateOf(false); private set
    var historyPath by mutableStateOf("")
    var stashes by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var inspectedChange by mutableStateOf<JSONObject?>(null); private set
    private val snapshots = kotlinx.coroutines.channels.Channel<Pair<List<WorkspaceBookmark>, List<EditorDraft>>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val discovery = HostDiscovery(vault, viewModelScope) { host -> if (!connected && !connecting && !busy) connectPairedHost(host) }
    fun connectPairedHost(host: PairedHost) {
        if (busy || connecting) { message = "Wait for the current operation to finish"; return }
        if (!host.online) { message = "Laptop is offline. Start hosting, then refresh Hosts."; return }
        discovery.selected(host); saveConfig(host.connection(config), connectAfter = true)
    }
    init {
        viewModelScope.launch { for (snapshot in navigationWrites) { try { withContext(Dispatchers.IO) { vault.write("navigation", snapshot) } } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "Could not save workspace navigation" } } }
        runCatching {
            config = ConnectionConfig.from(JSONObject(vault.read("connection") ?: "{}"))
            sessions = JSONArray(vault.read("sessions") ?: "[]").objects().map { TerminalSession(it.getString("id"), it.getString("title"), it.optString("project"), it.optString("host"), it.optString("server")) }.filter { it.server.isNotBlank() }
            val saved = JSONObject(vault.read("workbench") ?: "{}")
            bookmarks = saved.optJSONArray("bookmarks")?.objects().orEmpty().map { WorkspaceBookmark(it.getString("server"), it.getString("project"), it.optBoolean("pinned"), it.optLong("opened")) }
            drafts = saved.optJSONArray("drafts")?.objects().orEmpty().map { EditorDraft(it.getString("server"), it.getString("project"), OpenFile(it.getString("path"), it.getString("original"), it.getString("content"), it.getString("revision"), it.optString("branch"))) }
        }.onFailure { message = "Saved credentials could not be decrypted. Re-enter your connection settings." }
        // One writer serializes snapshots; an old delayed write cannot resurrect a discarded draft.
        viewModelScope.launch {
            for (first in snapshots) {
                delay(250)
                var latest = first
                while (true) { latest = snapshots.tryReceive().getOrNull() ?: break }
                try {
                    withContext(Dispatchers.IO) {
                        val saved = obj("bookmarks" to JSONArray().apply { latest.first.forEach { put(obj("server" to it.server, "project" to it.project, "pinned" to it.pinned, "opened" to it.opened)) } },
                            "drafts" to JSONArray().apply { latest.second.forEach { put(obj("server" to it.server, "project" to it.project, "path" to it.file.path, "original" to it.file.original, "content" to it.file.content, "revision" to it.file.revision, "branch" to it.file.branch)) } })
                        vault.write("workbench", saved.toString())
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = "Draft backup could not be saved: ${e.message}" }
            }
        }
        viewModelScope.launch {
            var failures = 0
            while (true) {
                delay((config.syncSeconds.toLongOrNull()?.coerceIn(3, 60) ?: 5) * 1000)
                if (connected && !tunnel.connected) { connected = false; forwards = emptyList() }
                if (connected && !busy && activeSession == null) {
                    try { refresh(); failures = 0 }
                    catch (e: CancellationException) { throw e }
                    catch (e: ApiFailure) { message = "Workspace sync: ${e.message}"; if (e.status == 401 || e.status == 403) { reconnect = false; connected = false; forwards = emptyList(); tunnel.close() } }
                    catch (_: Exception) { failures++; if (failures >= 2) { connected = false; forwards = emptyList(); message = "Connection interrupted. Your server sessions continue running." } }
                }
                if (reconnect && !connected && !connecting) connect(automatic = true)
            }
        }
    }
    fun notify(text: String) { message = text }
    fun saveConfig(c: ConnectionConfig, connectAfter: Boolean = false) = action {
        require(c.fontSize.toIntOrNull() in 10..24) { "Choose an editor font size between 10 and 24" }
        require(c.syncSeconds.toIntOrNull() in 3..60) { "Choose a sync interval between 3 and 60 seconds" }
        require(c.uiScale.toFloatOrNull()?.let { it in .9f..1.3f } == true) { "Choose an interface text scale between 0.9 and 1.3" }
        val previousIdentity = serverIdentity
        if ((connected || connecting) && c.copy(fontSize = config.fontSize, syncSeconds = config.syncSeconds, keepScreenOn = config.keepScreenOn, uiScale = config.uiScale) != config) disconnect()
        config = c
        if (previousIdentity != serverIdentity) { assistants.reset(); resetReview(); project = ""; projects = emptyList(); entries = emptyList(); directory = ""; file = null; recentFiles = emptyList(); git = JSONObject(); serverSettings = JSONObject(); lastSync = "" }
        withContext(Dispatchers.IO) { vault.write("connection", c.json().toString()) }
        if (connectAfter) connect() else message = "Connection settings saved securely"
    }
    fun connect(automatic: Boolean = false) {
        if (connecting) return
        forwards = emptyList(); connected = false; connectionGeneration++
        var attempt = connectionGeneration
        connecting = true
        connectionJob = viewModelScope.launch {
            try {
                tunnel.connect(config) { fp, changed ->
                    val answer = CompletableDeferred<Boolean>()
                    withContext(Dispatchers.Main) { if (attempt == connectionGeneration) hostPrompt = HostPrompt(fp, changed, answer) else answer.complete(false) }
                    val accepted = answer.await()
                    withContext(Dispatchers.Main) { if (hostPrompt?.result === answer) hostPrompt = null; if (!accepted && attempt == connectionGeneration) reconnect = false }
                    accepted
                }
                api.call("/api/health")
                connectionGeneration++; attempt = connectionGeneration
                val savedNavigation = navigation.optJSONObject(serverIdentity)
                val restore = project.isBlank()
                if (restore) { project = savedNavigation?.optString("project").orEmpty(); directory = savedNavigation?.optString("directory").orEmpty() }
                refresh(allowConnecting = true); serverSettings = JSONObject(api.call("/api/settings"))
                if (restore && project.isNotEmpty()) {
                    val path = savedNavigation?.optString("path").orEmpty()
                    if (path.isNotEmpty()) {
                        try { openFile(path) } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "Workspace reopened; the previous file is no longer available." }
                    } else tab = 1
                }
                connected = true; reconnect = true
                message = "Connected securely to ${config.host}"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == connectionGeneration) { connected = false; tunnel.close(); message = e.message ?: "Connection failed"; if (!automatic) reconnect = false } }
            finally { if (attempt == connectionGeneration) connecting = false }
        }
    }
    fun disconnect() { reconnect = false; connected = false; activeSession = null; forwards = emptyList(); hostPrompt?.result?.complete(false); hostPrompt = null; connectionJob?.cancel(); connecting = false; tunnel.close(); connectionGeneration++ }
    fun action(block: suspend () -> Unit) {
        if (busy) { message = "Please wait for the current operation to finish"; return }
        busy = true; message = null
        viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message ?: "Operation failed" } finally { busy = false } }
    }
    suspend fun refresh(allowConnecting: Boolean = false) {
        val revision = ++refreshRevision
        val generation = connectionGeneration
        val workspace = project
        val folder = directory
        val updatedProjects = JSONArray(api.call("/api/projects")).objects()
        val host = config.host + ":" + config.port
        val server = serverIdentity
        val remoteSessions = JSONArray(api.call("/api/sessions")).objects().map { TerminalSession(it.getString("id"), it.getString("title"), it.optString("project"), host, server) }
        var updatedEntries = emptyList<JSONObject>()
        var updatedGit = JSONObject()
        val capturedFile = file
        var updatedFile: JSONObject? = null
        var fileNotice: String? = null
        var resolvedFolder = folder
        val exists = updatedProjects.any { it.optString("name") == workspace }
        if (workspace.isNotBlank() && exists) {
            try { updatedEntries = JSONArray(api.call("/api/tree?project=${enc(workspace)}&path=${enc(folder)}")).objects() }
            catch (e: ApiFailure) {
                if (folder.isBlank() || e.status !in listOf(400, 404)) throw e
                updatedEntries = JSONArray(api.call("/api/tree?project=${enc(workspace)}")).objects(); resolvedFolder = ""
            }
            updatedGit = JSONObject(api.call("/api/git?project=${enc(workspace)}"))
            if (capturedFile != null) {
                try { updatedFile = JSONObject(api.call("/api/file?project=${enc(workspace)}&path=${enc(capturedFile.path)}")) }
                catch (e: ApiFailure) { if (e.status in listOf(400, 404, 413, 415)) fileNotice = "The open file moved, was deleted, or is no longer editable on the host. Your editor content is retained." else throw e }
            }
        }
        if (revision != refreshRevision || generation != connectionGeneration || workspace != project || folder != directory || (!connected && !allowConnecting)) return
        projects = updatedProjects; sessions = sessions.filter { it.server != server } + remoteSessions
        if (workspace.isNotBlank()) {
            entries = updatedEntries; git = updatedGit; directory = resolvedFolder
            if (!exists) { message = "Workspace no longer exists on the server. Local drafts are retained."; if (file == null) { project = ""; directory = ""; tab = 0 } }
            else if (resolvedFolder != folder) message = "That folder moved or was deleted. Showing the workspace root."
        }
        if (capturedFile != null && file == capturedFile) {
            val remote = updatedFile
            if (remote != null && (remote.optString("revision") != capturedFile.revision || remote.optString("branch") != capturedFile.branch)) {
                if (capturedFile.content == capturedFile.original) {
                    file = OpenFile(capturedFile.path, remote.getString("content"), remote.getString("content"), remote.getString("revision"), remote.optString("branch"))
                    undoEdits.clear(); redoEdits.clear(); updateUndoState(); externalRevision = ""
                } else if (externalRevision != remote.optString("revision") + remote.optString("branch")) {
                    externalRevision = remote.optString("revision") + remote.optString("branch")
                    message = "This file changed on the laptop. Your unsaved edits are retained; review before saving."
                }
            }
            if (fileNotice != null && externalRevision != "unavailable") { message = fileNotice; externalRevision = "unavailable" }
        }
        lastSync = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    }
    fun refreshNow() = action { refresh() }
    fun selectProject(name: String, destination: Int = 1) = action { resetReview(); project = name; directory = ""; file = null; entries = emptyList(); git = JSONObject(); recentFiles = emptyList(); searchResults = emptyList(); rememberWorkspace(name); rememberNavigation(); refresh(); if (project == name) tab = destination }
    fun clone(name: String, url: String) = action { api.call("/api/projects", "POST", obj("name" to name.trim(), "url" to url.trim())); refresh(); message = "Repository cloned" }
    fun worktree(name: String, branch: String, migration: Boolean) = action { val result = JSONObject(api.call("/api/worktree", "POST", obj("project" to project, "name" to name, "branch" to branch, "kind" to if (migration) "migration" else "feature"))); resetReview(); project = result.getString("name"); directory = ""; recentFiles = emptyList(); searchResults = emptyList(); rememberWorkspace(project); refresh(); message = "Separate workspace created" }
    fun browse(path: String) = action { ++refreshRevision; val listing = JSONArray(api.call("/api/tree?project=${enc(project)}&path=${enc(path)}")).objects(); directory = path; entries = listing; rememberNavigation() }
    fun open(path: String, line: Int? = null, discardDraft: Boolean = false) = action { openFile(path, line, discardDraft) }
    private suspend fun openFile(path: String, line: Int? = null, discardDraft: Boolean = false) {
        val draft = drafts.firstOrNull { it.server == serverIdentity && it.project == project && it.file.path == path }
        if (draft != null && !discardDraft) {
            file = draft.file; message = "Recovered unsaved edits. Save checks whether the server file has changed."
        } else {
            val j = JSONObject(api.call("/api/file?project=${enc(project)}&path=${enc(path)}"))
            file = OpenFile(path, j.getString("content"), j.getString("content"), j.getString("revision"), j.optString("branch"))
            if (discardDraft) discardDraftFor(project, path)
        }
        editorLine = line; undoEdits.clear(); redoEdits.clear(); updateUndoState(); lastEditTime = 0L
        recentFiles = (listOf(path) + recentFiles.filter { it != path }).take(8)
        activeSession = null; assistants.close(); externalRevision = ""; tab = 1; rememberNavigation()
    }
    fun search(query: String) = action { searchResults = JSONArray(api.call("/api/search?project=${enc(project)}&q=${enc(query)}")).objects() }
    private fun review(block: suspend () -> Unit) {
        reviewJob?.cancel()
        reviewLoading = true; reviewError = null
        reviewJob = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { reviewError = e.message ?: "Could not load review" }
            finally { if (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) reviewLoading = false }
        }
    }
    private fun resetReview() { reviewJob?.cancel(); reviewLoading = false; pulls = emptyList(); selectedPull = null; pullDiff = null; localDiff = null; reviewError = null; history = emptyList(); stashes = emptyList(); inspectedChange = null; historyPath = "" }
    fun loadPulls() = review {
        selectedPull = null; pullDiff = null
        pulls = JSONArray(api.call("/api/pulls?project=${enc(project)}&state=${enc(pullFilter)}&q=${enc(pullQuery)}")).objects()
    }
    fun openPull(number: Int) = review {
        pullDiff = null
        selectedPull = JSONObject(api.call("/api/pulls/$number?project=${enc(project)}"))
    }
    fun loadPullDiff() {
        val number = selectedPull?.optInt("number") ?: return
        review { pullDiff = JSONObject(api.call("/api/pulls/$number/diff?project=${enc(project)}")) }
    }
    fun closePull() { reviewJob?.cancel(); reviewLoading = false; selectedPull = null; pullDiff = null; reviewError = null }
    fun openDiff(path: String, staged: Boolean) = review {
        localDiff = obj("path" to path, "staged" to staged)
        val result = JSONObject(api.call("/api/git/diff?project=${enc(project)}&path=${enc(path)}&staged=$staged"))
        result.put("path", path); result.put("staged", staged); localDiff = result
    }
    fun closeDiff() { reviewJob?.cancel(); reviewLoading = false; localDiff = null; reviewError = null }
    fun saveTask(name: String, command: String) {
        val s = JSONObject(serverSettings.toString())
        val tasks = s.optJSONObject("tasks") ?: JSONObject()
        tasks.put(name, command); s.put("tasks", tasks); saveServerSettings(s)
    }
    fun deleteTask(name: String) {
        val s = JSONObject(serverSettings.toString()); val tasks = s.optJSONObject("tasks") ?: JSONObject()
        tasks.remove(name); s.put("tasks", tasks); saveServerSettings(s)
    }
    private fun updateUndoState() { canUndo = undoEdits.isNotEmpty(); canRedo = redoEdits.isNotEmpty() }
    fun edit(text: String) {
        val current = file ?: return
        if (current.content == text) return
        val now = android.os.SystemClock.uptimeMillis()
        if (undoEdits.isEmpty() || now - lastEditTime > 500 || kotlin.math.abs(text.length - current.content.length) > 1) {
            undoEdits.addLast(current.content)
            while (undoEdits.size > 40 || (undoEdits.size > 1 && undoEdits.sumOf { it.length.toLong() * 2 } > 8L * 1024 * 1024)) undoEdits.removeFirst()
        }
        redoEdits.clear(); lastEditTime = now
        file = current.copy(content = text); updateUndoState(); backupDraft()
    }
    fun undoEdit() { val current = file ?: return; if (undoEdits.isNotEmpty()) { redoEdits.addLast(current.content); file = current.copy(content = undoEdits.removeLast()); lastEditTime = 0L; updateUndoState(); backupDraft() } }
    fun redoEdit() { val current = file ?: return; if (redoEdits.isNotEmpty()) { undoEdits.addLast(current.content); file = current.copy(content = redoEdits.removeLast()); lastEditTime = 0L; updateUndoState(); backupDraft() } }
    fun closeFile(discard: Boolean = false) { if (discard) file?.let { discardDraftFor(project, it.path) }; file = null; undoEdits.clear(); redoEdits.clear(); updateUndoState(); rememberNavigation() }
    fun saveFile() = action {
        val f = file ?: return@action
        val workspace = project
        val server = serverIdentity
        val j = JSONObject(api.call("/api/file", "PUT", obj("project" to workspace, "path" to f.path, "content" to f.content, "revision" to f.revision, "branch" to f.branch)))
        // Typing can continue while the request is in flight. Only advance the saved baseline.
        val revision = j.getString("revision")
        if (server == serverIdentity && workspace == project) {
            file?.takeIf { it.path == f.path && it.branch == f.branch }?.let { current ->
                file = afterSave(current, f, revision)
            }
        }
        // The editor may have been closed while saving. Advance its retained draft too.
        drafts = drafts.map { draft -> if (draft.server == server && draft.project == workspace && draft.file.path == f.path && draft.file.branch == f.branch) draft.copy(file = afterSave(draft.file, f, revision)) else draft }.filter { it.file.content != it.file.original }
        persistWorkbench()
        message = "Saved on server"; refresh()
    }
    fun newFile(path: String) = action { api.call("/api/file", "PUT", obj("project" to project, "path" to path, "content" to "")); refresh(); message = "File created" }
    fun fileAction(action: String, path: String, target: String = "") = action { api.call("/api/files", "POST", obj("project" to project, "action" to action, "path" to path, "target" to target)); refresh(); message = if (action == "delete") "Moved to server recovery folder" else "Files updated" }
    fun gitAction(action: String, extras: JSONObject = JSONObject()) = action { extras.put("project", project); extras.put("action", action); val j = JSONObject(api.call("/api/git", "POST", extras)); message = j.optString("output").ifBlank { "Git operation completed" }; refresh() }
    fun saveServerSettings(s: JSONObject) = action { api.call("/api/settings", "PUT", s); serverSettings = s; message = "Server settings saved. New sessions use the updated environment." }
    private suspend fun persistSessions() = withContext(Dispatchers.IO) { vault.write("sessions", JSONArray().apply { sessions.forEach { put(obj("id" to it.id, "title" to it.title, "project" to it.project, "host" to it.host, "server" to it.server)) } }.toString()) }
    fun startAssistant(provider: String, fresh: Boolean = false, threadId: String = "", mode: String = "workspace-write") = action {
        activeSession = null; assistants.start(provider, project, fresh, threadId, mode); tab = 3
    }
    fun openAssistant(id: String) = action { activeSession = null; assistants.open(id); tab = 3 }
    fun openAssistantTerminal(chat: AssistantChat) {
        if (chat.running || chat.threadId.isBlank()) return
        val command = if (chat.provider == "codex") "codex resume ${chat.threadId}" else "claude --resume ${chat.threadId}"
        action { createTerminal("task", "${chat.provider} resume", command, chat.project) }
    }
    fun startSession(kind: String, title: String, command: String = "") {
        if (kind == "codex" || kind == "claude") { startAssistant(kind); return }
        action { createTerminal(kind, title, command, project) }
    }
    private suspend fun createTerminal(kind: String, title: String, command: String, workspace: String) {
        assistants.close()
        val j = JSONObject(api.call("/api/session", "POST", obj("project" to workspace, "kind" to kind, "title" to title, "command" to command)))
        val s = TerminalSession(j.getString("id"), title, workspace, config.host + ":" + config.port, serverIdentity)
        sessions = sessions + s; persistSessions(); activeSession = s; tab = 3
    }
    fun resumeSession(s: TerminalSession) = action {
        require(s.server == serverIdentity) { "Connect to the original SSH user and backend at ${s.host} to resume this session" }
        api.call("/api/session", "POST", obj("project" to s.project, "id" to s.id, "kind" to "shell"))
        assistants.close(); activeSession = s
    }
    fun forgetSession(s: TerminalSession) = action { sessions = sessions - s; persistSessions(); message = "Removed from this phone. The server session is unchanged." }
    fun endSession(s: TerminalSession) = action {
        require(s.server == serverIdentity) { "Connect to the original SSH user and backend at ${s.host} to end this session" }
        api.call("/api/session/end", "POST", obj("id" to s.id)); sessions = sessions - s; persistSessions(); if (activeSession == s) activeSession = null; message = "Session ended"
    }
    private fun rememberNavigation() {
        navigation.put(serverIdentity, obj("project" to project, "directory" to directory, "path" to file?.path.orEmpty()))
        navigationWrites.trySend(navigation.toString())
    }
    private fun persistWorkbench() { snapshots.trySend(bookmarks.toList() to drafts.toList()) }
    fun togglePin(name: String) {
        val old = bookmarks.firstOrNull { it.server == serverIdentity && it.project == name } ?: WorkspaceBookmark(serverIdentity, name)
        bookmarks = bookmarks.filterNot { it.server == serverIdentity && it.project == name } + old.copy(pinned = !old.pinned)
        persistWorkbench()
    }
    private fun rememberWorkspace(name: String) {
        val old = bookmarks.firstOrNull { it.server == serverIdentity && it.project == name } ?: WorkspaceBookmark(serverIdentity, name)
        bookmarks = bookmarks.filterNot { it.server == serverIdentity && it.project == name } + old.copy(opened = System.currentTimeMillis())
        persistWorkbench()
    }
    private fun backupDraft() {
        val f = file ?: return
        drafts = drafts.filterNot { it.server == serverIdentity && it.project == project && it.file.path == f.path } + if (f.content != f.original) listOf(EditorDraft(serverIdentity, project, f)) else emptyList()
        persistWorkbench()
    }
    fun discardDraftFor(workspace: String, path: String) { drafts = drafts.filterNot { it.server == serverIdentity && it.project == workspace && it.file.path == path }; persistWorkbench() }
    fun restoreDraft(draft: EditorDraft) = action {
        require(draft.server == serverIdentity) { "Connect to the original server to recover this draft" }
        resetReview(); project = draft.project; directory = ""; file = draft.file; editorLine = null; recentFiles = listOf(draft.file.path)
        undoEdits.clear(); redoEdits.clear(); updateUndoState(); rememberWorkspace(project); tab = 1
        message = "Recovered local draft. Save checks the server revision before writing."
    }
    fun loadHistory(more: Boolean = false) = review {
        val ref = if (more) history.firstOrNull()?.optString("id").orEmpty() else ""
        val page = JSONArray(api.call("/api/git/history?project=${enc(project)}&skip=${if (more) history.size else 0}&ref=${enc(ref)}&path=${enc(historyPath.trim())}")).objects()
        history = if (more) (history + page).distinctBy { it.optString("id") } else page
        historyMore = page.size == 40 && history.size < 10000
    }
    fun loadStashes() = review { stashes = JSONArray(api.call("/api/git/stashes?project=${enc(project)}")).objects() }
    fun inspectChange(item: JSONObject, stash: Boolean) = review {
        inspectedChange = obj("title" to item.optString("title"), "id" to item.getString("id"), "stash" to stash, "kind" to item.optString("kind"))
        val result = JSONObject(api.call("/api/git/${if (stash) "stash" else "commit"}?project=${enc(project)}&id=${enc(item.getString("id"))}"))
        result.put("title", item.optString("title")); result.put("id", item.getString("id")); result.put("stash", stash); result.put("kind", item.optString("kind")); inspectedChange = result
    }
    fun closeInspection() { reviewJob?.cancel(); reviewLoading = false; reviewError = null; inspectedChange = null }
    fun applyStash(id: String) = action {
        api.call("/api/git/stash/apply", "POST", obj("project" to project, "id" to id))
        closeInspection(); gitSection = "Changes"; refresh(); message = "Stash applied. The saved stash is retained."
    }
    fun addForward(host: String, port: String, label: String) = action {
        val remotePort = port.toIntOrNull() ?: error("Enter a numeric port")
        val generation = connectionGeneration
        val local = withContext(Dispatchers.IO) { tunnel.forward(host.trim(), remotePort) }
        check(connected && generation == connectionGeneration) { "Connection changed; open the port again" }
        forwards = forwards + ServiceForward(local, host.trim(), remotePort, label.trim().ifBlank { "Port $remotePort" })
    }
    fun closeForward(forward: ServiceForward) = action { withContext(Dispatchers.IO) { tunnel.removeForward(forward.localPort) }; forwards = forwards - forward }
    override fun onCleared() { disconnect(); super.onCleared() }
}
