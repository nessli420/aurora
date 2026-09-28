package com.aurora.music.util

object AppLog {
    enum class Level { DEBUG, WARN, ERROR }

    @Volatile var sink: (Level, String, String, Throwable?) -> Unit = { level, tag, message, error ->
        System.err.println("${level.name.first()}/$tag: $message")
        error?.printStackTrace()
    }

    fun d(tag: String, message: String) = sink(Level.DEBUG, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink(Level.ERROR, tag, message, error)
}
