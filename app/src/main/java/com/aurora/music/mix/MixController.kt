package com.aurora.music.mix

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class MixPlaybackState(val projectId: String = "", val playing: Boolean = false, val buffering: Boolean = false,
    val positionSec: Float = 0f, val error: String? = null, val levels: List<Float> = emptyList())

sealed interface MixCommand {
    data object Toggle : MixCommand
    data object Stop : MixCommand
    data class Seek(val seconds: Float) : MixCommand
    data class Update(val project: MixProject) : MixCommand
}

class MixController {
    var activeProject: MixProject? = null
    var pendingProject: MixProject? = null
    var pendingPosition: Float = 0f
    var pendingTracklist: Boolean = false
    val state = MutableStateFlow(MixPlaybackState())
    val commands = MutableSharedFlow<MixCommand>(extraBufferCapacity = 32)
}
