package com.orkgabb.fusao

/**
 * O único canal do app até o módulo: chama o CLI `fusao` (que é quem mexe nas
 * flags em /data/adb/fusao) e lê de volta o que o módulo publicou. O app nunca
 * escreve sysfs direto — todo write pertence ao fusaod/scripts do módulo.
 */
object FusaoBridge {

    private const val MOD_CLI = "/data/adb/modules/encore/system/bin/fusaod"
    private const val MOD_DIR = "/data/adb/modules/encore"
    private const val DATA_DIR = "/data/adb/fusao"

    /** Resolve o binário do CLI uma vez por sessão (symlink do KSU/AP ou módulo). */
    @Volatile
    private var cachedCli: String? = null

    suspend fun cli(): String {
        cachedCli?.let { return it }
        val r = RootShell.run(
            "command -v fusao 2>/dev/null; " +
                "for b in /data/adb/ksu/bin/fusao /data/adb/ap/bin/fusao " +
                "\"$MOD_DIR/system/bin/fusao\"; do " +
                "[ -x \"\$b\" ] && echo \"\$b\" && break; done"
        )
        val found = r.output.map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (found.isNotEmpty()) cachedCli = found
        return found
    }

    suspend fun modulePresent(): Boolean =
        RootShell.run("[ -x \"$MOD_CLI\" ] && echo Y || echo N")
            .output.any { it.trim() == "Y" }

    suspend fun moduleVersion(): String {
        val r = RootShell.run(
            "grep -E '^version' \"$MOD_DIR/module.prop\" 2>/dev/null; " +
                "grep -E '^version' /data/adb/modules_update/encore/module.prop 2>/dev/null"
        )
        return r.output.joinToString(" | ").ifEmpty { "—" }
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
        return r.output.lastOrNull()?.trim().orEmpty()
    }

    suspend fun setProfile(profile: String): String {
        val c = cli()
        if (c.isEmpty()) return "módulo ausente"
        val r = RootShell.run("\"$c\" profile $profile 2>&1")
        return r.output.lastOrNull()?.trim().orEmpty()
    }

    suspend fun preloadStatus(): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        return RootShell.run("\"$c\" preload status 2>&1").output
    }

    suspend fun preloadNow(): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        return RootShell.run("\"$c\" preload now 2>&1").output
    }

    /** Observa o jogo aberto por ~secs segundos e grava o pack (WILLNEED usa depois). */
    suspend fun preloadRecord(secs: Int = 20): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        val s = secs.coerceIn(5, 120)
        return RootShell.run("\"$c\" preload record $s 2>&1").output
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

    /** Cauda do log do módulo (fusao.log = ganchos, fusaod.log = writer). */
    suspend fun logTail(lines: Int = 120, fusaod: Boolean = false): List<String> {
        val f = if (fusaod) "fusaod.log" else "fusao.log"
        val n = lines.coerceIn(20, 500)
        return RootShell.run("tail -n $n \"$DATA_DIR/$f\" 2>/dev/null").output
    }

    suspend fun bugreport(): List<String> {
        val c = cli()
        if (c.isEmpty()) return listOf("módulo ausente")
        return RootShell.run("\"$c\" bugreport 2>&1").output
    }
}

/** Foto do módulo: tudo que o `fusao status` publica, mais se o módulo existe. */
data class FusaoStatus(
    val moduleInstalled: Boolean = false,
    val profile: String = "",
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
