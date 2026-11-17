package com.metrolist.music.playback.dlna

data class DlnaDevice(
    val id: String,
    val name: String,
    val manufacturer: String = "",
    val modelName: String = "",
    val location: String,
    val iconUrl: String? = null,
    val avTransportControlUrl: String,
    val renderingControlUrl: String? = null,
    val ipAddress: String = "",
)
