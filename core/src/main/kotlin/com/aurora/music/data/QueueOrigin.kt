package com.aurora.music.data

import com.aurora.music.foryou.MIX_KIND

// the collection page whose play or shuffle button made the queue, as "kind:id" with merged ids left namespaced
object QueueOrigin {
    fun of(kind: String, id: String): String = "$kind:$id"

    // a mix queue that moved on to the next edition still came from that mix
    fun followed(origin: String?, from: String, to: String): String? = if (origin == of(MIX_KIND, from)) of(MIX_KIND, to) else origin
}
