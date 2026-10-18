package com.orkgabb.fusao

import androidx.compose.runtime.Immutable

/**
 * O único canal do app até o módulo: chama o CLI `fusao` (que gerencia
 * flags, perfis e preload em /data/adb/fusao) e lê de volta a telemetria real
 * verificada pelo backend.
 */
object FusaoBridge {

    private const val MOD_CLI = "/data/adb/modules/encore/system/bin/fusaod"
    private const val MOD_DIR = "/data/adb/modules/encore"
    private const val ALT_MOD_DIR = "/data/adb/modules/fusao"
    private const val DATA_DIR = "/data/adb/fusao"

    /** Resolve o binário do CLI uma vez por sessão (symlink do KSU/AP ou módulo). */
    @Volatile
    private var cachedCli: String? = null

    fun clearCache() {
        cachedCli = null
    }

    suspend fun cli(): String {
        cachedCli?.let { return it }
        val r = RootShell.run(
            "command -v fusao 2>/dev/null; " +
                "for b in /data/adb/ksu/bin/fusao /data/adb/ap/bin/fusao " +
                "\"$MOD_DIR/system/bin/fusao\" \"$ALT_MOD_DIR/system/bin/fusao\" /system/bin/fusao; do " +
                "[ -x \"\$b\" ] && echo \"\$b\" && break; done"
        )
        val found = r.output.map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (found.isNotEmpty()) cachedCli = found
        return found
    }

    suspend fun modulePresent(): Boolean =
        RootShell.run(
            "if [ -x \"$MOD_CLI\" ] || [ -x \"$ALT_MOD_DIR/system/bin/fusaod\" ] || [ -d \"$DATA_DIR\" ]; then echo Y; else echo N; fi"
        ).output.any { it.trim() == "Y" }

    suspend fun moduleVersion(): String {
        val r = RootShell.run(
            "grep -E '^version=' \"$MOD_DIR/module.prop\" \"$ALT_MOD_DIR/module.prop\" 2>/dev/null | head -n 1 | sed 's/^[^:]*://'; " +
                "[ -f /data/adb/modules_update/encore/module.prop ] && " +
                "echo \"staged:\$(grep -E '^version=' /data/adb/modules_update/encore/module.prop 2>/dev/null | head -n 1)\""
        )
        return r.output.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ").ifEmpty { "—" }
    }

    suspend fun status(): FusaoStatus {
        val c = cli()
        if (c.isEmpty()) return FusaoStatus(moduleInstalled = false)
        val r = RootShell.run("\"$c\" status 2>&1")
        return FusaoStatus.parse(r.output, moduleInstalled = true)
    }

    /** Liga/desliga uma flag (fusaod, framework, perf, dnd, preload, ramclean, gos). */
    suspend fun setFlag(cmd: String, on: Boolean): String {
        val c = cli()
        if (c.isEmpty()) return "módulo ausente"
        val r = RootShell.run("\"$c\" $cmd ${if (on) "on" else "off"} 2>&1")
        if (!r.isSuccess && r.output.isEmpty()) return "FALHOU: comando $cmd retornou código ${r.exitCode}"
        return r.output.lastOrNull()?.trim().orEmpty()
    }

    suspend fun setProfile(profile: String): String {
        val c = cli()
        if (c.isEmpty()) return "módulo ausente"
        val safe = when (profile) {
            "game", "balanced", "battery" -> profile
            else -> return "perfil inválido: $profile"
        }
        val r = RootShell.run("\"$c\" profile $safe 2>&1")
        if (!r.isSuccess && r.output.isEmpty()) {
            return "FALHOU ao aplicar perfil $safe (rc=${r.exitCode})"
        }
        var pState = ""
        var pVerify = ""
        var pMis = ""
        var pExt = ""
        for (line in r.output) {
            val t = line.trim()
            when {
                t.startsWith("profile_state=") -> pState = t.removePrefix("profile_state=")
                t.startsWith("profile_verify=") -> pVerify = t.removePrefix("profile_verify=")
                t.startsWith("profile_mismatch=") -> pMis = t.removePrefix("profile_mismatch=")
                t.startsWith("profile_external=") -> pExt = t.removePrefix("profile_external=")
            }
        }
        return when (pState) {
            "failed" -> "FALHOU: perfil $safe não pôde ser confirmado ($pVerify) ${pMis.takeIf { it.isNotEmpty() } ?: ""}".trim()
            "partial" -> "profile=$safe [PARCIAL $pVerify] ${pMis.takeIf { it.isNotEmpty() } ?: ""}".trim()
            "pending" -> "profile=$safe [AGUARDANDO $pVerify] piso/teto externo: $pExt".trim()
            "applied" -> "profile=$safe [APLICADO $pVerify]"
            else -> r.output.firstOrNull()?.trim().orEmpty().ifEmpty { "profile=$safe" }
        }
    }

    /** Altera ou restaura o downscale via CLI `fusao display [show|720p|810p|900p|reset]`. */
    suspend fun setDisplay(mode: String): String {
        val c = cli()
        if (c.isEmpty()) return "módulo ausente"
        val allowed = setOf("show", "720p", "810p", "900p", "reset")
        val safe = if (mode in allowed) mode else "show"
        val r = RootShell.run("\"$c\" display $safe 2>&1")
        return r.output.lastOrNull()?.trim().orEmpty()
    }

    suspend fun preloadStatus(): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        return RootShell.run("\"$c\" preload status 2>&1").output
    }

    suspend fun preloadPrepare(pkg: String = ""): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        val safe = pkg.trim().replace(Regex("[^A-Za-z0-9_.]"), "")
        val target = if (safe.isEmpty()) "all" else safe
        return RootShell.run("\"$c\" preload prepare $target 2>&1").output
    }

    suspend fun preloadNow(pkg: String = ""): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        val safe = pkg.trim().replace(Regex("[^A-Za-z0-9_.]"), "")
        val cmd = if (safe.isEmpty()) "\"$c\" preload now 2>&1" else "\"$c\" preload now $safe 2>&1"
        return RootShell.run(cmd).output
    }

    /** Prepara/grava o pack para um pacote específico da gamelist ou todos os elegíveis. */
    suspend fun preloadRecord(pkg: String = "", secs: Int = 20): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        val safe = pkg.trim().replace(Regex("[^A-Za-z0-9_.]"), "")
        val s = secs.coerceIn(1, 120)
        val cmd = if (safe.isEmpty()) "\"$c\" preload record $s 2>&1" else "\"$c\" preload record $safe $s 2>&1"
        return RootShell.run(cmd).output
    }

    suspend fun ramcleanNow(): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        return RootShell.run("\"$c\" ramclean now 2>&1").output
    }

    suspend fun ramcleanStatus(): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        return RootShell.run("\"$c\" ramclean status 2>&1").output
    }

    suspend fun gamelist(): List<String> {
        val c = cli()
        if (c.isEmpty()) return emptyList()
        return RootShell.run("\"$c\" gamelist list 2>&1").output
            .map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun gamelistAdd(pkg: String): String {
        val c = cli()
        if (c.isEmpty()) return "módulo ausente"
        val safe = pkg.trim().replace(Regex("[^A-Za-z0-9_.]"), "")
        if (safe.isEmpty()) return "pacote inválido"
        val r = RootShell.run("\"$c\" gamelist add $safe 2>&1")
        return r.output.lastOrNull()?.trim().orEmpty()
    }

    suspend fun gamelistDel(pkg: String): String {
        val c = cli()
        if (c.isEmpty()) return "módulo ausente"
        val safe = pkg.trim().replace(Regex("[^A-Za-z0-9_.]"), "")
        val r = RootShell.run("\"$c\" gamelist del $safe 2>&1")
        return r.output.lastOrNull()?.trim().orEmpty()
    }

    /** Cauda do log do módulo (fusao.log = ganchos com suporte a .1, fusaod.log = writer). */
    suspend fun logTail(lines: Int = 120, fusaod: Boolean = false): List<String> {
        val n = lines.coerceIn(20, 500)
        return if (fusaod) {
            RootShell.run("tail -n $n \"$DATA_DIR/fusaod.log\" 2>/dev/null").output
        } else {
            RootShell.run(
                "{ cat \"$DATA_DIR/fusao.log.1\" 2>/dev/null; cat \"$DATA_DIR/fusao.log\" 2>/dev/null; } | tail -n $n"
            ).output
        }
    }

    suspend fun bugreport(): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        return RootShell.run("\"$c\" bugreport 2>&1").output
    }
}

/** Foto do módulo: tudo que o `fusao status` publica, incluindo verificação de readback. */
@Immutable
data class FusaoStatus(
    val moduleInstalled: Boolean = false,
    val profile: String = "",
    val profileState: String = "",
    val profileVerify: String = "",
    val sysfsSummary: String = "",
    val profileMismatch: String = "",
    val gameActive: String = "",
    val fusaod: Boolean = false,
    val framework: Boolean = false,
    val perf: Boolean = false,
    val dnd: Boolean = false,
    val preload: Boolean = false,
    val ramclean: Boolean = false,
    val gos: Boolean = true,
    val display: String = "",
    val thermal: String = "",
    val hooks: List<String> = emptyList(),
    val fusaodRunning: Boolean = false,
    val watchRunning: Boolean = false,
    val observerRunning: Boolean = false,
) {
    val inGame: Boolean get() = gameActive.isNotEmpty()

    val isProfileConfirmed: Boolean
        get() = profile.isNotEmpty() && profileState != "failed"

    val activeFlagsCount: Int
        get() = listOf(fusaod, framework, perf, dnd, preload, ramclean, gos).count { it }

    val runningDaemonsCount: Int
        get() = listOf(fusaodRunning, watchRunning, observerRunning).count { it }

    fun flag(cmd: String): Boolean = when (cmd) {
        "fusaod" -> fusaod
        "framework" -> framework
        "perf" -> perf
        "dnd" -> dnd
        "preload" -> preload
        "ramclean" -> ramclean
        "gos" -> gos
        else -> false
    }

    companion object {
        private val DAEMONS = setOf("fusaod", "watch", "observer")

        fun parse(lines: List<String>, moduleInstalled: Boolean): FusaoStatus {
            var profile = ""
            var profileState = ""
            var profileVerify = ""
            var sysfsSummary = ""
            var profileMismatch = ""
            var gameActive = ""
            val flags = mutableMapOf<String, Boolean>()
            var display = ""
            var thermal = ""
            val hooks = mutableListOf<String>()
            val daemons = mutableMapOf<String, Boolean>()
            var inHooks = false
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("---")) {
                    inHooks = true
                    continue
                }
                val eq = line.indexOf('=')
                if (eq > 0) {
                    val k = line.substring(0, eq)
                    val v = line.substring(eq + 1)
                    if (inHooks && k in DAEMONS && (v == "running" || v == "stopped")) {
                        daemons[k] = v == "running"
                        continue
                    }
                    if (inHooks) {
                        hooks.add(line)
                        continue
                    }
                    when (k) {
                        "profile" -> profile = v
                        "profile_state" -> profileState = v
                        "profile_verify" -> profileVerify = v
                        "sysfs_summary" -> sysfsSummary = v
                        "profile_mismatch" -> profileMismatch = v
                        "game-active" -> gameActive = v
                        "display" -> display = v
                        "thermal" -> thermal = v.removePrefix("thermal=")
                        "fusaod", "framework", "perf", "dnd", "preload", "ramclean", "gos" ->
                            flags[k] = v == "on"
                    }
                    continue
                }
                if (inHooks && line.isNotEmpty()) hooks.add(line)
            }
            return FusaoStatus(
                moduleInstalled = moduleInstalled,
                profile = profile,
                profileState = profileState,
                profileVerify = profileVerify,
                sysfsSummary = sysfsSummary,
                profileMismatch = profileMismatch,
                gameActive = gameActive,
                fusaod = flags["fusaod"] == true,
                framework = flags["framework"] == true,
                perf = flags["perf"] == true,
                dnd = flags["dnd"] == true,
                preload = flags["preload"] == true,
                ramclean = flags["ramclean"] == true,
                gos = flags["gos"] != false,
                display = display,
                thermal = thermal,
                hooks = hooks.takeLast(8),
                fusaodRunning = daemons["fusaod"] == true,
                watchRunning = daemons["watch"] == true,
                observerRunning = daemons["observer"] == true,
            )
        }
    }
}

/**
 * Extrai metadados estruturados reais a partir da saída textual de `fusao preload status`,
 * distinguindo preload habilitado, preparação solicitada/concluída, execução concluída e falha.
 */
@Immutable
data class PreloadTelemetry(
    val stateLabel: String = "—",
    val isEnabled: Boolean = false,
    val preloadState: String = "disabled",
    val eligibleCount: Int = 0,
    val eligiblePkgs: List<String> = emptyList(),
    val lastResult: String = "",
    val memAvailKb: String = "",
    val packCount: String = "0",
    val packFiles: List<String> = emptyList(),
    val recentEvents: List<String> = emptyList(),
) {
    val statusBadgeLabel: String
        get() = when (preloadState) {
            "executed" -> "EXECUÇÃO CONCLUÍDA"
            "prepared" -> "PACKS PREPARADOS"
            "armed" -> "PRELOAD HABILITADO"
            "failed" -> "FALHA NO PRELOAD"
            else -> if (isEnabled) "PRELOAD HABILITADO" else "DESATIVADO"
        }

    companion object {
        fun fromLines(lines: List<String>): PreloadTelemetry {
            if (lines.isEmpty()) return PreloadTelemetry()
            var stateLabel = "—"
            var isEnabled = false
            var preloadState = "disabled"
            var eligibleCount = 0
            var eligiblePkgs: List<String> = emptyList()
            var lastResult = ""
            var memAvailKb = ""
            var packCount = "0"
            val packs = mutableListOf<String>()
            val events = mutableListOf<String>()
            var inEvents = false

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("---")) {
                    inEvents = true
                    continue
                }
                if (!inEvents) {
                    when {
                        line.startsWith("preload=") -> {
                            stateLabel = line.removePrefix("preload=")
                            isEnabled = stateLabel.startsWith("on")
                        }
                        line.startsWith("preload_state=") -> {
                            preloadState = line.removePrefix("preload_state=").trim()
                        }
                        line.startsWith("eligible_count=") -> {
                            eligibleCount = line.removePrefix("eligible_count=").trim().toIntOrNull() ?: 0
                        }
                        line.startsWith("eligible_pkgs=") -> {
                            val csv = line.removePrefix("eligible_pkgs=").trim()
                            eligiblePkgs = if (csv.isEmpty()) emptyList() else csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        }
                        line.startsWith("last_result=") -> {
                            lastResult = line.removePrefix("last_result=").trim()
                        }
                        line.startsWith("mem_avail_kb=") -> {
                            memAvailKb = line.removePrefix("mem_avail_kb=").trim()
                        }
                        line.startsWith("packs:") -> {
                            val rawCount = line.removePrefix("packs:").trim()
                            packCount = if (rawCount.startsWith("nenhum", ignoreCase = true)) {
                                "0"
                            } else {
                                rawCount.substringBefore(" ").ifEmpty { "0" }
                            }
                        }
                        line.endsWith(".pack") || line.contains(".pack") -> {
                            packs.add(line)
                        }
                    }
                } else {
                    events.add(line)
                    if (line.contains("preload ok pkg=")) {
                        preloadState = "executed"
                    } else if (line.contains("preload-record ok") || line.contains("preload-prepare ok")) {
                        if (preloadState != "executed") preloadState = "prepared"
                    } else if (line.contains("falhou") || line.contains("reason=low_ram")) {
                        preloadState = "failed"
                    }
                }
            }
            return PreloadTelemetry(
                stateLabel = stateLabel,
                isEnabled = isEnabled,
                preloadState = preloadState,
                eligibleCount = eligibleCount,
                eligiblePkgs = eligiblePkgs,
                lastResult = lastResult,
                memAvailKb = memAvailKb,
                packCount = packCount,
                packFiles = packs,
                recentEvents = events,
            )
        }
    }
}

/**
 * Extrai metadados reais da saída de `fusao ramclean status` / `fusao ramclean now`,
 * preservando todas as linhas originais.
 */
@Immutable
data class RamcleanTelemetry(
    val stateLabel: String = "—",
    val isEnabled: Boolean = false,
    val lastTransition: String = "",
    val lastAvailKb: String = "",
    val lastRc: String = "",
    val recentEvents: List<String> = emptyList(),
) {
    companion object {
        private val FREE_REGEX = Regex("free=([0-9]+->[0-9]+)")
        private val AVAIL_REGEX = Regex("avail=([0-9]+)")
        private val RC_REGEX = Regex("rc=([0-9/]+)")

        fun fromLines(lines: List<String>): RamcleanTelemetry {
            if (lines.isEmpty()) return RamcleanTelemetry()
            var stateLabel = "—"
            var isEnabled = false
            val events = mutableListOf<String>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("---")) continue
                if (line.startsWith("ramclean=")) {
                    stateLabel = line.removePrefix("ramclean=")
                    isEnabled = stateLabel.startsWith("on")
                } else if (line.contains("ramclean ok") || line.contains("free=")) {
                    events.add(line)
                }
            }
            val latest = events.lastOrNull().orEmpty()
            val freeMatch = FREE_REGEX.find(latest)?.groupValues?.getOrNull(1).orEmpty()
            val availMatch = AVAIL_REGEX.find(latest)?.groupValues?.getOrNull(1).orEmpty()
            val rcMatch = RC_REGEX.find(latest)?.groupValues?.getOrNull(1).orEmpty()
            return RamcleanTelemetry(
                stateLabel = stateLabel,
                isEnabled = isEnabled,
                lastTransition = freeMatch,
                lastAvailKb = availMatch,
                lastRc = rcMatch,
                recentEvents = events,
            )
        }
    }
}
