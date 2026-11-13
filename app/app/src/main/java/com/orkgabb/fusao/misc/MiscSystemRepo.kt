package com.orkgabb.fusao.misc

import com.orkgabb.fusao.FusaoBridge
import com.orkgabb.fusao.RootShell

enum class DisplayHzMode(val cliArg: String, val label: String) {
    ADAPTIVE("adaptive", "Adaptável"),
    FIXED_120("120hz", "120 Hz fixo"),
    FIXED_60("60hz", "60 Hz"),
    UNKNOWN("show", "—"),
}

data class MiscSystemState(
    val loaded: Boolean = false,
    val displayHzMode: DisplayHzMode = DisplayHzMode.UNKNOWN,
    val minHzLabel: String = "",
    val peakHzLabel: String = "",
    val touchSensitivity: Boolean = false,
    val animationsOff: Boolean = false,
    val stayOnPlugged: Boolean = false,
) {
    companion object {
        fun fromLines(lines: List<String>): MiscSystemState {
            val map = buildMap {
                lines.forEach { raw ->
                    val idx = raw.indexOf('=')
                    if (idx > 0) {
                        put(raw.substring(0, idx).trim(), raw.substring(idx + 1).trim())
                    }
                }
            }

            val minHz = map["min_hz"]?.toFloatOrNull()
            val peakHz = map["peak_hz"]?.toFloatOrNull()
            val hzMode = classifyHz(minHz, peakHz)

            val wAnim = map["w_anim"]?.toFloatOrNull()
            val tAnim = map["t_anim"]?.toFloatOrNull()
            val aAnim = map["a_anim"]?.toFloatOrNull()
            val rAnim = map["r_anim"]?.toIntOrNull()
            val animOff = (rAnim == 1) || (
                wAnim != null && tAnim != null && aAnim != null &&
                    wAnim <= 0.01f && tAnim <= 0.01f && aAnim <= 0.01f
                )

            val touch = map["touch"]?.toIntOrNull() == 1
            val stayOn = (map["stay_on"]?.toIntOrNull() ?: 0) > 0

            return MiscSystemState(
                loaded = map.isNotEmpty(),
                displayHzMode = hzMode,
                minHzLabel = minHz?.let { "${it.toInt()}" }.orEmpty(),
                peakHzLabel = peakHz?.let { "${it.toInt()}" }.orEmpty(),
                touchSensitivity = touch,
                animationsOff = animOff,
                stayOnPlugged = stayOn,
            )
        }

        internal fun classifyHz(minHz: Float?, peakHz: Float?): DisplayHzMode = when {
            minHz == null || peakHz == null -> DisplayHzMode.UNKNOWN
            minHz >= 119f && peakHz >= 119f -> DisplayHzMode.FIXED_120
            peakHz <= 61f && minHz <= 61f -> DisplayHzMode.FIXED_60
            peakHz >= 119f && minHz <= 61f -> DisplayHzMode.ADAPTIVE
            else -> DisplayHzMode.UNKNOWN
        }
    }
}

/** Leitura e aplicação das chaves reais de sistema da aba Misc (requer root). */
object MiscSystemRepo {

    suspend fun read(): MiscSystemState {
        val script = buildString {
            append("echo \"min_hz=\$(settings get system min_refresh_rate 2>/dev/null)\"\n")
            append("echo \"peak_hz=\$(settings get system peak_refresh_rate 2>/dev/null)\"\n")
            append("echo \"touch=\$(settings get system touch_sensitivity 2>/dev/null)\"\n")
            append("echo \"w_anim=\$(settings get global window_animation_scale 2>/dev/null)\"\n")
            append("echo \"t_anim=\$(settings get global transition_animation_scale 2>/dev/null)\"\n")
            append("echo \"a_anim=\$(settings get global animator_duration_scale 2>/dev/null)\"\n")
            append("echo \"r_anim=\$(settings get global remove_animations 2>/dev/null)\"\n")
            append("echo \"stay_on=\$(settings get global stay_on_while_plugged_in 2>/dev/null)\"\n")
        }
        val res = RootShell.run(script)
        if (!res.isSuccess) return MiscSystemState(loaded = false)
        return MiscSystemState.fromLines(res.output)
    }

    suspend fun setDisplayHz(mode: DisplayHzMode): Pair<String, MiscSystemState> {
        if (mode == DisplayHzMode.UNKNOWN) return "Modo inválido" to read()
        val msg = FusaoBridge.setDisplay(mode.cliArg)
        val refreshed = read()
        return msg to refreshed
    }

    suspend fun setTouchSensitivity(enabled: Boolean): Pair<Boolean, MiscSystemState> {
        val v = if (enabled) "1" else "0"
        val r = RootShell.run("settings put system touch_sensitivity $v")
        val state = read()
        return (r.isSuccess && state.touchSensitivity == enabled) to state
    }

    suspend fun setAnimationsOff(off: Boolean): Pair<Boolean, MiscSystemState> {
        val scale = if (off) "0.0" else "1.0"
        val flag = if (off) "1" else "0"
        val r = RootShell.run(
            "settings put global window_animation_scale $scale && " +
                "settings put global transition_animation_scale $scale && " +
                "settings put global animator_duration_scale $scale && " +
                "settings put global remove_animations $flag && " +
                "settings put system remove_animations $flag"
        )
        val state = read()
        return (r.isSuccess && state.animationsOff == off) to state
    }

    suspend fun setStayOnPlugged(enabled: Boolean): Pair<Boolean, MiscSystemState> {
        val v = if (enabled) "7" else "0"
        val r = RootShell.run("settings put global stay_on_while_plugged_in $v")
        val state = read()
        return (r.isSuccess && state.stayOnPlugged == enabled) to state
    }
}
