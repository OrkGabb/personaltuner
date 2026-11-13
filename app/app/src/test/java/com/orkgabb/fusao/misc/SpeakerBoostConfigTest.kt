package com.orkgabb.fusao.misc

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerBoostConfigTest {

    @Test
    fun configClampsOutOfRangeGainsToM54SafeMargin() {
        val unsafe = SpeakerBoostConfig(
            enabled = true,
            preset = SpeakerPreset.CUSTOM,
            bassBoostDb = 12.0f,
            loudnessMb = 1400,
        ).clamped()

        assertEquals(SpeakerBoostConfig.MAX_SAFE_BASS_DB, unsafe.bassBoostDb, 0.001f)
        assertEquals(SpeakerBoostConfig.MAX_SAFE_LOUDNESS_MB, unsafe.loudnessMb)
    }

    @Test
    fun subBassBelow75HzIsAttenuatedForTfa9878Safety() {
        val dsp = SpeakerBoostConfig.computeDsp(
            SpeakerBoostConfig(enabled = true, preset = SpeakerPreset.BALANCED),
            volumeRatio = 0.40f,
        )
        assertTrue("banda <75 Hz deve atenuar para poupar excursão", dsp.bandGainsDb[0] < 0f)
        assertTrue("banda 120–185 Hz concentra punch", dsp.bandGainsDb[2] > dsp.bandGainsDb[1])
        assertTrue("banda 460–800 Hz reduz som de caixa", dsp.bandGainsDb[5] < 0f)
        assertTrue("banda 3400–6200 Hz controla estridência metálica", dsp.bandGainsDb[8] <= 0f)
        assertTrue("banda >6200 Hz adiciona ar leve", dsp.bandGainsDb[9] > 0f)
        assertEquals(SpeakerBoostConfig.M54_MBC_BAND_COUNT, dsp.mbcBands.size)
        for (i in 1 until dsp.mbcBands.size) {
            assertTrue("cutoffs MBC devem ser crescentes", dsp.mbcBands[i].cutoffHz > dsp.mbcBands[i - 1].cutoffHz)
            assertTrue("ratios MBC >= 1", dsp.mbcBands[i].ratio >= 1.0f)
        }
        assertTrue("inputGain negativo preserva headroom", dsp.inputGainDb < 0f)
        assertTrue("limiter ativo abaixo de 0 dBFS", dsp.limiterThresholdDb < 0f)
    }

    @Test
    fun adaptiveVolumeTapersGainAndLoudnessAboveKnee() {
        val cfg = SpeakerBoostConfig(
            enabled = true,
            preset = SpeakerPreset.STRONG,
            adaptiveVolume = true,
        )
        val midVol = SpeakerBoostConfig.computeDsp(cfg, 0.45f)
        val fullVol = SpeakerBoostConfig.computeDsp(cfg, 1.0f)

        assertTrue(fullVol.bandGainsDb[2] < midVol.bandGainsDb[2])
        assertEquals(0, fullVol.loudnessMb)
        assertTrue(midVol.loudnessMb > 0)
        assertTrue(fullVol.limiterThresholdDb < midVol.limiterThresholdDb)
    }

    @Test
    fun outputGuardStrictlyIsolatesSpeakerAndBlocksBluetooth() {
        assertEquals(
            SpeakerOutputRoute.SPEAKER,
            SpeakerOutputGuard.classifyDeviceTypes(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)),
        )
        assertEquals(
            SpeakerOutputRoute.BLUETOOTH,
            SpeakerOutputGuard.classifyDeviceTypes(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)),
        )
        assertEquals(
            SpeakerOutputRoute.BLUETOOTH,
            SpeakerOutputGuard.classifyDeviceTypes(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)),
        )
        // Mesmo com Dual Audio / Separate App Sound mantendo speaker na mídia, BT ativo pausa o DSP
        assertEquals(
            SpeakerOutputRoute.BLUETOOTH,
            SpeakerOutputGuard.classifyDeviceTypes(
                routedTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                playbackDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            ),
        )
        assertEquals(
            SpeakerOutputRoute.EXTERNAL,
            SpeakerOutputGuard.classifyDeviceTypes(listOf(AudioDeviceInfo.TYPE_USB_HEADSET)),
        )
        assertEquals(
            SpeakerOutputRoute.EXTERNAL,
            SpeakerOutputGuard.classifyDeviceTypes(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)),
        )
        assertEquals(
            SpeakerOutputRoute.IN_CALL,
            SpeakerOutputGuard.classifyDeviceTypes(
                routedTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
            ),
        )
        assertEquals(
            SpeakerOutputRoute.UNKNOWN,
            SpeakerOutputGuard.classifyDeviceTypes(emptyList()),
        )
    }

    @Test
    fun miscSystemStateParsesHzAndTweaksAccurately() {
        val parsed = MiscSystemState.fromLines(
            listOf(
                "min_hz=60.0",
                "peak_hz=120.0",
                "touch=1",
                "w_anim=0.0",
                "t_anim=0.0",
                "a_anim=0.0",
                "r_anim=0",
                "stay_on=7",
            )
        )
        assertTrue(parsed.loaded)
        assertEquals(DisplayHzMode.ADAPTIVE, parsed.displayHzMode)
        assertTrue(parsed.touchSensitivity)
        assertTrue(parsed.animationsOff)
        assertTrue(parsed.stayOnPlugged)

        val fixed120 = MiscSystemState.classifyHz(120f, 120f)
        val fixed60 = MiscSystemState.classifyHz(60f, 60f)
        assertEquals(DisplayHzMode.FIXED_120, fixed120)
        assertEquals(DisplayHzMode.FIXED_60, fixed60)

        val animsOn = MiscSystemState.fromLines(
            listOf("min_hz=120.0", "peak_hz=120.0", "touch=0", "w_anim=1.0", "t_anim=1.0", "a_anim=1.0", "r_anim=0", "stay_on=0")
        )
        assertFalse(animsOn.animationsOff)
        assertFalse(animsOn.touchSensitivity)
        assertFalse(animsOn.stayOnPlugged)
    }
}
