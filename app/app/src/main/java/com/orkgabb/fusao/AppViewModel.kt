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
    val selectedPreloadPkg: String = "",
    val ramcleanInfo: List<String> = emptyList(),
    val games: List<String> = emptyList(),
    /** Alvos do FAS por jogo (pkg -> lista), lidos de `fusao fas gen`. */
    val fasTargets: Map<String, List<Int>> = emptyMap(),
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
            if (_state.value.anyBusy()) return@launch
            _state.update { it.copy(busy = Busy.REFRESH, activeTarget = "bootstrap") }
            val root = RootShell.hasRoot()
            if (!root) {
                FusaoBridge.clearCache()
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
            if (!present) FusaoBridge.clearCache()
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
                        if (it.notice.startsWith("Sem root") || it.notice.startsWith("Módulo encore")) "" else it.notice
                    } else {
                        "Módulo encore ausente."
                    },
                )
            }
        }
    }

    /**
     * Revalida root e atualiza status, versão, lista de jogos e abas já inicializadas.
     */
    fun refresh() {
        viewModelScope.launch {
            if (_state.value.anyBusy()) return@launch
            _state.update { it.copy(busy = Busy.REFRESH, activeTarget = "refresh") }
            val root = RootShell.hasRoot()
            if (!root) {
                FusaoBridge.clearCache()
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
            if (!present) FusaoBridge.clearCache()
            val st = if (present) FusaoBridge.status() else FusaoStatus()
            val version = if (present) FusaoBridge.moduleVersion() else ""
            val cur = _state.value
            val games = if (present) FusaoBridge.gamelist() else emptyList()
            val pInfo = if (present && cur.preloadInfo.isNotEmpty()) FusaoBridge.preloadStatus() else cur.preloadInfo
            val rInfo = if (present && cur.ramcleanInfo.isNotEmpty()) FusaoBridge.ramcleanStatus() else cur.ramcleanInfo
            val lLines = if (present && cur.logLines.isNotEmpty()) {
                FusaoBridge.logTail(cur.logLineLimit, cur.logFusaod)
            } else {
                cur.logLines
            }
            _state.update {
                it.copy(
                    ready = true,
                    rooted = true,
                    moduleInstalled = present,
                    status = st,
                    moduleVersion = version,
                    games = games,
                    preloadInfo = pInfo,
                    ramcleanInfo = rInfo,
                    logLines = lLines,
                    busy = Busy.NONE,
                    activeTarget = "",
                    notice = if (!present) {
                        "Módulo encore ausente."
                    } else {
                        it.notice.takeIf { n -> !n.startsWith("Sem root") && !n.startsWith("Módulo encore") }.orEmpty()
                    },
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
            val cur = _state.value
            val pInfo = if (cmd == "preload") {
                FusaoBridge.preloadStatus()
            } else {
                cur.preloadInfo
            }
            val rInfo = if (cmd == "ramclean" && cur.ramcleanInfo.isNotEmpty()) {
                FusaoBridge.ramcleanStatus()
            } else {
                cur.ramcleanInfo
            }
            _state.update {
                it.copy(
                    status = st,
                    preloadInfo = pInfo,
                    ramcleanInfo = rInfo,
                    busy = Busy.NONE,
                    activeTarget = "",
                    notice = out,
                )
            }
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

    fun selectPreloadTarget(pkg: String) {
        _state.update { it.copy(selectedPreloadPkg = pkg) }
    }

    /** Carrega estado de Preload, gamelist e RAM clean de forma coordenada sem disputa de estado Busy. */
    fun loadPreloadAndRamcleanIfEmpty() {
        if (!guardRoot()) return
        val cur = _state.value
        if (cur.preloadInfo.isNotEmpty() && cur.ramcleanInfo.isNotEmpty()) return
        viewModelScope.launch {
            if (_state.value.anyBusy()) return@launch
            _state.update { it.copy(busy = Busy.PRELOAD, activeTarget = "preload_init") }
            val pInfo = if (cur.preloadInfo.isEmpty()) FusaoBridge.preloadStatus() else cur.preloadInfo
            val rInfo = if (cur.ramcleanInfo.isEmpty()) FusaoBridge.ramcleanStatus() else cur.ramcleanInfo
            val games = if (cur.games.isEmpty()) FusaoBridge.gamelist() else cur.games
            _state.update {
                it.copy(
                    preloadInfo = pInfo,
                    ramcleanInfo = rInfo,
                    games = games,
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

    fun preloadPrepare(targetPkg: String = _state.value.selectedPreloadPkg) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.PRELOAD, activeTarget = "preload_prepare") }
            val out = FusaoBridge.preloadPrepare(targetPkg)
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

    fun preloadNow(targetPkg: String = _state.value.selectedPreloadPkg) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.PRELOAD, activeTarget = "preload_now") }
            val out = FusaoBridge.preloadNow(targetPkg)
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

    fun preloadRecord(targetPkg: String = _state.value.selectedPreloadPkg) {
        if (!guardRoot()) return
        val inGameNow = _state.value.status.inGame
        viewModelScope.launch {
            recordCountdownJob?.cancel()
            val secs = if (inGameNow) 20 else 5
            _state.update {
                it.copy(
                    busy = Busy.RECORD,
                    activeTarget = "preload_record",
                    recordRemainingSec = secs,
                    notice = if (inGameNow) {
                        "Observando o jogo ativo (~20s)..."
                    } else {
                        "Mapeando diretórios dos pacotes elegíveis da gamelist..."
                    },
                )
            }
            if (inGameNow) {
                recordCountdownJob = launch {
                    for (rem in secs downTo 1) {
                        _state.update { it.copy(recordRemainingSec = rem) }
                        delay(1000L)
                    }
                    _state.update { it.copy(recordRemainingSec = 0) }
                }
            }
            val out = FusaoBridge.preloadRecord(targetPkg, secs)
            recordCountdownJob?.cancel()
            val info = FusaoBridge.preloadStatus()
            val st = FusaoBridge.status()
            _state.update {
                it.copy(
                    preloadInfo = info + listOf("---") + out,
                    status = st,
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
            if (_state.value.anyBusy()) return@launch
            _state.update { it.copy(busy = Busy.GAMELIST, activeTarget = "gamelist_load") }
            val games = FusaoBridge.gamelist()
            val fasTargets = if (_state.value.status.fasSupported) FusaoBridge.fasTargets() else emptyMap()
            _state.update { it.copy(games = games, fasTargets = fasTargets, busy = Busy.NONE, activeTarget = "") }
        }
    }

    fun setFasMode(mode: String) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.FLAG, activeTarget = "fas_mode:$mode") }
            val out = FusaoBridge.setFasMode(mode)
            val st = FusaoBridge.status()
            _state.update { it.copy(status = st, busy = Busy.NONE, activeTarget = "", notice = out) }
        }
    }

    /** Toque no FAS de um jogo: avança para o próximo limite (AUTO -> 30 -> 40 -> ... -> 144 -> AUTO). */
    fun cycleFasCap(pkg: String) {
        if (!guardRoot()) return
        viewModelScope.launch {
            val cur = fasCapOf(_state.value.fasTargets[pkg])
            val idx = FAS_CAP_PRESETS.indexOf(cur).coerceAtLeast(0)
            val next = FAS_CAP_PRESETS[(idx + 1) % FAS_CAP_PRESETS.size]
            _state.update { it.copy(busy = Busy.FLAG, activeTarget = "fas_target:$pkg") }
            val out = FusaoBridge.setFasTarget(pkg, fasTargetsForCap(next))
            val fasTargets = FusaoBridge.fasTargets()
            _state.update { it.copy(fasTargets = fasTargets, busy = Busy.NONE, activeTarget = "", notice = out) }
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
