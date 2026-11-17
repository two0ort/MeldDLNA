package com.metrolist.music.playback.dlna

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class DlnaLocalHttpServer(
    private val scope: CoroutineScope,
    private val streamUrlProvider: suspend (mediaId: String) -> String?,
    private val artworkUrlProvider: suspend (mediaId: String) -> String? = { null },
    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    var serverPort: Int = 8192
        private set

    companion object {
        private const val TAG = "DlnaLocalHttpServer"
        private const val DEFAULT_PORT = 8192
    }

    fun start(): Int {
        if (serverSocket != null && !serverSocket!!.isClosed) {
            return serverPort
        }

        try {
            serverSocket = ServerSocket(DEFAULT_PORT).apply {
                reuseAddress = true
            }
            serverPort = DEFAULT_PORT
        } catch (_: Exception) {
            // If default port is taken, let system allocate free port
            serverSocket = ServerSocket(0).apply {
                reuseAddress = true
            }
            serverPort = serverSocket!!.localPort
        }

        Timber.tag(TAG).d("DlnaLocalHttpServer started on port $serverPort")

        serverJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Timber.tag(TAG).d("Server socket closed or stopped: ${e.message}")
                        }
                        break
                    }
                }
            }

        return serverPort
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Timber.tag(TAG).d("DlnaLocalHttpServer stopped")
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))) {
                            return host
                        }
                    }
                }
            }
            // Fallback to any non-loopback IPv4
            val allInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in allInterfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error obtaining local IP address")
        }
        return null
    }

    fun getStreamUrlForMedia(mediaId: String): String {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        return "http://$ip:$serverPort/stream/$mediaId"
    }

    fun getArtworkUrlForMedia(mediaId: String): String {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        return "http://$ip:$serverPort/artwork/$mediaId"
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val path = parts[1]

                val headers = mutableMapOf<String, String>()
                var headerLine = reader.readLine()
                while (!headerLine.isNullOrBlank()) {
                    val idx = headerLine.indexOf(':')
                    if (idx > 0) {
                        val name = headerLine.substring(0, idx).trim().lowercase()
                        val value = headerLine.substring(idx + 1).trim()
                        headers[name] = value
                    }
                    headerLine = reader.readLine()
                }

                if (method != "GET" && method != "HEAD") {
                    sendSimpleResponse(s.getOutputStream(), 405, "Method Not Allowed")
                    return
                }

                when {
                    path.startsWith("/stream/") -> {
                        val mediaId = path.removePrefix("/stream/").substringBefore('?')
                        proxyStream(mediaId, headers["range"], method == "HEAD", s.getOutputStream())
                    }
                    path.startsWith("/artwork/") -> {
                        val mediaId = path.removePrefix("/artwork/").substringBefore('?')
                        proxyArtwork(mediaId, s.getOutputStream())
                    }
                    else -> {
                        sendSimpleResponse(s.getOutputStream(), 404, "Not Found")
                    }
                }
            } catch (e: Exception) {
                // Client connection reset is normal when DLNA renderer seeks or switches tracks
                Timber.tag(TAG).v("Client socket connection terminated: ${e.message}")
            }
        }
    }

    private suspend fun proxyStream(
        mediaId: String,
        rangeHeader: String?,
        isHead: Boolean,
        output: OutputStream,
    ) {
        val realStreamUrl = streamUrlProvider(mediaId)
        if (realStreamUrl == null) {
            sendSimpleResponse(output, 404, "Audio Stream Not Found")
            return
        }

        val requestBuilder = Request.Builder().url(realStreamUrl)
        if (!rangeHeader.isNullOrBlank()) {
            requestBuilder.addHeader("Range", rangeHeader)
        }

        try {
            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                val code = resp.code
                val statusMessage = if (code == 206) "Partial Content" else if (code == 200) "OK" else resp.message

                val out = BufferedOutputStream(output)
                val responseHeaders = StringBuilder()
                responseHeaders.append("HTTP/1.1 $code $statusMessage\r\n")
                responseHeaders.append("Server: MeldDLNA/1.0\r\n")
                responseHeaders.append("Accept-Ranges: bytes\r\n")

                val contentType = resp.header("Content-Type") ?: "audio/mp4"
                responseHeaders.append("Content-Type: $contentType\r\n")

                resp.header("Content-Length")?.let {
                    responseHeaders.append("Content-Length: $it\r\n")
                }
                resp.header("Content-Range")?.let {
                    responseHeaders.append("Content-Range: $it\r\n")
                }
                responseHeaders.append("Connection: keep-alive\r\n\r\n")

                out.write(responseHeaders.toString().toByteArray())
                out.flush()

                if (!isHead && resp.isSuccessful) {
                    val bodyStream = resp.body?.byteStream()
                    if (bodyStream != null) {
                        val buffer = ByteArray(16 * 1024)
                        val bufferedIn = BufferedInputStream(bodyStream)
                        var bytesRead: Int
                        while (bufferedIn.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                        out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).v("Streaming aborted: ${e.message}")
        }
    }

    private suspend fun proxyArtwork(
        mediaId: String,
        output: OutputStream,
    ) {
        val artworkUrl = artworkUrlProvider(mediaId)
        if (artworkUrl == null) {
            sendSimpleResponse(output, 404, "Artwork Not Found")
            return
        }

        try {
            val request = Request.Builder().url(artworkUrl).build()
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                val out = BufferedOutputStream(output)
                val contentType = resp.header("Content-Type") ?: "image/jpeg"
                val length = resp.header("Content-Length")

                val headerStr =
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        (if (length != null) "Content-Length: $length\r\n" else "") +
                        "Connection: close\r\n\r\n"

                out.write(headerStr.toByteArray())
                resp.body?.byteStream()?.copyTo(out)
                out.flush()
            }
        } catch (_: Exception) {
            sendSimpleResponse(output, 404, "Artwork Load Error")
        }
    }

    private fun sendSimpleResponse(
        output: OutputStream,
        statusCode: Int,
        message: String,
    ) {
        try {
            val response =
                "HTTP/1.1 $statusCode $message\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${message.length}\r\n" +
                    "Connection: close\r\n\r\n" +
                    message
            output.write(response.toByteArray())
            output.flush()
        } catch (_: Exception) {}
    }
}
