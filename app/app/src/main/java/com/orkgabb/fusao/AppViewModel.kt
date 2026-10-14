package com.orkgabb.fusao

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uma ação longa por vez (a UI trava só a linha ocupada, nunca a tela). */
enum class Busy { NONE, REFRESH, FLAG, PRELOAD, RECORD, RAMCLEAN, GAMELIST, LOG, BUGREPORT, APPS }

@Immutable
data class AppEntry(val pkg: String, val label: String)

/**
 * Cache em memória para ícones reais dos aplicativos obtidos via PackageManager
 * (usando a visibilidade declarada em <queries> para MAIN/LAUNCHER, sem permissões extras).
 */
object AppIconCache {
    private val cache = object : LruCache<String, ImageBitmap>(128) {}
    private val failed = HashSet<String>()

    @Synchronized
    fun peek(pkg: String): ImageBitmap? = cache.get(pkg)

    suspend fun getOrLoad(ctx: Context, pkg: String): ImageBitmap? {
        peek(pkg)?.let { return it }
        synchronized(this) {
            if (pkg in failed) return null
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val pm = ctx.applicationContext.packageManager
                val drawable = pm.getApplicationIcon(pkg)
                val bmp = drawableToBitmap(drawable, 96).asImageBitmap()
                synchronized(this@AppIconCache) {
                    cache.put(pkg, bmp)
                }
                bmp
            }.onFailure {
                synchronized(this@AppIconCache) {
                    failed.add(pkg)
                }
            }.getOrNull()
        }
    }

    private fun drawableToBitmap(drawable: Drawable, maxPx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val b = drawable.bitmap
            if (b.width <= maxPx && b.height <= maxPx) return b
            return Bitmap.createScaledBitmap(b, maxPx, maxPx, true)
        }
        val w = drawable.intrinsicWidth.coerceIn(1, maxPx)
        val h = drawable.intrinsicHeight.coerceIn(1, maxPx)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}

@Immutable
data class FusaoUiState(
    val ready: Boolean = false,
    val rooted: Boolean = false,
    val moduleInstalled: Boolean = false,
    val moduleVersion: String = "",
    val status: FusaoStatus = FusaoStatus(),
    val busy: Busy = Busy.NONE,
    val activeTarget: String = "",
    val recordRemainingSec: Int = 0,
    val notice: String = "",
    val preloadInfo: List<String> = emptyList(),
    val ramcleanInfo: List<String> = emptyList(),
    val games: List<String> = emptyList(),
    val logLines: List<String> = emptyList(),
    val logFusaod: Boolean = false,
    val logLineLimit: Int = 120,
    val apps: List<AppEntry> = emptyList(),
    val appLabelsMap: Map<String, String> = emptyMap(),
) {
    fun isBusy(b: Busy): Boolean = busy == b
    fun anyBusy(): Boolean = busy != Busy.NONE
    fun labelFor(pkg: String): String = appLabelsMap[pkg] ?: pkg
}

class FusaoViewModel : ViewModel() {

    private val _state = MutableStateFlow(FusaoUiState())
    val state: StateFlow<FusaoUiState> = _state.asStateFlow()

    private var recordCountdownJob: Job? = null

    /**
     * Revalida o estado de root e do módulo a cada abertura/retorno do aplicativo,
     * cumprindo estritamente o contrato de temp-root do FUSAO-M54.
     */
    fun bootstrap(ctx: Context? = null) {
        ctx?.let { ensureAppsCatalogLoaded(it) }
        viewModelScope.launch {
            if (_state.value.busy == Busy.RECORD) return@launch
            _state.update { it.copy(busy = Busy.REFRESH, activeTarget = "bootstrap") }
            val root = RootShell.hasRoot()
            if (!root) {
                _state.update {
                    it.copy(
                        ready = true,
                        rooted = false,
                        moduleInstalled = false,
                        busy = Busy.NONE,
                        activeTarget = "",
                        notice = "Sem root — modo leitura. Refaça o temp-root e reabra.",
                    )
                }
                return@launch
            }
            val present = FusaoBridge.modulePresent()
            val version = if (present) FusaoBridge.moduleVersion() else ""
            val st = if (present) FusaoBridge.status() else FusaoStatus()
            val games = if (present && _state.value.games.isEmpty()) {
                FusaoBridge.gamelist()
            } else {
                _state.value.games
            }
            _state.update {
                it.copy(
                    ready = true,
                    rooted = true,
                    moduleInstalled = present,
                    moduleVersion = version,
                    status = st,
                    games = games,
                    busy = Busy.NONE,
                    activeTarget = "",
                    notice = if (present) {
                        if (it.notice.startsWith("Sem root")) "" else it.notice
                    } else {
                        "Módulo encore ausente."
                    },
                )
            }
        }
    }

    /**
     * Revalida root e atualiza status, versão e lista de jogos.
     * Se o usuário acabou de conceder temp-root e tocou em Atualizar, o app detecta na hora.
     */
    fun refresh() {
        viewModelScope.launch {
            if (_state.value.busy == Busy.RECORD) return@launch
            _state.update { it.copy(busy = Busy.REFRESH, activeTarget = "refresh") }
            val root = RootShell.hasRoot()
            if (!root) {
                _state.update {
                    it.copy(
                        ready = true,
                        rooted = false,
                        busy = Busy.NONE,
                        activeTarget = "",
                        notice = "Sem root — refaça o temp-root e reabra.",
                    )
                }
                return@launch
            }
            val present = FusaoBridge.modulePresent()
            val st = if (present) FusaoBridge.status() else FusaoStatus()
            val version = if (present) FusaoBridge.moduleVersion() else ""
            _state.update {
                it.copy(
                    ready = true,
                    rooted = true,
                    moduleInstalled = present,
                    status = st,
                    moduleVersion = version,
                    busy = Busy.NONE,
                    activeTarget = "",
                    notice = if (!present) "Módulo encore ausente." else it.notice.takeIf { n -> !n.startsWith("Sem root") }.orEmpty(),
                )
            }
        }
    }

    fun toggleFlag(cmd: String, on: Boolean) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.FLAG, activeTarget = cmd) }
            val out = FusaoBridge.setFlag(cmd, on)
            val st = FusaoBridge.status()
            _state.update { it.copy(status = st, busy = Busy.NONE, activeTarget = "", notice = out) }
        }
    }

    fun setProfile(profile: String) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.FLAG, activeTarget = "profile:$profile") }
            val out = FusaoBridge.setProfile(profile)
            val st = FusaoBridge.status()
            _state.update { it.copy(status = st, busy = Busy.NONE, activeTarget = "", notice = out) }
        }
    }

    fun setDisplay(mode: String) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.FLAG, activeTarget = "display:$mode") }
            val out = FusaoBridge.setDisplay(mode)
            val st = FusaoBridge.status()
            _state.update {
                it.copy(
                    status = st,
                    busy = Busy.NONE,
                    activeTarget = "",
                    notice = if (out.isNotEmpty()) "display: $out" else "display atualizado",
                )
            }
        }
    }

    /** Carrega estado de Preload e RAM clean de forma coordenada sem disputa de estado Busy. */
    fun loadPreloadAndRamcleanIfEmpty() {
        if (!guardRoot()) return
        val cur = _state.value
        if (cur.preloadInfo.isNotEmpty() && cur.ramcleanInfo.isNotEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.PRELOAD, activeTarget = "preload_init") }
            val pInfo = if (cur.preloadInfo.isEmpty()) FusaoBridge.preloadStatus() else cur.preloadInfo
            val rInfo = if (cur.ramcleanInfo.isEmpty()) FusaoBridge.ramcleanStatus() else cur.ramcleanInfo
            _state.update {
                it.copy(
                    preloadInfo = pInfo,
                    ramcleanInfo = rInfo,
                    busy = Busy.NONE,
                    activeTarget = "",
                )
            }
        }
    }

    fun loadPreload() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.PRELOAD, activeTarget = "preload_status") }
            val info = FusaoBridge.preloadStatus()
            _state.update { it.copy(preloadInfo = info, busy = Busy.NONE, activeTarget = "") }
        }
    }

    fun preloadNow() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.PRELOAD, activeTarget = "preload_now") }
            val out = FusaoBridge.preloadNow()
            val info = FusaoBridge.preloadStatus()
            val st = FusaoBridge.status()
            _state.update {
                it.copy(
                    preloadInfo = info + listOf("---") + out,
                    status = st,
                    busy = Busy.NONE,
                    activeTarget = "",
                    notice = out.lastOrNull().orEmpty(),
                )
            }
        }
    }

    fun preloadRecord() {
        if (!guardRoot()) return
        viewModelScope.launch {
            recordCountdownJob?.cancel()
            _state.update {
                it.copy(
                    busy = Busy.RECORD,
                    activeTarget = "preload_record",
                    recordRemainingSec = 20,
                    notice = "Observando o jogo aberto (~20s)...",
                )
            }
            recordCountdownJob = launch {
                for (rem in 20 downTo 1) {
                    _state.update { it.copy(recordRemainingSec = rem) }
                    delay(1000L)
                }
                _state.update { it.copy(recordRemainingSec = 0) }
            }
            val out = FusaoBridge.preloadRecord(20)
            recordCountdownJob?.cancel()
            val info = FusaoBridge.preloadStatus()
            _state.update {
                it.copy(
                    preloadInfo = info + listOf("---") + out,
                    busy = Busy.NONE,
                    activeTarget = "",
                    recordRemainingSec = 0,
                    notice = out.lastOrNull().orEmpty(),
                )
            }
        }
    }

    fun ramcleanNow() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.RAMCLEAN, activeTarget = "ramclean_now") }
            val out = FusaoBridge.ramcleanNow()
            val info = FusaoBridge.ramcleanStatus()
            val st = FusaoBridge.status()
            _state.update {
                it.copy(
                    ramcleanInfo = info + listOf("---") + out,
                    status = st,
                    busy = Busy.NONE,
                    activeTarget = "",
                    notice = out.lastOrNull().orEmpty(),
                )
            }
        }
    }

    fun loadRamclean() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.RAMCLEAN, activeTarget = "ramclean_status") }
            val info = FusaoBridge.ramcleanStatus()
            _state.update { it.copy(ramcleanInfo = info, busy = Busy.NONE, activeTarget = "") }
        }
    }

    fun loadGames() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.GAMELIST, activeTarget = "gamelist_load") }
            val games = FusaoBridge.gamelist()
            _state.update { it.copy(games = games, busy = Busy.NONE, activeTarget = "") }
        }
    }

    fun gameAdd(pkg: String) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.GAMELIST, activeTarget = "add:$pkg") }
            val out = FusaoBridge.gamelistAdd(pkg)
            val games = FusaoBridge.gamelist()
            _state.update { it.copy(games = games, busy = Busy.NONE, activeTarget = "", notice = out) }
        }
    }

    fun gameDel(pkg: String) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.GAMELIST, activeTarget = "del:$pkg") }
            val out = FusaoBridge.gamelistDel(pkg)
            val games = FusaoBridge.gamelist()
            _state.update { it.copy(games = games, busy = Busy.NONE, activeTarget = "", notice = out) }
        }
    }

    fun loadLog(fusaod: Boolean, lineLimit: Int = _state.value.logLineLimit) {
        if (!guardRoot()) return
        val clamped = lineLimit.coerceIn(20, 500)
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = Busy.LOG,
                    activeTarget = if (fusaod) "fusaod.log" else "fusao.log",
                    logFusaod = fusaod,
                    logLineLimit = clamped,
                )
            }
            val lines = FusaoBridge.logTail(clamped, fusaod)
            _state.update { it.copy(logLines = lines, busy = Busy.NONE, activeTarget = "") }
        }
    }

    fun bugreport() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.BUGREPORT, activeTarget = "bugreport") }
            val out = FusaoBridge.bugreport()
            _state.update {
                it.copy(
                    busy = Busy.NONE,
                    activeTarget = "",
                    notice = out.lastOrNull().orEmpty(),
                    logLines = out,
                )
            }
        }
    }

    private fun ensureAppsCatalogLoaded(ctx: Context) {
        if (_state.value.apps.isNotEmpty()) return
        val appCtx = ctx.applicationContext
        viewModelScope.launch {
            val list = queryLauncherApps(appCtx)
            val map = list.associate { it.pkg to it.label }
            _state.update { it.copy(apps = list, appLabelsMap = map) }
        }
    }

    fun loadApps(ctx: Context) {
        val appCtx = ctx.applicationContext
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.APPS, activeTarget = "apps") }
            val list = queryLauncherApps(appCtx)
            val map = list.associate { it.pkg to it.label }
            _state.update { it.copy(apps = list, appLabelsMap = map, busy = Busy.NONE, activeTarget = "") }
        }
    }

    private suspend fun queryLauncherApps(appCtx: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = appCtx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        found.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull() ?: pkg
            AppEntry(pkg, label)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun appLabel(pkg: String): String =
        _state.value.appLabelsMap[pkg]
            ?: _state.value.apps.firstOrNull { it.pkg == pkg }?.label
            ?: pkg

    fun clearNotice() {
        _state.update { it.copy(notice = "") }
    }

    private fun guardRoot(): Boolean {
        if (_state.value.rooted) return true
        _state.update { it.copy(notice = "Sem root — refaça o temp-root e reabra.") }
        return false
    }
}
