package com.orkgabb.fusao

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * O handshake do su é a parte mais lenta da abertura e vai acontecer de qualquer
 * jeito — a tela do app é inútil sem root. A MainActivity dispara [prewarmRoot] no
 * começo do onCreate para sobrepor com o startup do Compose em vez de rodar depois.
 *
 * Não roda no onCreate do Application: o processo também sobe sozinho pelo serviço
 * do Smart Pixels (acessibilidade, religado pelo sistema no boot), que não usa root —
 * ali o su não deve ser tocado.
 */
class FusaoApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rootPrewarmed = AtomicBoolean(false)

    fun prewarmRoot() {
        if (rootPrewarmed.compareAndSet(false, true)) {
            scope.launch { RootShell.hasRoot() }
        }
    }
}
