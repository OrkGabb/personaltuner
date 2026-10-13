package com.orkgabb.fusao

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * O handshake do su é a parte mais lenta da abertura e vai acontecer de qualquer
 * jeito — o app é inútil sem root. Dispara no instante em que o processo existe
 * para sobrepor com o startup da Activity/Compose em vez de rodar depois.
 */
class FusaoApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch { RootShell.hasRoot() }
    }
}
