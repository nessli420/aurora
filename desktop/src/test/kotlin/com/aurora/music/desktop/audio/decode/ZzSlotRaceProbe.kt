package com.aurora.music.desktop.audio.decode

import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.javacpp.Pointer
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ZzSlotRaceProbe {
    private class Cb : AVIOInterruptCB.Callback_Pointer() {
        override fun call(opaque: Pointer?): Int = 0
    }

    @Test
    fun concurrentAllocationSharesSlots() {
        org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
        val live = ConcurrentHashMap.newKeySet<Long>()
        val collisions = AtomicInteger()
        val zero = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (0 until 6).map {
            thread {
                start.await()
                repeat(200_000) {
                    val cb = Cb()
                    val holder = AVIOInterruptCB()
                    holder.callback(cb)
                    val address = org.bytedeco.javacpp.LongPointer(holder).get(0)
                    holder.close()
                    if (address == 0L) zero.incrementAndGet()
                    else if (!live.add(address)) collisions.incrementAndGet()
                    else live.remove(address)
                    cb.close()
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        println("SLOTPROBE collisions=${collisions.get()} zero=${zero.get()}")
    }

    @Test
    fun staleSlotCall() {
        val cb = Cb()
        val holder = AVIOInterruptCB()
        holder.callback(cb)
        val fn = org.bytedeco.javacpp.LongPointer(holder).get(0)
        holder.close()
        cb.close()
        val file = java.io.File.createTempFile("slot", ".bin").apply { writeBytes(ByteArray(65536) { it.toByte() }); deleteOnExit() }
        val format = org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context()
        org.bytedeco.javacpp.LongPointer(format.interrupt_callback()).put(0, fn)
        println("SLOTPROBE opening with stale thunk $fn")
        val result = runCatching { org.bytedeco.ffmpeg.global.avformat.avformat_open_input(format, "file:" + file.path, null, null) }
        println("SLOTPROBE stale open -> $result")
    }
}
