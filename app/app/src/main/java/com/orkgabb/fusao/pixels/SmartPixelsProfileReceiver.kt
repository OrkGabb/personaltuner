package com.orkgabb.fusao.pixels

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Recebe o perfil em vigor que o módulo publica a cada aplicação de perfil (e no boot):
 *
 *   am broadcast -a com.orkgabb.fusao.action.PROFILE -p com.orkgabb.fusao --es profile <game|balanced|battery>
 *
 * Protegido por android.permission.DUMP no manifesto: root e shell têm, app de terceiro não.
 * Só grava no [SmartPixelsStore]; se o serviço da máscara estiver rodando ele reage pela
 * mesma foto que a aba e o bloco usam. Não toca em su/root.
 */
class SmartPixelsProfileReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROFILE) return
        SmartPixelsStore.get(context).setEffectiveProfile(intent.getStringExtra(EXTRA_PROFILE))
    }

    companion object {
        const val ACTION_PROFILE: String = "com.orkgabb.fusao.action.PROFILE"
        const val EXTRA_PROFILE: String = "profile"
    }
}
