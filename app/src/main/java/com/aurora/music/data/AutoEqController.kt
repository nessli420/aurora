package com.aurora.music.data

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AutoEqController(
    context: Context,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun toast(msg: String) = mainHandler.post {
        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
    @Volatile private var lastToastKey: String = ""

    @Volatile private var enabled = false
    @Volatile private var bindings: List<EqBinding> = emptyList()

    init {
        scope.launch { settingsStore.autoEqAutoSwitch.collect { enabled = it; applyForCurrent() } }
        scope.launch { settingsStore.eqBindings.collect { bindings = it; applyForCurrent() } }
        runCatching {
            am?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = applyForCurrent()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = applyForCurrent()
            }, Handler(Looper.getMainLooper()))
        }
    }

    fun currentOutputLabel(): String = currentOutput()?.let { label(it) } ?: "Speaker"
    fun currentOutputKey(): String = currentOutput()?.let { keyOf(it) } ?: "speaker"

    private fun currentOutput(): AudioDeviceInfo? =
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.minByOrNull { priority(it.type) }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 2
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 3
        else -> 9
    }

    private fun keyOf(d: AudioDeviceInfo): String = "${d.type}:${d.productName}"

    private fun label(d: AudioDeviceInfo): String =
        d.productName?.toString()?.trim()?.ifBlank { null } ?: typeName(d.type)

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        else -> "Speaker"
    }

    private fun applyForCurrent() {
        if (!enabled) return
        val key = currentOutputKey()
        val b = bindings.firstOrNull { it.deviceKey == key }
        val notify = key != lastToastKey
        lastToastKey = key
        // never wipe a manually-set correction on an unbound device
        if (b == null) return
        scope.launch {
            settingsStore.setDspParametric(b.bands)
            settingsStore.setDspPreamp(b.preampDb)
            settingsStore.setDspMode(DspMode.CUSTOM)
            settingsStore.setActiveEqProfile(b.profileName)
            if (notify) toast("AutoEQ: ${b.profileName} → ${b.deviceLabel}")
        }
    }
}
