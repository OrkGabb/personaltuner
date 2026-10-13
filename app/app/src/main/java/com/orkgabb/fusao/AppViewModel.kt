package com.orkgabb.fusao

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Uma ação longa por vez (a UI trava só a linha ocupada, nunca a tela). */
enum class Busy { NONE, REFRESH, FLAG, PRELOAD, RECORD, RAMCLEAN, GAMELIST, LOG, BUGREPORT, APPS }

data class AppEntry(val pkg: String, val label: String)

data class FusaoUiState(
    val ready: Boolean = false,
    val rooted: Boolean = false,
    val moduleInstalled: Boolean = false,
    val moduleVersion: String = "",
    val status: FusaoStatus = FusaoStatus(),
    val busy: Busy = Busy.NONE,
    val notice: String = "",
    val preloadInfo: List<String> = emptyList(),
    val ramcleanInfo: List<String> = emptyList(),
    val games: List<String> = emptyList(),
    val logLines: List<String> = emptyList(),
    val logFusaod: Boolean = false,
    val apps: List<AppEntry> = emptyList(),
) {
    fun isBusy(b: Busy): Boolean = busy == b
    fun anyBusy(): Boolean = busy != Busy.NONE
}

class FusaoViewModel : ViewModel() {

    private val _state = MutableStateFlow(FusaoUiState())
    val state: StateFlow<FusaoUiState> = _state.asStateFlow()

    fun bootstrap() {
        if (_state.value.ready) {
            refresh()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.REFRESH) }
            val root = RootShell.hasRoot()
            if (!root) {
                _state.update {
                    it.copy(
                        ready = true, rooted = false, busy = Busy.NONE,
                        notice = "Sem root — modo leitura. Refaça o temp-root e reabra.",
                    )
                }
                return@launch
            }
            val present = FusaoBridge.modulePresent()
            val version = if (present) FusaoBridge.moduleVersion() else ""
            val st = if (present) FusaoBridge.status() else FusaoStatus()
            _state.update {
                it.copy(
                    ready = true, rooted = true, moduleInstalled = present,
                    moduleVersion = version, status = st, busy = Busy.NONE,
                    notice = if (present) "" else "Módulo encore ausente.",
                )
            }
        }
    }

    fun refresh() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.REFRESH) }
            val st = FusaoBridge.status()
            val version = FusaoBridge.moduleVersion()
            _state.update { it.copy(status = st, moduleVersion = version, busy = Busy.NONE) }
        }
    }

    fun toggleFlag(cmd: String, on: Boolean) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.FLAG) }
            val out = FusaoBridge.setFlag(cmd, on)
            val st = FusaoBridge.status()
            _state.update { it.copy(status = st, busy = Busy.NONE, notice = out) }
        }
    }

    fun setProfile(profile: String) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.FLAG) }
            val out = FusaoBridge.setProfile(profile)
            val st = FusaoBridge.status()
            _state.update { it.copy(status = st, busy = Busy.NONE, notice = out) }
        }
    }

    fun loadPreload() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.PRELOAD) }
            val info = FusaoBridge.preloadStatus()
            _state.update { it.copy(preloadInfo = info, busy = Busy.NONE) }
        }
    }

    fun preloadNow() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.PRELOAD) }
            val out = FusaoBridge.preloadNow()
            val info = FusaoBridge.preloadStatus()
            val st = FusaoBridge.status()
            _state.update {
                it.copy(
                    preloadInfo = info + listOf("---") + out, status = st,
                    busy = Busy.NONE,
                    notice = out.lastOrNull().orEmpty(),
                )
            }
        }
    }

    fun preloadRecord() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update {
                it.copy(busy = Busy.RECORD, notice = "Observando o jogo aberto (~20s)...")
            }
            val out = FusaoBridge.preloadRecord(20)
            val info = FusaoBridge.preloadStatus()
            _state.update {
                it.copy(
                    preloadInfo = info + listOf("---") + out,
                    busy = Busy.NONE,
                    notice = out.lastOrNull().orEmpty(),
                )
            }
        }
    }

    fun ramcleanNow() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.RAMCLEAN) }
            val out = FusaoBridge.ramcleanNow()
            val info = FusaoBridge.ramcleanStatus()
            val st = FusaoBridge.status()
            _state.update {
                it.copy(
                    ramcleanInfo = info + listOf("---") + out, status = st,
                    busy = Busy.NONE,
                    notice = out.lastOrNull().orEmpty(),
                )
            }
        }
    }

    fun loadRamclean() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.RAMCLEAN) }
            val info = FusaoBridge.ramcleanStatus()
            _state.update { it.copy(ramcleanInfo = info, busy = Busy.NONE) }
        }
    }

    fun loadGames() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.GAMELIST) }
            val games = FusaoBridge.gamelist()
            _state.update { it.copy(games = games, busy = Busy.NONE) }
        }
    }

    fun gameAdd(pkg: String) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.GAMELIST) }
            val out = FusaoBridge.gamelistAdd(pkg)
            val games = FusaoBridge.gamelist()
            _state.update { it.copy(games = games, busy = Busy.NONE, notice = out) }
        }
    }

    fun gameDel(pkg: String) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.GAMELIST) }
            val out = FusaoBridge.gamelistDel(pkg)
            val games = FusaoBridge.gamelist()
            _state.update { it.copy(games = games, busy = Busy.NONE, notice = out) }
        }
    }

    fun loadLog(fusaod: Boolean) {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.LOG, logFusaod = fusaod) }
            val lines = FusaoBridge.logTail(120, fusaod)
            _state.update { it.copy(logLines = lines, busy = Busy.NONE) }
        }
    }

    fun bugreport() {
        if (!guardRoot()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.BUGREPORT) }
            val out = FusaoBridge.bugreport()
            _state.update {
                it.copy(
                    busy = Busy.NONE,
                    notice = out.lastOrNull().orEmpty(),
                    logLines = out,
                )
            }
        }
    }

    fun loadApps(ctx: Context) {
        viewModelScope.launch {
            _state.update { it.copy(busy = Busy.APPS) }
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = runCatching {
                pm.queryIntentActivities(intent, 0)
            }.getOrDefault(emptyList())
            val list = found.mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                val label = ri.loadLabel(pm)?.toString() ?: pkg
                AppEntry(pkg, label)
            }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
            _state.update { it.copy(apps = list, busy = Busy.NONE) }
        }
    }

    fun appLabel(pkg: String): String =
        _state.value.apps.firstOrNull { it.pkg == pkg }?.label ?: pkg

    fun clearNotice() {
        _state.update { it.copy(notice = "") }
    }

    private fun guardRoot(): Boolean {
        if (_state.value.rooted) return true
        _state.update { it.copy(notice = "Sem root — refaça o temp-root e reabra.") }
        return false
    }
}
