package com.orkgabb.fusao.block

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Immutable

/** O que o sistema já concedeu ao bloqueio, lido pelo próprio app (não precisa de root). */
@Immutable
data class BlockAccessState(
    /** Papel de triagem de chamadas: sem ele nenhuma chamada passa pelo app. */
    val role: Boolean = false,
    /** Acesso a notificações: sem ele a notificação do SMS não é cancelada. */
    val listener: Boolean = false,
    /** RECEIVE_SMS: dá o número real do remetente. */
    val sms: Boolean = false,
    /** READ_CONTACTS: regra "fora dos contatos" e triagem também de quem está na agenda. */
    val contacts: Boolean = false,
) {
    val callsReady: Boolean get() = role
    val smsReady: Boolean get() = listener
    val complete: Boolean get() = role && listener && sms && contacts
    val grantedCount: Int get() = listOf(role, listener, sms, contacts).count { it }
}

object BlockAccess {

    val RUNTIME_PERMISSIONS: Array<String> = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS)

    fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context): BlockAccessState {
        val role: Boolean = try {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } catch (_: Exception) {
            false
        }
        val listener: Boolean = try {
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(ComponentName(context, BlockNotificationListener::class.java)) == true
        } catch (_: Exception) {
            false
        }
        return BlockAccessState(
            role = role,
            listener = listener,
            sms = hasPermission(context, Manifest.permission.RECEIVE_SMS),
            contacts = hasPermission(context, Manifest.permission.READ_CONTACTS),
        )
    }

    /** Pedido do papel pela tela do sistema (caminho sem root); null se o aparelho não tem o papel. */
    fun roleRequestIntent(context: Context): Intent? = try {
        context.getSystemService(RoleManager::class.java)
            ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) }
            ?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    } catch (_: Exception) {
        null
    }

    fun listenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
