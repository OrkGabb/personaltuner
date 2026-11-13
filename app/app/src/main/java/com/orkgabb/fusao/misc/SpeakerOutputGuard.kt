package com.orkgabb.fusao.misc

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build

data class RouteEvaluation(
    val route: SpeakerOutputRoute,
    val deviceLabel: String,
    val activePlayback: Boolean,
) {
    val isSpeakerOnly: Boolean get() = route == SpeakerOutputRoute.SPEAKER
}

/**
 * Classificação estrita de saída de áudio (fail-closed): só libera o DSP quando a rota ativa
 * de mídia pertence exclusivamente ao alto-falante interno (`TYPE_BUILTIN_SPEAKER`).
 * Dispositivos Bluetooth (A2DP, BLE Audio, SCO, Aparelho auditivo), USB, P2, HDMI ou chamadas
 * desligam os efeitos imediatamente para não interferir com o áudio externo.
 */
object SpeakerOutputGuard {

    private val mediaAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private val ENHANCEABLE_USAGES: Set<Int> = setOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN,
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
    )

    private val BLUETOOTH_TYPES: Set<Int> = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    private val SPEAKER_TYPES: Set<Int> = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
    )

    // Earpiece e linha de telefonia aparecem sempre em GET_DEVICES_OUTPUTS do M54
    private val PASSIVE_INTERNAL_TYPES: Set<Int> = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_TELEPHONY,
    )

    fun evaluate(audioManager: AudioManager, configs: List<AudioPlaybackConfiguration>? = null): RouteEvaluation {
        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        val activeConfigs = configs ?: runCatching { audioManager.activePlaybackConfigurations }.getOrDefault(emptyList())

        val activePlayback = runCatching { audioManager.isMusicActive }.getOrDefault(false) ||
            activeConfigs.any { it.audioAttributes.usage in ENHANCEABLE_USAGES }

        @Suppress("DEPRECATION")
        val playbackDevices: List<AudioDeviceInfo> = activeConfigs.mapNotNull { it.audioDeviceInfo }
        val playbackTypes = playbackDevices.map { it.type }

        val routedDevices: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { audioManager.getAudioDevicesForAttributes(mediaAttributes) }.getOrDefault(emptyList())
        } else {
            runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
                .getOrDefault(emptyList())
                .filter { it.type !in PASSIVE_INTERNAL_TYPES }
        }

        val routedTypes = routedDevices.map { it.type }
        val route = classifyDeviceTypes(
            routedTypes = routedTypes,
            playbackDeviceTypes = playbackTypes,
            audioMode = mode,
        )

        val preferredLabelDevice = (playbackDevices + routedDevices).firstOrNull { d ->
            when (route) {
                SpeakerOutputRoute.BLUETOOTH -> d.type in BLUETOOTH_TYPES
                SpeakerOutputRoute.EXTERNAL -> d.type !in SPEAKER_TYPES
                else -> d.type in SPEAKER_TYPES
            }
        }
        val label = preferredLabelDevice?.productName?.toString()?.trim().orEmpty()

        return RouteEvaluation(
            route = route,
            deviceLabel = label,
            activePlayback = activePlayback,
        )
    }

    internal fun classifyDeviceTypes(
        routedTypes: List<Int>,
        playbackDeviceTypes: List<Int> = emptyList(),
        audioMode: Int = AudioManager.MODE_NORMAL,
    ): SpeakerOutputRoute {
        if (audioMode != AudioManager.MODE_NORMAL) {
            return SpeakerOutputRoute.IN_CALL
        }

        val allTypes = routedTypes + playbackDeviceTypes
        if (allTypes.isEmpty()) {
            return SpeakerOutputRoute.UNKNOWN
        }
        if (allTypes.any { it in BLUETOOTH_TYPES }) {
            return SpeakerOutputRoute.BLUETOOTH
        }
        if (allTypes.any { it !in SPEAKER_TYPES }) {
            return SpeakerOutputRoute.EXTERNAL
        }
        return SpeakerOutputRoute.SPEAKER
    }
}
