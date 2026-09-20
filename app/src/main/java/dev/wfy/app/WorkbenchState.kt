package dev.wfy.app

import java.security.MessageDigest

/** Identity excludes credentials; changing keys does not orphan drafts. */
fun workspaceIdentity(host: String, port: String, user: String, backendHost: String, backendPort: String): String =
    MessageDigest.getInstance("SHA-256").digest(listOf(host.trim(), port, user, backendHost, backendPort).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

data class WorkspaceBookmark(val server: String, val project: String, val pinned: Boolean = false, val opened: Long = 0)
fun workspaceOrder(bookmarks: Map<String, WorkspaceBookmark>): Comparator<String> =
    compareByDescending<String> { bookmarks[it]?.pinned == true }
        .thenByDescending { bookmarks[it]?.opened ?: 0L }
        .thenBy { it }
data class EditorDraft(val server: String, val project: String, val file: OpenFile)
fun afterSave(current: OpenFile, sent: OpenFile, revision: String): OpenFile = current.copy(original = sent.content, revision = revision)
data class ServiceForward(val localPort: Int, val host: String, val port: Int, val label: String) {
    val url get() = "http://127.0.0.1:$localPort"
}

fun lineOffset(text: String, requested: Int): Int {
    if (requested <= 1) return 0
    var line = 1
    text.forEachIndexed { index, char -> if (char == '\n' && ++line == requested) return index + 1 }
    return text.length
}
