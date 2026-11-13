package com.orkgabb.fusao.block

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Um evento do registro: `data hora|canal|número|decisão|detalhe`. */
@Immutable
data class BlockEvent(
    val time: String,
    val channel: String,
    val number: String,
    val verdict: String,
    val detail: String,
) {
    companion object {
        fun parse(line: String): BlockEvent? {
            val p: List<String> = line.split('|')
            if (p.size < 5) return null
            return BlockEvent(p[0], p[1], p[2], p[3], p.subList(4, p.size).joinToString("/"))
        }
    }
}

/**
 * Dono único da configuração e do registro do bloqueio. Tudo fica no armazenamento protegido do aparelho
 * (device-protected), legível antes do primeiro desbloqueio: a triagem de chamadas roda no direct boot.
 *
 * Um arquivo só, um processo só: a aba, a triagem, o receptor de SMS e o listener leem [config] na memória e
 * só [update] grava. Não há cópia em /data/adb nem espelho no armazenamento externo: o bloqueio não depende
 * de root e os números das regras não saem do diretório do app.
 */
class BlockStore private constructor(app: Context) {

    private val dir: File = app.createDeviceProtectedStorageContext().filesDir
    private val configFile: File = File(dir, CONFIG_NAME)
    private val logFile: File = File(dir, LOG_NAME)
    private val logLock: Any = Any()
    private val stamp: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _config: MutableStateFlow<BlockConfig> = MutableStateFlow(load())
    val config: StateFlow<BlockConfig> = _config.asStateFlow()

    /** Sobe a cada evento gravado ou registro apagado: a aba relê o registro quando muda. */
    private val _logVersion: MutableStateFlow<Int> = MutableStateFlow(0)
    val logVersion: StateFlow<Int> = _logVersion.asStateFlow()

    /** SMS bloqueados há pouco, para o listener reconhecer a notificação correspondente. */
    val suppression: BlockSuppression = BlockSuppression()

    fun current(): BlockConfig = _config.value

    /**
     * Aplica [transform] e grava. Devolve false (e mantém a configuração anterior valendo) se o arquivo não
     * pôde ser gravado: a memória nunca fica à frente do disco.
     */
    @Synchronized
    fun update(transform: (BlockConfig) -> BlockConfig): Boolean {
        val next: BlockConfig = transform(_config.value)
        if (next == _config.value) return true
        if (!write(next.serialize())) return false
        _config.value = next
        return true
    }

    fun log(channel: BlockChannel, number: String?, verdict: Verdict, detail: String) {
        val line: String = stampNow() + "|" + channel.name + "|" + clean(number.orEmpty().ifEmpty { "privado" }) +
            "|" + verdict.name + "|" + clean(detail) + "\n"
        synchronized(logLock) {
            try {
                if (logFile.length() > LOG_MAX_BYTES) {
                    val old = File(dir, "$LOG_NAME.1")
                    old.delete()
                    logFile.renameTo(old)
                }
                FileOutputStream(logFile, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            } catch (_: Exception) {
                // registro é melhor esforço: nunca derruba a triagem
            }
        }
        _logVersion.value = _logVersion.value + 1
    }

    /** Últimos [max] eventos, do mais novo para o mais antigo. */
    fun readLog(max: Int): List<BlockEvent> = synchronized(logLock) {
        val lines = ArrayList<String>()
        for (f in listOf(File(dir, "$LOG_NAME.1"), logFile)) {
            try {
                if (f.isFile) lines.addAll(f.readLines(Charsets.UTF_8))
            } catch (_: Exception) {
            }
        }
        lines.takeLast(max).asReversed().mapNotNull { BlockEvent.parse(it) }
    }

    fun clearLog() {
        synchronized(logLock) {
            File(dir, "$LOG_NAME.1").delete()
            logFile.delete()
        }
        _logVersion.value = _logVersion.value + 1
    }

    private fun stampNow(): String = synchronized(stamp) { stamp.format(Date()) }

    private fun clean(value: String): String = value.replace('\n', ' ').replace('\r', ' ').replace('|', '/').take(120)

    private fun load(): BlockConfig {
        val text: String = try {
            if (configFile.isFile) configFile.readText(Charsets.UTF_8) else ""
        } catch (_: Exception) {
            ""
        }
        return BlockConfig.parse(text) ?: BlockConfig()
    }

    /** Grava num temporário, sincroniza e renomeia por cima: nunca fica um arquivo pela metade. */
    private fun write(text: String): Boolean {
        val tmp = File(dir, "$CONFIG_NAME.tmp")
        return try {
            if (!dir.isDirectory) dir.mkdirs()
            FileOutputStream(tmp, false).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (tmp.renameTo(configFile)) {
                true
            } else {
                tmp.delete()
                false
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    companion object {
        const val CONFIG_NAME: String = "block.conf"
        const val LOG_NAME: String = "block.log"
        private const val LOG_MAX_BYTES: Long = 128L * 1024L

        @Volatile
        private var instance: BlockStore? = null

        fun get(context: Context): BlockStore =
            instance ?: synchronized(this) {
                instance ?: BlockStore(context.applicationContext).also { instance = it }
            }
    }
}
