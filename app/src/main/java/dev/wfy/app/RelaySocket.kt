package dev.wfy.app

import com.jcraft.jsch.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** JSch still verifies the laptop host key and authenticates inside this stream. */
class RelaySocketFactory(private val url: String, private val access: String = "") : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket = RelaySocket(url, access)
    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

private class RelaySocket(url: String, access: String) : Socket() {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private val opened = CountDownLatch(1)
    private val queue = LinkedBlockingQueue<ByteArray>(64)
    @Volatile private var ended = false
    @Volatile private var failure: IOException? = null
    @Volatile private var timeout = 20000
    private val socket: WebSocket
    init {
        require(url.startsWith("wss://")) { "The relay URL must start with wss://" }
        socket = client.newWebSocket(Request.Builder().url(url).apply { if (access.isNotEmpty()) header("Authorization", "Bearer $access") }.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size == 0) return
                if (bytes.size > 65536 || !queue.offer(bytes.toByteArray())) { finish(IOException("Relay stream exceeded its buffer")); webSocket.cancel() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { finish(IOException("Relay sent an invalid text frame")); webSocket.cancel() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { finish(IOException(if (response?.code == 503) "Laptop is offline. Start hosting on the laptop." else "Relay connection failed: ${t.message}", t)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); finish(null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish(null) }
        })
        try {
            if (!opened.await(20, TimeUnit.SECONDS)) throw SocketTimeoutException("Relay connection timed out")
            failure?.let { throw it }
            if (ended) throw IOException("Relay connection closed")
        } catch (e: Exception) { close(); throw e }
    }
    private fun finish(error: IOException?) { failure = error; ended = true; opened.countDown(); queue.offer(ByteArray(0)) }
    private val input = object : InputStream() {
        private var chunk = ByteArray(0)
        private var offset = 0
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            while (offset == chunk.size) {
                if (ended && queue.isEmpty()) { failure?.let { throw it }; return -1 }
                chunk = try { if (timeout == 0) queue.take() else queue.poll(timeout.toLong(), TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("Relay read timed out") }
                catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Relay read interrupted", e) }
                offset = 0
                if (chunk.isEmpty() && ended) { failure?.let { throw it }; return -1 }
            }
            val count = minOf(len, chunk.size - offset); chunk.copyInto(b, off, offset, offset + count); offset += count; return count
        }
    }
    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
            if (ended) throw failure ?: IOException("Relay closed")
            var position = off
            while (position < off + len) {
                val count = minOf(32768, off + len - position)
                if (!socket.send(b.toByteString(position, count))) throw IOException("Relay send buffer is full")
                position += count
            }
        }
    }
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun setSoTimeout(value: Int) { require(value >= 0); timeout = value }
    override fun getSoTimeout(): Int = timeout
    override fun setTcpNoDelay(value: Boolean) { }
    override fun isConnected(): Boolean = !ended
    override fun isClosed(): Boolean = ended
    override fun close() { finish(null); socket.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
