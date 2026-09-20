package dev.wfy.app

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(val id: String, val role: String, val text: String)
data class AssistantChat(val id: String, val provider: String, val project: String, val title: String, val threadId: String, val mode: String, val running: Boolean, val status: String, val error: String, val messages: List<ChatMessage>) {
    companion object {
        fun from(j: JSONObject) = AssistantChat(j.getString("id"), j.getString("provider"), j.getString("project"), j.getString("title"), j.optString("threadId"), j.optString("mode", "workspace-write"), j.optBoolean("running"), j.optString("status"), j.optString("error"), j.optJSONArray("messages")?.objects().orEmpty().map { ChatMessage(it.getString("id"), it.getString("role"), it.getString("text")) })
    }
}

class AssistantModel(private val api: Api, private val vault: Vault, private val scope: CoroutineScope, private val identity: () -> String, private val connected: () -> Boolean) {
    var chats by mutableStateOf<List<AssistantChat>>(emptyList()); private set
    var providers by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var active by mutableStateOf<AssistantChat?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var draft by mutableStateOf(""); private set
    private val drafts = runCatching { JSONObject(vault.read("assistant-drafts") ?: "{}") }.getOrDefault(JSONObject())
    private val requests = drafts.optJSONObject("_requests") ?: JSONObject().also { drafts.put("_requests", it) }
    private var pendingRequest: String? = null
    private var pendingText = ""
    private val draftWrites = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var generation = 0
    init {
        scope.launch {
            for (snapshot in draftWrites) try { withContext(Dispatchers.IO) { vault.write("assistant-drafts", snapshot) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not back up the message draft on this phone" }
        }
        scope.launch {
            while (true) {
                delay(1500)
                val chat = active ?: continue
                if (!connected() || loading || sending) continue
                val gen = generation
                try {
                    val updated = AssistantChat.from(JSONObject(api.call("/api/ai/chats/${chat.id}")))
                    if (gen == generation && active?.id == chat.id) { active = updated; error = null }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (gen == generation) error = "Chat sync: ${e.message}. Your reply continues on the host." }
            }
        }
    }
    private fun key(id: String) = identity() + "|" + id
    fun editDraft(text: String) {
        draft = text
        val chat = active ?: return
        if (text.isEmpty()) drafts.remove(key(chat.id)) else drafts.put(key(chat.id), text)
        draftWrites.trySend(drafts.toString())
    }
    fun close() { generation++; active = null; error = null; loading = false }
    fun reset() { close(); chats = emptyList(); providers = emptyList() }
    suspend fun refresh(project: String, checkProviders: Boolean = false) {
        val gen = generation; val host = identity()
        val updated = JSONArray(api.call("/api/ai/chats?project=${enc(project)}")).objects().map(AssistantChat::from)
        if (host == identity() && gen == generation) chats = updated
        if (checkProviders) {
            val status = JSONArray(api.call("/api/ai/providers")).objects()
            if (host == identity() && gen == generation) providers = status
        }
    }
    suspend fun open(id: String) {
        generation++; val gen = generation; loading = true; error = null
        try {
            val chat = AssistantChat.from(JSONObject(api.call("/api/ai/chats/$id")))
            if (gen == generation) {
                active = chat; draft = drafts.optString(key(id))
                val pending = requests.optJSONObject(key(id))?.takeIf { it.optString("text") == draft }
                pendingRequest = pending?.optString("id"); pendingText = pending?.optString("text").orEmpty()
            }
        } finally { if (gen == generation) loading = false }
    }
    suspend fun start(provider: String, project: String, fresh: Boolean = false, threadId: String = "", mode: String = "workspace-write") {
        require(project.isNotBlank()) { "Open a workspace before starting a chat" }
        refresh(project)
        val previous = chats.firstOrNull { it.provider == provider }
        if (!fresh && threadId.isBlank() && previous != null) { open(previous.id); return }
        val chat = AssistantChat.from(JSONObject(api.call("/api/ai/chats", "POST", obj("provider" to provider, "project" to project, "threadId" to threadId.trim(), "mode" to mode))))
        chats = listOf(chat) + chats; open(chat.id)
    }
    fun send() {
        val chat = active ?: return
        if (sending || chat.running || draft.isBlank()) return
        if (!connected()) { error = "Reconnect to your host before sending. Your message is saved."; return }
        val text = draft
        if (pendingRequest == null || pendingText != text) { pendingRequest = UUID.randomUUID().toString(); pendingText = text }
        val request = pendingRequest!!; val gen = generation; sending = true; error = null
        requests.put(key(chat.id), obj("id" to request, "text" to text)); draftWrites.trySend(drafts.toString())
        scope.launch {
            try {
                val result = AssistantChat.from(JSONObject(api.call("/api/ai/chats/${chat.id}/message", "POST", obj("message" to text, "requestId" to request))))
                if (gen == generation && active?.id == chat.id) {
                    active = result; requests.remove(key(chat.id)); if (draft == text) editDraft(""); pendingRequest = null; pendingText = ""; draftWrites.trySend(drafts.toString())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (gen == generation) error = "${e.message}. Your message is retained; retrying will not send it twice." }
            finally { sending = false }
        }
    }
    fun stop() {
        val chat = active ?: return
        scope.launch { try { api.call("/api/ai/chats/${chat.id}/stop", "POST", obj()) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    }
    suspend fun remove(chat: AssistantChat) {
        api.call("/api/ai/chats/${chat.id}", "DELETE")
        chats = chats.filterNot { it.id == chat.id }; if (active?.id == chat.id) close()
        drafts.remove(key(chat.id)); requests.remove(key(chat.id)); draftWrites.trySend(drafts.toString())
    }
}
