package com.orkgabb.fusao

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Único ponto de entrada para toda interação com shell root no app.
 *
 * Root é tratado como temporário (só vale até o reboot): nunca se cacheia booleano
 * de "root concedido" entre aberturas — cada [hasRoot] re-checa o shell de verdade.
 */
object RootShell {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                // preload record observa por ~20s; preload de jogo grande leva ~2s.
                // 60s cobre tudo sem travar a UI (tudo roda em Dispatchers.IO).
                .setTimeout(60)
        )
    }

    /** Tenta adquirir o shell root agora. Nunca joga exceção: retorna false se sem root. */
    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val shell = Shell.getShell()
            shell.isRoot
        }.getOrDefault(false)
    }

    /** Roda um script (contexto root) e devolve as linhas de stdout. Nunca joga exceção. */
    suspend fun run(script: String): ShellRunResult = withContext(Dispatchers.IO) {
        val result = Shell.cmd(script).exec()
        ShellRunResult(
            isSuccess = result.isSuccess,
            exitCode = result.code,
            output = result.out,
        )
    }
}

data class ShellRunResult(
    val isSuccess: Boolean,
    val exitCode: Int,
    val output: List<String>,
)
