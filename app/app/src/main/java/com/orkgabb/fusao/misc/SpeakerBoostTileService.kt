package com.orkgabb.fusao.misc

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Bloco das Configurações rápidas para o Speaker Boost (apenas alto-falante interno). */
class SpeakerBoostTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val store = SpeakerBoostStore.get(this)
        store.setEnabled(!store.state.value.config.enabled)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val ui = SpeakerBoostStore.get(this).state.value
        val enabled = ui.config.enabled
        val rt = ui.runtime

        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle = when {
            !enabled -> "Desligado"
            rt.route == SpeakerOutputRoute.BLUETOOTH -> "Pausado · Bluetooth"
            rt.route == SpeakerOutputRoute.EXTERNAL -> "Pausado · Fone/Externo"
            rt.route == SpeakerOutputRoute.IN_CALL -> "Pausado · Chamada"
            rt.effectsAttached -> "${ui.config.preset.label} · Speaker"
            else -> "${ui.config.preset.label} · Aguardando"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, SpeakerBoostTileService::class.java),
                )
            }
        }
    }
}
