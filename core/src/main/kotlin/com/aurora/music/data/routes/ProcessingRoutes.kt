package com.aurora.music.data.routes

import com.aurora.music.data.sha256Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProcessingRouteKind { ANDROID, IDLE, UNKNOWN, NATIVE_USB, CAST, MIX }
enum class OutputDeviceCategory(val label: String) {
    SPEAKER("Speaker"), EARPIECE("Earpiece"), HEADPHONES("Wired headphones"),
    BLUETOOTH("Bluetooth output"), USB("USB audio"), HDMI("HDMI output"), OTHER("Android output"),
}

data class ProcessingRoute(
    val kind: ProcessingRouteKind = ProcessingRouteKind.IDLE,
    val key: String? = null,
    val label: String = "No active output",
    val detail: String = "Start playback to detect the output.",
    val legacyKey: String? = null,
    val category: OutputDeviceCategory? = null,
    val androidDeviceId: Int? = null,
    val bypassesProcessing: Boolean = false,
) {
    // device eq bindings follow android and native usb outputs, full output presets stay android only
    val switchesDeviceEq get() = key != null && (kind == ProcessingRouteKind.ANDROID || kind == ProcessingRouteKind.NATIVE_USB)
    val bindsDeviceEq get() = switchesDeviceEq && !bypassesProcessing
}

data class RouteObservation(val generation: Long = 0, val route: ProcessingRoute = ProcessingRoute())

class ProcessingRouteMonitor {
    private val mutable = MutableStateFlow(RouteObservation())
    val observations = mutable.asStateFlow()
    val current get() = mutable.value

    @Synchronized fun publish(route: ProcessingRoute) {
        if (route != mutable.value.route) mutable.value = RouteObservation(mutable.value.generation + 1, route)
    }
}

data class RoutePresetBinding(val routeKey: String, val routeLabel: String, val headphones: String, val presetId: String)
data class ProcessingRouteRules(
    val enabled: Boolean = false,
    val bindings: List<RoutePresetBinding> = emptyList(),
    val headphones: Map<String, String> = emptyMap(),
    val manual: Set<String> = emptySet(),
) {
    fun binding(routeKey: String): RoutePresetBinding? = bindings.firstOrNull {
        it.routeKey == routeKey && it.headphones == headphones[routeKey].orEmpty()
    }
}

object RouteIdentity {
    fun key(type: Int, address: String, builtIn: Boolean, unique: Boolean, usbSerialIdentity: String? = null): String? {
        if (!unique) return null
        val identity = when {
            builtIn -> "built-in"
            type in setOf(11, 12, 22) -> usbSerialIdentity?.takeIf { it.isNotBlank() } ?: return null
            address.isBlank() || address.trim().lowercase() in setOf("02:00:00:00:00:00", "00:00:00:00:00:00") -> return null
            else -> address.trim().lowercase()
        }
        return "android:$type:${sha256Hex(identity)}"
    }
}

data class RouteApplicationStatus(val message: String = "No output rule applied.", val presetId: String? = null)

object RouteRuleDecision {
    fun preset(observation: RouteObservation, rules: ProcessingRouteRules): String? {
        val route = observation.route
        val key = route.key ?: return null
        if (route.kind != ProcessingRouteKind.ANDROID || !rules.enabled || key in rules.manual) return null
        return rules.binding(key)?.presetId
    }

    fun mayApply(expected: RouteObservation, current: RouteObservation, rules: ProcessingRouteRules, presetId: String): Boolean =
        expected == current && preset(expected, rules) == presetId
}
