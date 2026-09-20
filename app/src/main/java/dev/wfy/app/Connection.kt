package dev.wfy.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ConnectionConfig(
    val host: String = "", val port: String = "22", val user: String = "ubuntu",
    val password: String = "", val key: String = "", val passphrase: String = "",
    val useKey: Boolean = true, val backendPort: String = "8787", val token: String = "",
    val fontSize: String = "15", val syncSeconds: String = "5", val keepScreenOn: Boolean = false,
    val backendHost: String = "127.0.0.1", val uiScale: String = "1.0", val relayUrl: String = "", val relayAccess: String = "", val pairedHostId: String = "", val pairedPublicKey: String = "", val hostLabel: String = ""
) {
    fun json() = JSONObject().apply {
        put("host", host); put("port", port); put("user", user); put("password", password)
        put("key", key); put("passphrase", passphrase); put("useKey", useKey)
        put("backendPort", backendPort); put("token", token); put("fontSize", fontSize)
        put("syncSeconds", syncSeconds); put("keepScreenOn", keepScreenOn)
        put("backendHost", backendHost)
        put("uiScale", uiScale)
        put("relayUrl", relayUrl); put("relayAccess", relayAccess); put("pairedHostId", pairedHostId); put("pairedPublicKey", pairedPublicKey); put("hostLabel", hostLabel)
    }
    companion object {
        fun from(j: JSONObject) = ConnectionConfig(j.optString("host"), j.optString("port", "22"), j.optString("user", "ubuntu"), j.optString("password"), j.optString("key"), j.optString("passphrase"), j.optBoolean("useKey", true), j.optString("backendPort", "8787"), j.optString("token"), j.optString("fontSize", "15"), j.optString("syncSeconds", "5"), j.optBoolean("keepScreenOn"), j.optString("backendHost", "127.0.0.1"), j.optString("uiScale", "1.0"), j.optString("relayUrl"), j.optString("relayAccess"), j.optString("pairedHostId"), j.optString("pairedPublicKey"), j.optString("hostLabel"))
    }
}

/** Only ciphertext and the non-secret GCM nonce are stored in SharedPreferences. */
class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("outpost.vault", Context.MODE_PRIVATE)
    private val key: SecretKey get() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("outpost", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("outpost", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    @Synchronized fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Could not save encrypted settings" }
    }
}

class Tunnel(private val vault: Vault) {
    private var epoch = 0
    @Volatile var session: Session? = null; private set
    @Volatile var localPort: Int = 0; private set
    @Synchronized fun generation() = epoch
    val connected get() = session?.isConnected == true
    suspend fun connect(c: ConnectionConfig, trust: suspend (String, Boolean) -> Boolean) = withContext(Dispatchers.IO) {
        val attempt = synchronized(this@Tunnel) { close(); epoch }
        require(c.host.isNotBlank() && c.user.isNotBlank()) { "Enter the SSH host and username" }
        val sshPort = c.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Invalid SSH port")
        val apiPort = c.backendPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Invalid backend port")
        require(c.token.length >= 32) { "Paste the server token printed by the installer" }
        val client = JSch()
        // Android does not load the JAR's Java-15 multi-release EdDSA implementations.
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        client.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String, raw: ByteArray): Int {
                val encoded = Base64.encodeToString(raw, Base64.NO_WRAP)
                if (c.pairedPublicKey.isNotBlank()) return if (MessageDigest.isEqual(Base64.decode(c.pairedPublicKey, Base64.NO_WRAP), raw)) HostKeyRepository.OK else HostKeyRepository.CHANGED
                val name = "hostkey:${c.host}:$sshPort"
                val old = vault.read(name)
                if (old == encoded) return HostKeyRepository.OK
                val fp = "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(raw), Base64.NO_WRAP or Base64.NO_PADDING)
                val accepted = runBlocking { trust(fp, old != null) }
                if (accepted) { vault.write(name, encoded); return HostKeyRepository.OK }
                return HostKeyRepository.CHANGED
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) {}
            override fun remove(host: String?, type: String?) {}
            override fun remove(host: String?, type: String?, key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "Android Keystore"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        if (c.useKey) {
            require(c.key.isNotBlank()) { "Import or paste your SSH private key" }
            client.addIdentity("outpost", c.key.toByteArray(), null, c.passphrase.takeIf { it.isNotEmpty() }?.toByteArray())
        }
        val ssh = client.getSession(c.user, c.host.trim(), sshPort)
        if (c.relayUrl.isNotBlank()) ssh.setSocketFactory(RelaySocketFactory(c.relayUrl.trim(), c.relayAccess))
        ssh.setConfig("StrictHostKeyChecking", "yes")
        ssh.setConfig("PreferredAuthentications", if (c.useKey) "publickey" else "password,keyboard-interactive")
        if (!c.useKey) ssh.setPassword(c.password)
        ssh.serverAliveInterval = 15000
        ssh.serverAliveCountMax = 3
        try {
            ssh.connect(20000)
            val port = ssh.setPortForwardingL("127.0.0.1", 0, c.backendHost.ifBlank { "127.0.0.1" }, apiPort)
            synchronized(this@Tunnel) {
                check(epoch == attempt) { "SSH connection was cancelled" }
                localPort = port; session = ssh
            }
        } catch (e: Exception) { ssh.disconnect(); throw e }
    }
    @Synchronized fun forward(host: String, port: Int): Int {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' } && port in 1..65535) { "Enter a hostname and port between 1 and 65535" }
        val ssh = session?.takeIf { it.isConnected } ?: error("Connect to SSH first")
        return ssh.setPortForwardingL("127.0.0.1", 0, host, port)
    }
    @Synchronized fun removeForward(port: Int) { session?.delPortForwardingL("127.0.0.1", port) }
    @Synchronized fun close() { epoch++; session?.disconnect(); session = null; localPort = 0 }
}

class Api(private val tunnel: Tunnel, private val token: () -> String) {
    val client = workspaceHttpClient()
    val base get() = "http://127.0.0.1:${tunnel.localPort}"
    fun request(path: String) = Request.Builder().url(base + path).header("Authorization", "Bearer ${token()}")
    suspend fun call(path: String, method: String = "GET", data: JSONObject? = null): String {
        check(tunnel.connected) { "Connect to your server in Settings first" }
        val generation = tunnel.generation()
        // Freeze target and token before dispatching; a reconnect must not retarget a queued write.
        val req = request(path).method(method, if (method == "GET") null else (data ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())).build()
        val text = executeRequest(client, req)
        check(tunnel.connected && generation == tunnel.generation()) { "Connection changed during the request. Refresh server state before retrying." }
        return text
    }
}
