package com.orkgabb.fusao.pixels

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Bloco "Smart Pixels" das Configurações rápidas. Liga/desliga a mesma [SmartPixelsConfig]
 * da aba Pixels; sem a permissão de acessibilidade, abre a tela de acessibilidade.
 */
class SmartPixelsTileService : TileService() {

    private val openSettingsAction: Runnable = Runnable { openAccessibilitySettings() }

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val store: SmartPixelsStore = SmartPixelsStore.get(this)
        if (!store.accessibilityGranted()) {
            if (isLocked) unlockAndRun(openSettingsAction) else openAccessibilitySettings()
            return
        }
        store.setEnabled(!store.config.value.enabled)
        render()
    }

    private fun render() {
        val tile: Tile = qsTile ?: return
        val store: SmartPixelsStore = SmartPixelsStore.get(this)
        val cfg: SmartPixelsConfig = store.config.value
        val granted: Boolean = store.accessibilityGranted()
        tile.state = if (granted && cfg.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            !granted -> "Sem permissão"
            !cfg.enabled -> "Desligado"
            else -> "${cfg.litPercent}% acesos"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAccessibilitySettings() {
        val intent: Intent = SmartPixelsStore.get(this).accessibilitySettingsIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending: PendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_ACCESSIBILITY,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_ACCESSIBILITY: Int = 1
    }
}
