package com.aurora.music.data

object GenreText {
    fun split(raw: String): List<String> {
        // id3v2.4 stores several genres in one frame separated by nul
        val parts = raw.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
        // broken tags leak raw frame bytes so one bad part means the whole value is unusable
        return if (parts.all(::readable)) parts else emptyList()
    }

    fun matches(raw: String, genre: String): Boolean = split(raw).any { it.equals(genre, true) }

    private fun readable(value: String): Boolean = value.length <= 60 &&
        "ÿþ" !in value && "þÿ" !in value &&
        value.none { c ->
            Character.isISOControl(c) || c == '�' || c == '﻿' ||
                Character.getType(c).let { it == Character.PRIVATE_USE.toInt() || it == Character.UNASSIGNED.toInt() }
        }
}
