package com.orkgabb.fusao.misc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Religa o Speaker Boost após boot ou atualização do app caso o usuário tenha ativado a opção.
 * Trata com segurança restrições de Foreground Service no Android 14+.
 */
class SpeakerBoostBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val cfg = SpeakerBoostStore.get(context).state.value.config
        if (cfg.enabled && cfg.startOnBoot) {
            SpeakerBoostService.sync(context, enable = true)
        }
    }
}
