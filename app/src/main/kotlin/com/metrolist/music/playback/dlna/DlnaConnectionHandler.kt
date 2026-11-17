package com.metrolist.music.playback.dlna

import android.content.Context
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class DlnaConnectionHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val musicService: MusicService,
) {
    val discovery = SsdpDiscovery(context, scope)
    val controlPoint = DlnaControlPoint()
    val localHttpServer =
        DlnaLocalHttpServer(
            scope = scope,
            streamUrlProvider = { mediaId ->
                musicService.getStreamUrl(mediaId)
            },
            artworkUrlProvider = { mediaId ->
                musicService.database.songEntity(mediaId)?.thumbnailUrl
            },
        )

    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _castDeviceName = MutableStateFlow<String?>(null)
    val castDeviceName: StateFlow<String?> = _castDeviceName.asStateFlow()

    private val _castPosition = MutableStateFlow(0L)
    val castPosition: StateFlow<Long> = _castPosition.asStateFlow()

    private val _castDuration = MutableStateFlow(0L)
    val castDuration: StateFlow<Long> = _castDuration.asStateFlow()

    private val _castIsPlaying = MutableStateFlow(false)
    val castIsPlaying: StateFlow<Boolean> = _castIsPlaying.asStateFlow()

    private val _castIsBuffering = MutableStateFlow(false)
    val castIsBuffering: StateFlow<Boolean> = _castIsBuffering.asStateFlow()

    private val _castVolume = MutableStateFlow(1f)
    val castVolume: StateFlow<Float> = _castVolume.asStateFlow()

    private val _connectedDevice = MutableStateFlow<DlnaDevice?>(null)
    val connectedDevice: StateFlow<DlnaDevice?> = _connectedDevice.asStateFlow()

    private var pollJob: Job? = null
    private var lastLoadedMediaId: String? = null

    companion object {
        private const val TAG = "DlnaConnectionHandler"
    }

    fun connect(device: DlnaDevice) {
        scope.launch(Dispatchers.IO) {
            try {
                Timber.tag(TAG).d("Connecting to DLNA device: ${device.name}")
                _connectedDevice.value = device
                _castDeviceName.value = device.name

                localHttpServer.start()

                // Pause local playback
                launch(Dispatchers.Main) {
                    musicService.player.pause()
                }

                _isCasting.value = true
                _castIsPlaying.value = true

                // Initial volume sync
                device.renderingControlUrl?.let {
                    val vol = controlPoint.getVolume(device)
                    if (vol != null) {
                        _castVolume.value = vol / 100f
                    }
                }

                // Load currently active song
                val currentMedia = musicService.currentMediaMetadata.value
                if (currentMedia != null) {
                    loadMedia(currentMedia)
                }

                startPolling(device)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error connecting to DLNA device")
                disconnect()
            }
        }
    }

    fun disconnect() {
        val device = _connectedDevice.value
        stopPolling()
        scope.launch(Dispatchers.IO) {
            if (device != null) {
                try {
                    controlPoint.stop(device)
                } catch (_: Exception) {}
            }
            localHttpServer.stop()
        }

        _isCasting.value = false
        _castDeviceName.value = null
        _connectedDevice.value = null
        _castIsPlaying.value = false
        _castIsBuffering.value = false
        lastLoadedMediaId = null
    }

    fun loadMedia(metadata: MediaMetadata) {
        val device = _connectedDevice.value ?: return
        lastLoadedMediaId = metadata.id
        scope.launch(Dispatchers.IO) {
            try {
                _castIsBuffering.value = true
                val streamUrl = localHttpServer.getStreamUrlForMedia(metadata.id)
                val artworkUrl = metadata.thumbnailUrl ?: localHttpServer.getArtworkUrlForMedia(metadata.id)

                val durationSec = metadata.duration.takeIf { it > 0 } ?: 0
                _castDuration.value = durationSec * 1000L

                val setUriSuccess =
                    controlPoint.setAVTransportURI(
                        device = device,
                        streamUrl = streamUrl,
                        title = metadata.title,
                        artist = metadata.artists.joinToString { it.name },
                        album = metadata.album?.title.orEmpty(),
                        albumArtUri = artworkUrl,
                        durationSeconds = durationSec,
                    )

                if (setUriSuccess) {
                    controlPoint.play(device)
                    _castIsPlaying.value = true
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error loading media to DLNA device: ${metadata.id}")
            } finally {
                _castIsBuffering.value = false
            }
        }
    }

    fun play() {
        val device = _connectedDevice.value ?: return
        scope.launch(Dispatchers.IO) {
            if (controlPoint.play(device)) {
                _castIsPlaying.value = true
            }
        }
    }

    fun pause() {
        val device = _connectedDevice.value ?: return
        scope.launch(Dispatchers.IO) {
            if (controlPoint.pause(device)) {
                _castIsPlaying.value = false
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val device = _connectedDevice.value ?: return
        _castPosition.value = positionMs
        scope.launch(Dispatchers.IO) {
            controlPoint.seek(device, positionMs)
        }
    }

    fun setVolume(volume: Float) {
        val device = _connectedDevice.value ?: return
        _castVolume.value = volume
        scope.launch(Dispatchers.IO) {
            controlPoint.setVolume(device, (volume * 100).toInt())
        }
    }

    fun skipToNext() {
        launchOnMain {
            musicService.player.seekToNextMediaItem()
        }
    }

    fun skipToPrevious() {
        launchOnMain {
            musicService.player.seekToPreviousMediaItem()
        }
    }

    private fun startPolling(device: DlnaDevice) {
        pollJob?.cancel()
        pollJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(1000)
                    try {
                        val posInfo = controlPoint.getPositionInfo(device)
                        if (posInfo != null) {
                            _castPosition.value = posInfo.positionMs
                            if (posInfo.durationMs > 0) {
                                _castDuration.value = posInfo.durationMs
                            }
                        }

                        val transportState = controlPoint.getTransportInfo(device)
                        if (transportState != null) {
                            when (transportState) {
                                "PLAYING" -> {
                                    _castIsPlaying.value = true
                                    _castIsBuffering.value = false
                                }
                                "PAUSED_PLAYBACK" -> {
                                    _castIsPlaying.value = false
                                    _castIsBuffering.value = false
                                }
                                "TRANSITIONING" -> {
                                    _castIsBuffering.value = true
                                }
                                "STOPPED" -> {
                                    // Song finished, auto-advance to next song if we reached the end
                                    val currentPos = _castPosition.value
                                    val currentDur = _castDuration.value
                                    if (currentDur > 0 && currentPos >= (currentDur - 3000L)) {
                                        Timber.tag(TAG).d("DLNA playback reached end of track, advancing queue")
                                        launchOnMain {
                                            musicService.player.seekToNextMediaItem()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Timber.tag(TAG).v("Polling error: ${e.message}")
                        }
                    }
                }
            }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun launchOnMain(block: suspend () -> Unit) {
        scope.launch(Dispatchers.Main) {
            block()
        }
    }

    fun release() {
        disconnect()
        discovery.stopScan()
    }
}
