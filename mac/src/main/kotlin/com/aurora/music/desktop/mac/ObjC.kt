package com.aurora.music.desktop.mac

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.aurora.music.util.AppLog
import java.util.concurrent.ConcurrentLinkedQueue

// just enough of the objective-c runtime to talk to appkit and the media player framework without a native library.
// objc_msgSend is called with fixed arguments, which is what arm64 needs; jna only goes variadic when asked to
internal object ObjC {
    private val objc = NativeLibrary.getInstance("objc")
    private val send: Function = objc.getFunction("objc_msgSend")

    @Suppress("FunctionName")
    private interface Runtime : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Long): Pointer?
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, sel: Pointer, imp: Callback, types: String): Boolean
        fun objc_autoreleasePoolPush(): Pointer
        fun objc_autoreleasePoolPop(pool: Pointer)
    }

    private val runtime: Runtime = Native.load("objc", Runtime::class.java)

    fun cls(name: String): Pointer = checkNotNull(runtime.objc_getClass(name)) { "No Objective-C class $name" }
    fun sel(name: String): Pointer = runtime.sel_registerName(name)

    fun msg(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        receiver?.let { send.invokePointer(arrayOf(it, sel(selector), *args)) }

    fun msgLong(receiver: Pointer, selector: String, vararg args: Any?): Long = send.invokeLong(arrayOf(receiver, sel(selector), *args))
    fun msgDouble(receiver: Pointer, selector: String, vararg args: Any?): Double = send.invokeDouble(arrayOf(receiver, sel(selector), *args))
    fun msgVoid(receiver: Pointer, selector: String, vararg args: Any?) { send.invoke(arrayOf(receiver, sel(selector), *args)) }

    fun string(value: String): Pointer = checkNotNull(msg(cls("NSString"), "stringWithUTF8String:", value))
    fun number(value: Double): Pointer = checkNotNull(msg(cls("NSNumber"), "numberWithDouble:", value))
    fun number(value: Long): Pointer = checkNotNull(msg(cls("NSNumber"), "numberWithLongLong:", value))

    fun data(bytes: ByteArray): Pointer {
        val memory = Memory(bytes.size.toLong().coerceAtLeast(1)).apply { write(0, bytes, 0, bytes.size) }
        return checkNotNull(msg(cls("NSData"), "dataWithBytes:length:", memory, bytes.size.toLong()))
    }

    fun release(obj: Pointer?) { obj?.let { msgVoid(it, "release") } }

    /** Registers a subclass of NSObject whose methods call back into kotlin; the callbacks must stay referenced. */
    fun subclass(name: String, methods: Map<String, Pair<Callback, String>>): Pointer {
        runtime.objc_getClass(name)?.let { return it }
        val cls = checkNotNull(runtime.objc_allocateClassPair(cls("NSObject"), name, 0)) { "Could not create $name" }
        methods.forEach { (selector, method) -> runtime.class_addMethod(cls, sel(selector), method.first, method.second) }
        runtime.objc_registerClassPair(cls)
        return cls
    }

    /** The value of an exported NSString constant, such as MPMediaItemPropertyTitle. */
    fun constant(library: String, symbol: String): Pointer =
        NativeLibrary.getInstance(library).getGlobalVariableAddress(symbol).getPointer(0)

    // appkit and the media player framework want the main thread; work is queued and drained there by one callback
    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    private interface Dispatch : Library {
        fun dispatch_async_f(queue: Pointer, context: Pointer?, work: Work)
    }

    fun interface Work : Callback {
        fun invoke(context: Pointer?)
    }

    private val dispatch: Dispatch = Native.load("System", Dispatch::class.java)
    private val mainQueue: Pointer = NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q")
    private val drain = Work {
        val pool = runtime.objc_autoreleasePoolPush()
        try {
            while (true) {
                val next = pending.poll() ?: break
                runCatching(next).onFailure { AppLog.w("ObjC", "Main-thread call failed", it) }
            }
        } finally {
            runtime.objc_autoreleasePoolPop(pool)
        }
    }

    fun onMain(block: () -> Unit) {
        pending.add(block)
        dispatch.dispatch_async_f(mainQueue, null, drain)
    }
}
