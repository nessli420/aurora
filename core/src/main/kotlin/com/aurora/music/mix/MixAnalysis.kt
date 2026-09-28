package com.aurora.music.mix

data class MixAnalysis(val peaks: List<Float>, val seconds: Float, val bpm: Float, val confidence: Float, val rmsDb: Float,
    val sonic: List<Float> = emptyList(), val energy: List<Float> = emptyList(), val beatOffset: Float = 0f,
    val audibleStart: Float = 0f, val audibleEnd: Float = seconds)
