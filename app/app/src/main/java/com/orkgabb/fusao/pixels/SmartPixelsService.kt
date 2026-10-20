package com.orkgabb.fusao.pixels

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço de acessibilidade que existe só para hospedar a janela da máscara.
 *
 * Por que acessibilidade: janela TYPE_APPLICATION_OVERLAY que não recebe toque é limitada
 * pelo Android a 80% de opacidade, então o "apagado" não seria preto de verdade (pixel
 * AMOLED ainda aceso). TYPE_ACCESSIBILITY_OVERLAY não tem esse limite e cobre barras e
 * recorte da câmera. O serviço não recebe eventos nem lê conteúdo de janela
 * (res/xml/smart_pixels_accessibility.xml), não usa root nem sysfs.
 *
 * Custo: o bloco só é regenerado quando muda aceso/tamanho/padrão; o movimento é um
 * deslocamento do shader + um redesenho por passo; com a tela apagada o movimento para.
 */
class SmartPixelsService : AccessibilityService() {

    private val handler: Handler = Handler(Looper.getMainLooper())
    private val stepRunnable: Runnable = Runnable { advanceStep() }

    private var scope: CoroutineScope? = null
    private var store: SmartPixelsStore? = null
    private var windowManager: WindowManager? = null
    private var maskView: PixelMaskView? = null

    /** Config desenhada agora na máscara (null = sem máscara). */
    private var drawn: SmartPixelsConfig? = null
    private var shiftMillis: Long = SmartPixelsConfig.STATIC_SHIFT_MILLIS
    private var step: Int = 0
    private var screenInteractive: Boolean = true
    private var receiverRegistered: Boolean = false

    private val screenReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    handler.removeCallbacks(stepRunnable)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    scheduleStep()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val pixelsStore: SmartPixelsStore = SmartPixelsStore.get(this)
        store = pixelsStore
        windowManager = getSystemService(WindowManager::class.java)
        screenInteractive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        registerScreenReceiver()
        pixelsStore.reportService(SmartPixelsServiceState.IDLE)

        scope?.cancel()
        val serviceScope: CoroutineScope = MainScope()
        scope = serviceScope
        serviceScope.launch {
            pixelsStore.state.collect { snapshot -> apply(snapshot) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        scope?.cancel()
        scope = null
        removeMask()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        store?.reportService(SmartPixelsServiceState.STOPPED)
    }

    /** Reavalia a visibilidade a cada mudança de config ou de perfil em vigor (regra em [SmartPixelsSnapshot]). */
    private fun apply(snapshot: SmartPixelsSnapshot) {
        val pixelsStore: SmartPixelsStore = store ?: return
        val cfg: SmartPixelsConfig = snapshot.config
        if (!snapshot.drawsMask) {
            removeMask()
            pixelsStore.reportService(SmartPixelsServiceState.IDLE)
            return
        }
        val view: PixelMaskView = maskView ?: addMask() ?: run {
            pixelsStore.reportService(SmartPixelsServiceState.IDLE)
            return
        }
        if (!cfg.sameTile(drawn)) {
            view.setTile(PixelMask.buildTile(cfg))
            view.setShift(
                PixelMask.shiftCellsX(step) * cfg.cellPx,
                PixelMask.shiftCellsY(step) * cfg.cellPx,
            )
        }
        drawn = cfg
        if (shiftMillis != cfg.shiftMillis) {
            shiftMillis = cfg.shiftMillis
            scheduleStep()
        } else if (!handler.hasCallbacks(stepRunnable)) {
            scheduleStep()
        }
        pixelsStore.reportService(SmartPixelsServiceState.MASKING)
    }

    private fun advanceStep() {
        val view: PixelMaskView = maskView ?: return
        val cfg: SmartPixelsConfig = drawn ?: return
        step = (step + 1) and (PixelMask.TILE_AREA - 1)
        view.setShift(
            PixelMask.shiftCellsX(step) * cfg.cellPx,
            PixelMask.shiftCellsY(step) * cfg.cellPx,
        )
        scheduleStep()
    }

    private fun scheduleStep() {
        handler.removeCallbacks(stepRunnable)
        if (maskView == null || !screenInteractive) return
        if (shiftMillis <= SmartPixelsConfig.STATIC_SHIFT_MILLIS) return
        handler.postDelayed(stepRunnable, shiftMillis)
    }

    private fun addMask(): PixelMaskView? {
        val wm: WindowManager = windowManager ?: return null
        val view = PixelMaskView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        params.title = WINDOW_TITLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            params.fitInsetsTypes = 0
            params.fitInsetsSides = 0
        } else {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            wm.addView(view, params)
            maskView = view
            drawn = null
            view
        } catch (e: RuntimeException) {
            Log.w(TAG, "máscara não entrou na tela", e)
            null
        }
    }

    private fun removeMask() {
        handler.removeCallbacks(stepRunnable)
        val view: PixelMaskView = maskView ?: return
        maskView = null
        drawn = null
        runCatching { windowManager?.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "máscara não saiu da tela", it) }
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private companion object {
        const val TAG: String = "FusaoPixels"
        const val WINDOW_TITLE: String = "FUSAO Smart Pixels"
    }
}
