package com.orkgabb.fusao.misc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.orkgabb.fusao.MainActivity
import com.orkgabb.fusao.R
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Serviço em primeiro plano responsável pelo DSP de grave/volume exclusivamente no
 * alto-falante interno (`TYPE_BUILTIN_SPEAKER`). Libera e desliga os efeitos imediatamente
 * quando qualquer saída Bluetooth, fone/USB ou chamada entra em uso.
 */
class SpeakerBoostService : Service() {

    private lateinit var store: SpeakerBoostStore
    private lateinit var audioManager: AudioManager
    private lateinit var dspThread: HandlerThread
    private lateinit var dspHandler: Handler

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var lastAppliedTarget: SpeakerDspTarget? = null
    private var lastNotificationText: String = ""

    private var modeListener: Any? = null

    private val idleReleaseRunnable = Runnable {
        releaseEffects()
        refreshStateLocked(forceEvalPlayback = false)
    }

    private val activeTickRunnable = object : Runnable {
        override fun run() {
            evaluateAndApply(configs = null)
            val rt = store.state.value.runtime
            if (store.state.value.config.enabled && rt.effectsAttached) {
                dspHandler.postDelayed(this, ACTIVE_POLL_MS)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scheduleEvaluate(delayMs = 0L)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            scheduleEvaluate(delayMs = 0L)
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            dspHandler.removeCallbacks(idleReleaseRunnable)
            dspHandler.post { evaluateAndApply(configs = configs) }
        }
    }

    private val volumeObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            scheduleEvaluate(delayMs = 0L)
        }
    }

    private val audioEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleEvaluate(delayMs = 0L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SpeakerBoostStore.get(this)
        audioManager = getSystemService(AudioManager::class.java)
        dspThread = HandlerThread("FusaoSpeakerDsp").apply { start() }
        dspHandler = Handler(dspThread.looper)

        ensureChannel()
        enterForeground("Inicializando proteção de saída...")

        audioManager.registerAudioDeviceCallback(deviceCallback, dspHandler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, dspHandler)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { scheduleEvaluate(0L) }
            modeListener = listener
            val executor = Executor { r -> dspHandler.post(r) }
            audioManager.addOnModeChangedListener(executor, listener)
        }

        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(ACTION_VOLUME_CHANGED)
        }
        ContextCompat.registerReceiver(this, audioEventReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        store.updateRuntime { it.copy(running = true, lastError = "") }
        scheduleEvaluate(0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            else -> {
                if (!store.state.value.config.enabled) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                enterForeground(lastNotificationText.ifEmpty { "Monitorando saída de áudio..." })
                scheduleEvaluate(0L)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        dspHandler.removeCallbacksAndMessages(null)
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let {
                runCatching { audioManager.removeOnModeChangedListener(it) }
            }
        }
        runCatching { contentResolver.unregisterContentObserver(volumeObserver) }
        runCatching { unregisterReceiver(audioEventReceiver) }

        releaseEffects()
        store.updateRuntime {
            SpeakerBoostRuntime(
                running = false,
                route = it.route,
                routeDeviceName = it.routeDeviceName,
            )
        }
        SpeakerBoostTileService.requestUpdate(this)
        dspThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSelfSafely() {
        dspHandler.removeCallbacksAndMessages(null)
        releaseEffects()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun scheduleEvaluate(delayMs: Long) {
        if (delayMs <= 0L) {
            dspHandler.post { evaluateAndApply(configs = null) }
        } else {
            dspHandler.postDelayed({ evaluateAndApply(configs = null) }, delayMs)
        }
    }

    private fun evaluateAndApply(configs: List<AudioPlaybackConfiguration>?) {
        val cfg = store.state.value.config
        if (!cfg.enabled) {
            releaseEffects()
            stopSelfSafely()
            return
        }

        val evaluation = SpeakerOutputGuard.evaluate(audioManager, configs)
        if (!evaluation.isSpeakerOnly) {
            // Garantia absoluta: fora do speaker (Bluetooth, USB, fone, chamada), zera imediatamente
            dspHandler.removeCallbacks(idleReleaseRunnable)
            dspHandler.removeCallbacks(activeTickRunnable)
            releaseEffects()
            publishRuntime(evaluation, target = null, attached = false, error = "")
            return
        }

        if (!evaluation.activePlayback) {
            if (dynamicsProcessing != null || loudnessEnhancer != null) {
                dspHandler.removeCallbacks(idleReleaseRunnable)
                dspHandler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_MS)
            } else {
                publishRuntime(evaluation, target = null, attached = false, error = "")
            }
            return
        }

        dspHandler.removeCallbacks(idleReleaseRunnable)
        val volumeRatio = readMusicVolumeRatio()
        val target = SpeakerBoostConfig.computeDsp(cfg, volumeRatio)
        val error = applyEffects(target)
        val attached = (dynamicsProcessing != null || loudnessEnhancer != null) && error.isEmpty()

        publishRuntime(evaluation, target, attached, error)

        dspHandler.removeCallbacks(activeTickRunnable)
        if (attached) {
            dspHandler.postDelayed(activeTickRunnable, ACTIVE_POLL_MS)
        }
    }

    private fun refreshStateLocked(forceEvalPlayback: Boolean) {
        val evaluation = SpeakerOutputGuard.evaluate(audioManager, if (forceEvalPlayback) null else emptyList())
        publishRuntime(evaluation, target = null, attached = false, error = "")
    }

    private fun publishRuntime(
        evaluation: RouteEvaluation,
        target: SpeakerDspTarget?,
        attached: Boolean,
        error: String,
    ) {
        val volumePct = (readMusicVolumeRatio() * 100f).roundToInt()
        val (ampTemp, ampExc) = store.readAmpTelemetry()
        val effBass = if (attached && target != null) target.bandGainsDb[2] else 0f
        val effLoud = if (attached && target != null) target.loudnessMb else 0

        store.updateRuntime {
            SpeakerBoostRuntime(
                running = true,
                route = evaluation.route,
                routeDeviceName = evaluation.deviceLabel,
                effectsAttached = attached,
                activePlayback = evaluation.activePlayback,
                volumePercent = volumePct,
                effectiveBassDb = effBass,
                effectiveLoudnessMb = effLoud,
                ampTempC = ampTemp,
                ampExcursionMaxMm = ampExc,
                lastError = error,
            )
        }

        val notifText = when {
            error.isNotEmpty() -> error
            evaluation.route == SpeakerOutputRoute.BLUETOOTH -> "Pausado · Bluetooth detectado"
            evaluation.route == SpeakerOutputRoute.EXTERNAL -> "Pausado · Fone/saída externa"
            evaluation.route == SpeakerOutputRoute.IN_CALL -> "Pausado · Chamada em curso"
            attached -> "Speaker ativo · +${"%.1f".format(effBass)} dB · vol $volumePct%"
            else -> "Armado para speaker · aguardando áudio"
        }
        updateNotification(notifText)
        SpeakerBoostTileService.requestUpdate(this)
    }

    private fun readMusicVolumeRatio(): Float {
        val min = runCatching { audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val max = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
        val cur = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val span = (max - min).coerceAtLeast(1)
        return ((cur - min).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    private fun applyEffects(target: SpeakerDspTarget): String {
        return try {
            val dp = ensureDynamicsProcessing()
            val le = ensureLoudnessEnhancer()

            if (lastAppliedTarget != target) {
                val bands = target.bandCutoffsHz.size
                val eq = DynamicsProcessing.Eq(true, true, bands)
                for (i in 0 until bands) {
                    eq.getBand(i).apply {
                        isEnabled = true
                        cutoffFrequency = target.bandCutoffsHz[i]
                        gain = target.bandGainsDb[i]
                    }
                }
                val mbcCount = target.mbcBands.size
                val mbc = DynamicsProcessing.Mbc(true, true, mbcCount)
                for (i in 0 until mbcCount) {
                    val spec = target.mbcBands[i]
                    mbc.getBand(i).apply {
                        isEnabled = true
                        cutoffFrequency = spec.cutoffHz
                        threshold = spec.thresholdDb
                        ratio = spec.ratio
                        attackTime = spec.attackMs
                        releaseTime = spec.releaseMs
                        kneeWidth = spec.kneeWidthDb
                        noiseGateThreshold = -90.0f
                        expanderRatio = 1.0f
                        preGain = 0.0f
                        postGain = spec.postGainDb
                    }
                }
                dp.setInputGainAllChannelsTo(target.inputGainDb)
                dp.setMbcAllChannelsTo(mbc)
                dp.setPostEqAllChannelsTo(eq)

                val limiter = DynamicsProcessing.Limiter(
                    true,
                    true,
                    0,
                    target.limiterAttackMs,
                    target.limiterReleaseMs,
                    target.limiterRatio,
                    target.limiterThresholdDb,
                    0.0f,
                )
                dp.setLimiterAllChannelsTo(limiter)

                le.setTargetGain(target.loudnessMb)
                lastAppliedTarget = target
            }

            if (!dp.enabled) dp.enabled = true
            if (target.loudnessMb > 0) {
                if (!le.enabled) le.enabled = true
            } else {
                if (le.enabled) le.enabled = false
            }

            if (!dp.hasControl()) {
                Log.w(TAG, "Controle do DynamicsProcessing cedido a outro app")
            }
            ""
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao aplicar DSP na sessão 0", t)
            releaseEffects()
            t.message ?: "falha ao aplicar DSP"
        }
    }

    private fun ensureDynamicsProcessing(): DynamicsProcessing {
        val existing = dynamicsProcessing
        if (existing != null) return existing

        val bands = SpeakerBoostConfig.M54_BAND_CUTOFFS_HZ.size
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNEL_COUNT,
            false,
            0,
            true,
            SpeakerBoostConfig.M54_MBC_BAND_COUNT,
            true,
            bands,
            true,
        ).build()

        return DynamicsProcessing(Int.MAX_VALUE, GLOBAL_SESSION, cfg).also { effect ->
            effect.setControlStatusListener { _, hasControl ->
                if (!hasControl && store.state.value.config.enabled) {
                    scheduleEvaluate(350L)
                }
            }
            effect.setEnableStatusListener { _, isEnabled ->
                if (!isEnabled && store.state.value.config.enabled && store.state.value.runtime.effectsAttached) {
                    scheduleEvaluate(350L)
                }
            }
            dynamicsProcessing = effect
            lastAppliedTarget = null
        }
    }

    private fun ensureLoudnessEnhancer(): LoudnessEnhancer {
        val existing = loudnessEnhancer
        if (existing != null) return existing
        return LoudnessEnhancer(GLOBAL_SESSION).also { effect ->
            loudnessEnhancer = effect
            lastAppliedTarget = null
        }
    }

    private fun releaseEffects() {
        lastAppliedTarget = null
        runCatching {
            dynamicsProcessing?.apply {
                enabled = false
                release()
            }
        }
        dynamicsProcessing = null

        runCatching {
            loudnessEnhancer?.apply {
                enabled = false
                release()
            }
        }
        loudnessEnhancer = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Speaker Boost (M54)",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "DSP exclusivo do alto-falante interno; desliga em Bluetooth e fones."
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun enterForeground(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        lastNotificationText = text
    }

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_speaker_boost)
            .setContentTitle("FUSAO · Speaker Boost")
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "FusaoSpeaker"
        private const val CHANNEL_ID = "fusao_speaker_boost"
        private const val NOTIFICATION_ID = 4207
        private const val GLOBAL_SESSION = 0
        private const val CHANNEL_COUNT = 2
        private const val IDLE_RELEASE_MS = 2500L
        private const val ACTIVE_POLL_MS = 1000L
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_STOP = "com.orkgabb.fusao.misc.SPEAKER_BOOST_STOP"
        private const val ACTION_REFRESH = "com.orkgabb.fusao.misc.SPEAKER_BOOST_REFRESH"

        fun sync(context: Context, enable: Boolean) {
            val app = context.applicationContext
            if (enable) {
                runCatching {
                    ContextCompat.startForegroundService(app, Intent(app, SpeakerBoostService::class.java))
                }.onFailure { err ->
                    Log.w(TAG, "Não foi possível iniciar o SpeakerBoostService agora", err)
                    SpeakerBoostStore.get(app).updateRuntime {
                        it.copy(
                            running = false,
                            lastError = "Reabra o app para ativar o serviço (${err.javaClass.simpleName})",
                        )
                    }
                }
            } else {
                runCatching { app.stopService(Intent(app, SpeakerBoostService::class.java)) }
            }
        }

        fun refreshIfRunning(context: Context, enable: Boolean) {
            if (!enable) return
            val app = context.applicationContext
            val store = SpeakerBoostStore.get(app)
            if (store.state.value.runtime.running) {
                runCatching {
                    app.startService(
                        Intent(app, SpeakerBoostService::class.java).setAction(ACTION_REFRESH),
                    )
                }
            } else {
                sync(app, true)
            }
        }
    }
}
