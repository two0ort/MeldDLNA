package com.metrolist.music.playback.dlna

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SsdpDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build(),
) {
    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null
    private val discoveredLocations = ConcurrentHashMap<String, DlnaDevice>()

    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SCAN_TIMEOUT_MS = 6000

        private val SEARCH_TARGETS =
            listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
            )
    }

    fun startScan() {
        if (_isScanning.value) return
        discoveredLocations.clear()
        _devices.value = emptyList()
        _isScanning.value = true

        scanJob?.cancel()
        scanJob =
            scope.launch(Dispatchers.IO) {
                acquireMulticastLock()
                try {
                    val socket =
                        DatagramSocket(null).apply {
                            reuseAddress = true
                            soTimeout = 1000
                            bind(InetSocketAddress(0))
                        }

                    socket.use { ds ->
                        val ssdpGroup = InetAddress.getByName(SSDP_ADDRESS)

                        // Send search requests for all search targets
                        for (st in SEARCH_TARGETS) {
                            val msg =
                                "M-SEARCH * HTTP/1.1\r\n" +
                                    "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                                    "MAN: \"ssdp:discover\"\r\n" +
                                    "MX: 3\r\n" +
                                    "ST: $st\r\n\r\n"
                            val bytes = msg.toByteArray()
                            val packet = DatagramPacket(bytes, bytes.size, ssdpGroup, SSDP_PORT)
                            ds.send(packet)
                        }

                        val receiveBuffer = ByteArray(4096)
                        val startTime = System.currentTimeMillis()

                        while (isActive && (System.currentTimeMillis() - startTime) < SCAN_TIMEOUT_MS) {
                            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                            try {
                                ds.receive(receivePacket)
                                val response = String(receivePacket.data, 0, receivePacket.length)
                                val location = extractHeader(response, "LOCATION")
                                if (!location.isNullOrBlank() && !discoveredLocations.containsKey(location)) {
                                    val senderIp = receivePacket.address.hostAddress ?: ""
                                    launch(Dispatchers.IO) {
                                        fetchAndParseDevice(location, senderIp)
                                    }
                                }
                            } catch (e: SocketTimeoutException) {
                                // Continue loop until scan duration expires
                            } catch (e: Exception) {
                                if (e !is CancellationException) {
                                    Timber.tag(TAG).w(e, "Error during SSDP packet receive")
                                }
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Timber.tag(TAG).e(e, "SSDP Discovery failed")
                    }
                } finally {
                    releaseMulticastLock()
                    _isScanning.value = false
                }
            }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        releaseMulticastLock()
        _isScanning.value = false
    }

    private suspend fun fetchAndParseDevice(
        location: String,
        senderIp: String,
    ) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(location).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext
            val xml = response.body?.string() ?: return@withContext

            val device = parseDeviceXml(xml, location, senderIp)
            if (device != null && device.avTransportControlUrl.isNotBlank()) {
                discoveredLocations[location] = device
                _devices.value = discoveredLocations.values.toList()
                Timber.tag(TAG).d("Discovered DLNA Device: ${device.name} at ${device.location}")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to parse device description from $location: ${e.message}")
        }
    }

    private fun parseDeviceXml(
        xml: String,
        location: String,
        senderIp: String,
    ): DlnaDevice? {
        return try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var friendlyName: String? = null
            var manufacturer = ""
            var modelName = ""
            var udn = ""
            var iconUrl: String? = null
            var avTransportControlUrl: String? = null
            var renderingControlUrl: String? = null

            var currentTag: String? = null
            var inDevice = false
            var inService = false
            var currentServiceType: String? = null
            var currentControlUrl: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "device") inDevice = true
                        if (currentTag == "service") {
                            inService = true
                            currentServiceType = null
                            currentControlUrl = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            if (inService) {
                                when (currentTag) {
                                    "serviceType" -> currentServiceType = text
                                    "controlURL" -> currentControlUrl = text
                                }
                            } else if (inDevice) {
                                when (currentTag) {
                                    "friendlyName" -> if (friendlyName == null) friendlyName = text
                                    "manufacturer" -> if (manufacturer.isEmpty()) manufacturer = text
                                    "modelName" -> if (modelName.isEmpty()) modelName = text
                                    "UDN" -> if (udn.isEmpty()) udn = text
                                    "url" -> if (iconUrl == null && text.isNotEmpty()) iconUrl = text
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name
                        if (tag == "service") {
                            if (currentServiceType != null && currentControlUrl != null) {
                                if (currentServiceType!!.contains("AVTransport")) {
                                    avTransportControlUrl = resolveUrl(location, currentControlUrl!!)
                                } else if (currentServiceType!!.contains("RenderingControl")) {
                                    renderingControlUrl = resolveUrl(location, currentControlUrl!!)
                                }
                            }
                            inService = false
                        } else if (tag == "device") {
                            // Keep parsing nested subdevices if any
                        }
                        currentTag = null
                    }
                }
                eventType = parser.next()
            }

            if (friendlyName != null && avTransportControlUrl != null) {
                DlnaDevice(
                    id = if (udn.isNotBlank()) udn else location,
                    name = friendlyName,
                    manufacturer = manufacturer,
                    modelName = modelName,
                    location = location,
                    iconUrl = iconUrl?.let { resolveUrl(location, it) },
                    avTransportControlUrl = avTransportControlUrl,
                    renderingControlUrl = renderingControlUrl,
                    ipAddress = senderIp,
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error parsing XML from $location")
            null
        }
    }

    private fun resolveUrl(
        baseLocation: String,
        path: String,
    ): String {
        return try {
            val baseUri = URI(baseLocation)
            baseUri.resolve(path).toString()
        } catch (_: Exception) {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                val cleanBase = baseLocation.substringBeforeLast('/')
                val cleanPath = if (path.startsWith("/")) path else "/$path"
                "$cleanBase$cleanPath"
            }
        }
    }

    private fun extractHeader(
        response: String,
        headerName: String,
    ): String? {
        val lines = response.lines()
        val prefix = "$headerName:"
        for (line in lines) {
            if (line.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
                return line.substring(prefix.length).trim()
            }
        }
        return null
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("MeldDlnaMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not acquire multicast lock")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not release multicast lock")
        }
    }
}
