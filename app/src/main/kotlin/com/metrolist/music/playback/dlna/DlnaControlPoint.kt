package com.metrolist.music.playback.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DlnaPositionInfo(
    val positionMs: Long,
    val durationMs: Long,
)

class DlnaControlPoint(
    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build(),
) {
    companion object {
        private const val TAG = "DlnaControlPoint"
        private const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING_CONTROL_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"
    }

    suspend fun setAVTransportURI(
        device: DlnaDevice,
        streamUrl: String,
        title: String,
        artist: String,
        album: String,
        albumArtUri: String?,
        durationSeconds: Int = 0,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val metadataDidl = buildDidlLiteXml(
                streamUrl = streamUrl,
                title = title,
                artist = artist,
                album = album,
                albumArtUri = albumArtUri,
                durationSeconds = durationSeconds,
            )

            val escapedMetadata = escapeXml(metadataDidl)
            val actionBody =
                """
                <u:SetAVTransportURI xmlns:u="$AV_TRANSPORT_SERVICE">
                    <InstanceID>0</InstanceID>
                    <CurrentURI>${escapeXml(streamUrl)}</CurrentURI>
                    <CurrentURIMetaData>$escapedMetadata</CurrentURIMetaData>
                </u:SetAVTransportURI>
                """.trimIndent()

            val success = sendSoapAction(device.avTransportControlUrl, AV_TRANSPORT_SERVICE, "SetAVTransportURI", actionBody)
            success
        }

    suspend fun play(device: DlnaDevice): Boolean =
        withContext(Dispatchers.IO) {
            val actionBody =
                """
                <u:Play xmlns:u="$AV_TRANSPORT_SERVICE">
                    <InstanceID>0</InstanceID>
                    <Speed>1</Speed>
                </u:Play>
                """.trimIndent()
            sendSoapAction(device.avTransportControlUrl, AV_TRANSPORT_SERVICE, "Play", actionBody)
        }

    suspend fun pause(device: DlnaDevice): Boolean =
        withContext(Dispatchers.IO) {
            val actionBody =
                """
                <u:Pause xmlns:u="$AV_TRANSPORT_SERVICE">
                    <InstanceID>0</InstanceID>
                </u:Pause>
                """.trimIndent()
            sendSoapAction(device.avTransportControlUrl, AV_TRANSPORT_SERVICE, "Pause", actionBody)
        }

    suspend fun stop(device: DlnaDevice): Boolean =
        withContext(Dispatchers.IO) {
            val actionBody =
                """
                <u:Stop xmlns:u="$AV_TRANSPORT_SERVICE">
                    <InstanceID>0</InstanceID>
                </u:Stop>
                """.trimIndent()
            sendSoapAction(device.avTransportControlUrl, AV_TRANSPORT_SERVICE, "Stop", actionBody)
        }

    suspend fun seek(
        device: DlnaDevice,
        positionMs: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val targetTime = formatDuration(positionMs)
            val actionBody =
                """
                <u:Seek xmlns:u="$AV_TRANSPORT_SERVICE">
                    <InstanceID>0</InstanceID>
                    <Unit>REL_TIME</Unit>
                    <Target>$targetTime</Target>
                </u:Seek>
                """.trimIndent()
            sendSoapAction(device.avTransportControlUrl, AV_TRANSPORT_SERVICE, "Seek", actionBody)
        }

    suspend fun getPositionInfo(device: DlnaDevice): DlnaPositionInfo? =
        withContext(Dispatchers.IO) {
            val actionBody =
                """
                <u:GetPositionInfo xmlns:u="$AV_TRANSPORT_SERVICE">
                    <InstanceID>0</InstanceID>
                </u:GetPositionInfo>
                """.trimIndent()

            val responseXml =
                sendSoapActionWithResponse(
                    device.avTransportControlUrl,
                    AV_TRANSPORT_SERVICE,
                    "GetPositionInfo",
                    actionBody,
                ) ?: return@withContext null

            parsePositionInfo(responseXml)
        }

    suspend fun getTransportInfo(device: DlnaDevice): String? =
        withContext(Dispatchers.IO) {
            val actionBody =
                """
                <u:GetTransportInfo xmlns:u="$AV_TRANSPORT_SERVICE">
                    <InstanceID>0</InstanceID>
                </u:GetTransportInfo>
                """.trimIndent()

            val responseXml =
                sendSoapActionWithResponse(
                    device.avTransportControlUrl,
                    AV_TRANSPORT_SERVICE,
                    "GetTransportInfo",
                    actionBody,
                ) ?: return@withContext null

            parseTagText(responseXml, "CurrentTransportState")
        }

    suspend fun setVolume(
        device: DlnaDevice,
        volumePercent: Int,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val controlUrl = device.renderingControlUrl ?: return@withContext false
            val clamped = volumePercent.coerceIn(0, 100)
            val actionBody =
                """
                <u:SetVolume xmlns:u="$RENDERING_CONTROL_SERVICE">
                    <InstanceID>0</InstanceID>
                    <Channel>Master</Channel>
                    <DesiredVolume>$clamped</DesiredVolume>
                </u:SetVolume>
                """.trimIndent()
            sendSoapAction(controlUrl, RENDERING_CONTROL_SERVICE, "SetVolume", actionBody)
        }

    suspend fun getVolume(device: DlnaDevice): Int? =
        withContext(Dispatchers.IO) {
            val controlUrl = device.renderingControlUrl ?: return@withContext null
            val actionBody =
                """
                <u:GetVolume xmlns:u="$RENDERING_CONTROL_SERVICE">
                    <InstanceID>0</InstanceID>
                    <Channel>Master</Channel>
                </u:GetVolume>
                """.trimIndent()

            val responseXml =
                sendSoapActionWithResponse(
                    controlUrl,
                    RENDERING_CONTROL_SERVICE,
                    "GetVolume",
                    actionBody,
                ) ?: return@withContext null

            parseTagText(responseXml, "CurrentVolume")?.toIntOrNull()
        }

    private fun sendSoapAction(
        controlUrl: String,
        serviceType: String,
        actionName: String,
        actionBody: String,
    ): Boolean {
        val response = sendSoapActionWithResponse(controlUrl, serviceType, actionName, actionBody)
        return response != null
    }

    private fun sendSoapActionWithResponse(
        controlUrl: String,
        serviceType: String,
        actionName: String,
        actionBody: String,
    ): String? {
        return try {
            val soapEnvelope =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                    <s:Body>
                        $actionBody
                    </s:Body>
                </s:Envelope>
                """.trimIndent()

            val requestBody = soapEnvelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())
            val request =
                Request.Builder()
                    .url(controlUrl)
                    .addHeader("SOAPACTION", "\"$serviceType#$actionName\"")
                    .post(requestBody)
                    .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                Timber.tag(TAG).w("SOAP action $actionName failed with HTTP ${response.code}")
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Exception executing SOAP action $actionName: ${e.message}")
            null
        }
    }

    private fun buildDidlLiteXml(
        streamUrl: String,
        title: String,
        artist: String,
        album: String,
        albumArtUri: String?,
        durationSeconds: Int,
    ): String {
        val durationStr = if (durationSeconds > 0) " duration=\"${formatDuration(durationSeconds * 1000L)}\"" else ""
        val artTag = if (!albumArtUri.isNullOrBlank()) "<upnp:albumArtURI>${escapeXml(albumArtUri)}</upnp:albumArtURI>" else ""

        return """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item id="1" parentID="0" restricted="1">
                <dc:title>${escapeXml(title)}</dc:title>
                <dc:creator>${escapeXml(artist)}</dc:creator>
                <upnp:artist>${escapeXml(artist)}</upnp:artist>
                <upnp:album>${escapeXml(album)}</upnp:album>
                $artTag
                <res protocolInfo="http-get:*:audio/mp4:*"$durationStr>${escapeXml(streamUrl)}</res>
                <upnp:class>object.item.audioItem.musicTrack</upnp:class>
            </item>
        </DIDL-Lite>
        """.trimIndent()
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun parsePositionInfo(xml: String): DlnaPositionInfo? {
        val relTimeStr = parseTagText(xml, "RelTime") ?: "00:00:00"
        val trackDurationStr = parseTagText(xml, "TrackDuration") ?: "00:00:00"

        val relTimeMs = parseDurationMs(relTimeStr)
        val durationMs = parseDurationMs(trackDurationStr)
        return DlnaPositionInfo(positionMs = relTimeMs, durationMs = durationMs)
    }

    private fun parseTagText(
        xml: String,
        targetTag: String,
    ): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals(targetTag, ignoreCase = true)) {
                    return parser.nextText().trim()
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDurationMs(time: String): Long {
        return try {
            val parts = time.trim().split(":")
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val secondsWithFraction = parts[2].toDouble()
                ((hours * 3600 + minutes * 60 + secondsWithFraction) * 1000).toLong()
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
