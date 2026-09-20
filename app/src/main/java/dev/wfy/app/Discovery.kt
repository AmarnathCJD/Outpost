package dev.wfy.app

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun encode64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
private fun decode64(value: String) = Base64.decode(value, Base64.NO_WRAP)

fun discoveryRelay(value: String): String {
    var text = value.trim().replaceFirst(Regex("^wss://"), "https://")
    if (!text.contains("://")) text = "https://$text"
    val url = text.toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use an HTTPS relay address without credentials or a query" }
    var path = url.encodedPath.trimEnd('/')
    if (path.endsWith("/laptop") || path.endsWith("/phone")) path = path.substringBeforeLast('/')
    if (path.isBlank()) path = "/.well-known/outpost-relay"
    return url.newBuilder().encodedPath(path).build().toString().trimEnd('/')
}

fun readPairingInvitation(value: String): Pair<String, String> {
    require(value.length <= 4096 && value.startsWith("outpost://pair#")) { "Scan or paste an Outpost pairing invitation from the laptop" }
    val json = JSONObject(String(Base64.decode(value.substringAfter('#'), Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
    return discoveryRelay(json.getString("relay")) to json.getString("phrase")
}

data class PairedHost(val relay: String, val pairId: String, val key: String, val profile: String, val online: Boolean = false, val lastSeen: Long = 0, val error: String = "") {
    val details get() = JSONObject(profile)
    val id get() = details.getString("hostId")
    val name get() = details.getString("name")
    val identity get() = "$relay|$id"
    fun json() = obj("relay" to relay, "pairId" to pairId, "key" to key, "profile" to profile, "lastSeen" to lastSeen)
    fun connection(previous: ConnectionConfig): ConnectionConfig {
        val data = details
        val url = relay.toHttpUrl()
        val phone = url.newBuilder().addPathSegment("phone").addQueryParameter("host", id).build().toString().replaceFirst("https://", "wss://")
        return previous.copy(host = url.host, port = url.port.toString(), user = data.getString("user"), password = data.getString("token"), token = data.getString("token"), useKey = false, key = "", passphrase = "", backendHost = "127.0.0.1", backendPort = data.getString("backendPort"), relayUrl = phone, relayAccess = pairId, pairedHostId = id, pairedPublicKey = data.getString("publicKey"), hostLabel = name)
    }
}

class HostDiscovery(private val vault: Vault, private val scope: CoroutineScope, private val automaticConnect: (PairedHost) -> Unit) {
    var hosts by mutableStateOf<List<PairedHost>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var pairing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var autoConnect by mutableStateOf(false); private set
    private var lastHost = ""
    private var revision = 0
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    init {
        runCatching {
            val saved = JSONObject(vault.read("paired-hosts") ?: "{}")
            hosts = saved.optJSONArray("hosts")?.objects().orEmpty().take(32).map { PairedHost(it.getString("relay"), it.getString("pairId"), it.getString("key"), it.getString("profile"), lastSeen = it.optLong("lastSeen")) }
            autoConnect = saved.optBoolean("autoConnect"); lastHost = saved.optString("lastHost")
        }.onFailure { error = "Saved laptop pairings could not be read. Pair your laptop again." }
        scope.launch { refreshNow(); if (autoConnect) hosts.firstOrNull { it.identity == lastHost && it.online }?.let(automaticConnect) }
    }
    private fun persist() {
        runCatching { vault.write("paired-hosts", obj("hosts" to JSONArray().apply { hosts.forEach { put(it.json()) } }, "autoConnect" to autoConnect, "lastHost" to lastHost).toString()) }.onFailure { error = "Pairing changes could not be saved securely. Try again." }
    }
    fun setAutomatic(value: Boolean) { autoConnect = value; persist() }
    fun selected(host: PairedHost) { lastHost = host.identity; persist() }
    fun forget(host: PairedHost) { revision++; hosts = hosts.filterNot { it.identity == host.identity }; if (lastHost == host.identity) lastHost = ""; persist() }
    fun refresh() { scope.launch { refreshNow() } }
    private suspend fun query(relay: String, ids: List<String>): List<JSONObject> {
        val request = Request.Builder().url("$relay/discover").post(obj("ids" to JSONArray(ids)).toString().toRequestBody("application/json".toMediaType())).build()
        return JSONArray(executeRequest(client, request)).objects()
    }
    private fun decrypt(relay: String, pairId: String, key: String, record: JSONObject): PairedHost {
        require(record.getString("pairId") == pairId) { "Pairing identity does not match" }
        val id = record.getString("hostId")
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid laptop identity" }
        val blob = decode64(record.getString("sealed"))
        require(blob.size in 29..16384) { "Invalid pairing profile" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decode64(key), "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        cipher.updateAAD("$id\n$pairId".toByteArray())
        val profile = String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
        val data = JSONObject(profile)
        require(data.getString("hostId") == id && sha256(decode64(data.getString("publicKey"))).hex() == id) { "Laptop identity could not be verified" }
        require(data.getString("token").length in 32..4096 && data.getString("backendPort").toIntOrNull() in 1..65535 && data.getString("user").length in 1..128 && data.getString("name").length in 1..100) { "Invalid laptop connection details" }
        return PairedHost(relay, pairId, key, profile, record.optBoolean("online"), record.optLong("lastSeen"))
    }
    fun pair(relayInput: String, phraseInput: String, done: () -> Unit) {
        if (pairing) return
        pairing = true; error = null
        scope.launch {
            try {
                val relay = discoveryRelay(relayInput)
                val phrase = phraseInput.lowercase().replace(Regex("[-\\s]"), "")
                require(phrase.matches(Regex("[a-f0-9]{32}"))) { "Paste the full pairing code from your laptop" }
                val (pairId, key) = withContext(Dispatchers.Default) {
                    val spec = PBEKeySpec(phrase.toCharArray(), "Outpost pairing v1".toByteArray(), 120000, 256)
                    val secret = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
                    sha256(secret + "lookup".toByteArray()).hex() to encode64(sha256(secret + "profile".toByteArray()))
                }
                val records = query(relay, listOf(pairId))
                require(records.size == 1) { "Laptop not found. Start hosting with discovery enabled, or check the pairing code." }
                val host = withContext(Dispatchers.Default) { decrypt(relay, pairId, key, records.single()) }
                require(hosts.size < 32 || hosts.any { it.identity == host.identity }) { "You can save up to 32 laptops. Forget one before adding another." }
                revision++; hosts = hosts.filterNot { it.identity == host.identity } + host; persist(); done()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Pairing failed. Check the relay and code." }
            finally { pairing = false }
        }
    }
    private suspend fun refreshNow() {
        if (loading || hosts.isEmpty()) return
        loading = true
        val snapshot = hosts; val generation = revision
        try {
            val updated = coroutineScope {
                snapshot.groupBy { it.relay }.map { (relay, group) -> async {
                    try {
                        val records = query(relay, group.map { it.pairId }).associateBy { it.getString("pairId") }
                        group.map { host ->
                            val record = records[host.pairId]
                            if (record == null) host.copy(online = false, error = "Pairing expired. Pair this laptop again.")
                            else runCatching { decrypt(relay, host.pairId, host.key, record).also { require(it.id == host.id) } }
                                .getOrElse { host.copy(online = false, error = "Laptop identity could not be verified. Pair again.") }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { group.map { it.copy(online = false, error = "Relay unavailable. Check your connection.") } }
                } }.awaitAll().flatten()
            }
            if (revision == generation) { hosts = updated; persist() }
        } finally { loading = false }
    }
}
