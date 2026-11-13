package com.orkgabb.fusao.block

import android.app.Notification
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.SmsMessage
import android.telephony.TelephonyManager
import java.util.Calendar

/** Hora e minuto locais de agora, para o horário de silêncio. */
private fun nowHourMinute(): Pair<Int, Int> {
    val now: Calendar = Calendar.getInstance()
    return now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE)
}

/**
 * Consulta direta à agenda (PhoneLookup casa formatos e código do país). Síncrona de propósito: a triagem quase
 * sempre sobe a frio para a chamada, e um índice carregado em segundo plano ainda estaria vazio na primeira (e
 * normalmente única) chamada do processo. null = agenda indisponível (sem permissão, aparelho ainda bloqueado
 * depois do boot): a regra "fora dos contatos" é pulada.
 */
internal fun lookupContact(context: Context, number: String?): Boolean? {
    if (number.isNullOrEmpty()) return null
    return try {
        val uri: Uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { it.moveToFirst() }
    } catch (_: Exception) {
        null
    }
}

/**
 * Triagem de chamadas: o sistema chama [onScreenCall] antes de tocar, para o detentor do papel
 * `android.app.role.CALL_SCREENING`. Toda saída responde ao sistema; na dúvida (erro, configuração ilegível)
 * a chamada passa.
 */
class BlockCallScreener : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val response: CallResponse = try {
            decide(details)
        } catch (_: Exception) {
            CallResponse.Builder().build()
        }
        try {
            respondToCall(details, response)
        } catch (_: Exception) {
        }
    }

    private fun decide(details: Call.Details): CallResponse {
        val allow: CallResponse = CallResponse.Builder().build()
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return allow
        val number: String? = details.handle?.schemeSpecificPart
        if (isEmergency(number)) return allow

        val store: BlockStore = BlockStore.get(this)
        val (hour: Int, minute: Int) = nowHourMinute()
        val decision: BlockDecision = BlockEngine.evaluate(
            cfg = store.current(),
            rawNumber = number,
            channel = BlockChannel.CALL,
            hour = hour,
            minute = minute,
            isContact = { lookupContact(this, number) },
        )
        return when (decision.verdict) {
            Verdict.ALLOW -> allow
            // rejeitada: fica no histórico de chamadas, sem tocar e sem notificação de chamada perdida
            Verdict.BLOCK -> {
                store.log(BlockChannel.CALL, number, Verdict.BLOCK, decision.reason)
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(true)
                    .build()
            }
            Verdict.SILENCE -> {
                store.log(BlockChannel.CALL, number, Verdict.SILENCE, decision.reason)
                CallResponse.Builder()
                    .setSilenceCall(true)
                    .build()
            }
        }
    }

    /** Número de emergência nunca é bloqueado, antes de qualquer regra. */
    private fun isEmergency(number: String?): Boolean {
        if (number.isNullOrEmpty()) return false
        return try {
            getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number) == true
        } catch (_: Exception) {
            BlockEngine.normalize(number) in FALLBACK_EMERGENCY
        }
    }

    private companion object {
        /** Só se o sistema não souber responder (sem telefonia pronta). */
        val FALLBACK_EMERGENCY: Set<String> = setOf("190", "192", "193", "112", "911")
    }
}

/**
 * `SMS_RECEIVED` chega a apps que não são o app de SMS padrão quando `RECEIVE_SMS` está concedida. Dá o número
 * real do remetente, que a notificação nem sempre mostra. Não apaga nem intercepta a mensagem: só marca o
 * remetente e o texto para o listener cancelar a notificação.
 */
class BlockSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            handle(context, intent)
        } catch (_: Exception) {
            // nunca atrapalha a entrega do SMS
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val parts: Array<SmsMessage?> = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // um SMS longo chega em partes do mesmo remetente
        val bodies = LinkedHashMap<String, StringBuilder>()
        for (sms in parts) {
            if (sms == null) continue
            val sender: String = sms.displayOriginatingAddress ?: sms.originatingAddress ?: ""
            val body: String = sms.displayMessageBody ?: sms.messageBody ?: ""
            bodies.getOrPut(sender) { StringBuilder() }.append(body)
        }
        val store: BlockStore = BlockStore.get(context)
        val cfg: BlockConfig = store.current()
        val (hour: Int, minute: Int) = nowHourMinute()
        var blockedAny = false
        for ((sender: String, body: StringBuilder) in bodies) {
            val decision: BlockDecision =
                BlockEngine.evaluate(cfg, sender, BlockChannel.SMS, hour, minute, isContact = { null })
            if (!decision.blocks) continue
            store.suppression.add(sender, body.toString(), System.currentTimeMillis())
            store.log(BlockChannel.SMS, sender, Verdict.BLOCK, decision.reason)
            blockedAny = true
        }
        // o app de SMS padrão recebe a mensagem antes deste broadcast: a notificação já pode estar na tela
        if (blockedAny) BlockNotificationListener.sweep()
    }
}

/**
 * Cancela a notificação de SMS de remetente bloqueado. É assim que o SMS é "bloqueado" sem virar o app de SMS
 * padrão (o que quebraria MMS e o app de mensagens): a mensagem continua no histórico, só não avisa.
 */
class BlockNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        sweepActive()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
        } catch (_: Exception) {
        }
    }

    private fun sweepActive() {
        val active: Array<StatusBarNotification> = try {
            activeNotifications ?: return
        } catch (_: Exception) {
            return
        }
        for (sbn in active) {
            try {
                handle(sbn)
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg: String = sbn.packageName ?: return
        if (pkg == packageName) return
        val notification: Notification = sbn.notification ?: return
        if (notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return

        val store: BlockStore = BlockStore.get(this)
        val cfg: BlockConfig = store.current()
        if (!cfg.enabled || !cfg.blockSms || pkg !in cfg.notifPackages) return
        val extras: Bundle = notification.extras ?: return

        val senders: List<String> = SenderFields.candidates(senderFields(extras))
        // já decidido (e registrado) pelo receptor de SMS: só some com a notificação
        val suppressed: Boolean = store.suppression.matches(
            senderDigits = senders.map { BlockEngine.normalize(it) },
            text = fullText(extras),
            nowMillis = System.currentTimeMillis(),
        )
        if (suppressed) {
            cancelNotification(sbn.key)
            return
        }
        // sem RECEIVE_SMS (ou o broadcast ainda não chegou): decide pelo remetente que a notificação mostra
        val (hour: Int, minute: Int) = nowHourMinute()
        for (sender in senders) {
            val decision: BlockDecision =
                BlockEngine.evaluate(cfg, sender, BlockChannel.SMS, hour, minute, isContact = { null })
            if (!decision.blocks) continue
            cancelNotification(sbn.key)
            // com RECEIVE_SMS o receptor registra todo SMS bloqueado; registrar aqui também duplicaria a linha
            if (!BlockAccess.hasPermission(this, android.Manifest.permission.RECEIVE_SMS)) {
                store.log(BlockChannel.SMS, sender, Verdict.BLOCK, decision.reason + " [" + pkg + "]")
            }
            return
        }
    }

    /** Campos que dizem QUEM mandou; o corpo da mensagem não entra (ver [SenderFields]). */
    private fun senderFields(extras: Bundle): List<CharSequence?> {
        val out = ArrayList<CharSequence?>()
        out.add(extras.getCharSequence(Notification.EXTRA_TITLE))
        out.add(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        forEachMessage(extras) { message: Bundle ->
            out.add(message.getCharSequence(KEY_MESSAGE_SENDER))
            val person: Person? = personOf(message)
            out.add(person?.name)
            out.add(person?.uri)
        }
        @Suppress("DEPRECATION")
        val people: ArrayList<Person>? = try {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
        } catch (_: Exception) {
            null
        }
        people?.forEach { person: Person? ->
            out.add(person?.name)
            out.add(person?.uri)
        }
        return out
    }

    private fun fullText(extras: Bundle): String {
        val sb = StringBuilder(256)
        fun add(value: CharSequence?) {
            if (!value.isNullOrEmpty()) sb.append(value).append(' ')
        }
        add(extras.getCharSequence(Notification.EXTRA_TITLE))
        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        forEachMessage(extras) { message: Bundle -> add(message.getCharSequence(KEY_MESSAGE_TEXT)) }
        return sb.toString()
    }

    private inline fun forEachMessage(extras: Bundle, block: (Bundle) -> Unit) {
        @Suppress("DEPRECATION")
        val messages: Array<android.os.Parcelable>? = try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } catch (_: Exception) {
            null
        }
        messages?.forEach { if (it is Bundle) block(it) }
    }

    private fun personOf(message: Bundle): Person? = try {
        @Suppress("DEPRECATION")
        message.getParcelable(KEY_MESSAGE_SENDER_PERSON)
    } catch (_: Exception) {
        null
    }

    companion object {
        // chaves do Bundle de cada mensagem do MessagingStyle (Notification.MessagingStyle.Message)
        private const val KEY_MESSAGE_TEXT: String = "text"
        private const val KEY_MESSAGE_SENDER: String = "sender"
        private const val KEY_MESSAGE_SENDER_PERSON: String = "sender_person"

        @Volatile
        private var instance: BlockNotificationListener? = null

        /** Reavalia as notificações que já estão na tela (chamado quando um SMS acaba de ser bloqueado). */
        fun sweep() {
            instance?.sweepActive()
        }
    }
}
