package com.aurora.music.data.remote

data class ClientInfo(
    val product: String,
    val version: String,
    val platform: String,
    val device: String,
    val deviceName: String,
)
