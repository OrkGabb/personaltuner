package com.orkgabb.fusao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val vm: FusaoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by vm.state.collectAsState()
                LaunchedEffect(Unit) { vm.bootstrap() }
                FusaoScreen(state)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FusaoScreen(state: FusaoUiState) {
        var tab by remember { mutableIntStateOf(0) }
        val tabs = listOf("Status", "Flags", "Preload", "Jogos", "Log")
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("FUSAO" + if (state.moduleVersion.isNotEmpty() && state.moduleVersion != "—") " " + state.moduleVersion.substringBefore(" ") else "") },
                    actions = {
                        Text(
                            "Atualizar",
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .clickable(enabled = !state.anyBusy()) { vm.refresh() },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                )
            }
        ) { pad ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
            ) {
                if (!state.ready) {
                    Text("Aguarde...", modifier = Modifier.padding(16.dp))
                    return@Column
                }
                if (!state.rooted) {
                    Banner("Sem root — modo leitura. Refaça o temp-root e reabra o app.")
                } else if (!state.moduleInstalled) {
                    Banner("Módulo encore ausente.")
                }
                if (state.notice.isNotEmpty()) {
                    Banner(state.notice, onClose = { vm.clearNotice() })
                }
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
                when (tab) {
                    0 -> StatusTab(state)
                    1 -> FlagsTab(state)
                    2 -> PreloadTab(state)
                    3 -> GamesTab(state)
                    4 -> LogTab(state)
                }
            }
        }
    }

    @Composable
    private fun Banner(text: String, onClose: (() -> Unit)? = null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (onClose != null) {
                    Text(
                        "OK",
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clickable { onClose() },
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusTab(state: FusaoUiState) {
        val st = state.status
        val busy = state.anyBusy()
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Perfil", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("game", "balanced", "battery").forEach { p ->
                                val sel = st.profile == p
                                if (sel) {
                                    Button(onClick = {}, enabled = false) { Text(p) }
                                } else {
                                    OutlinedButton(
                                        onClick = { vm.setProfile(p) },
                                        enabled = state.rooted && !busy
                                    ) { Text(p) }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Jogo ativo: " + if (st.inGame) st.gameActive else "— (idle)")
                        Text("Display: " + st.display.ifEmpty { "—" })
                        Text("Thermal: " + st.thermal.ifEmpty { "—" })
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Daemons", style = MaterialTheme.typography.titleMedium)
                        Text("fusaod: " + daemonText(st.fusaodRunning))
                        Text("watch: " + daemonText(st.watchRunning))
                        Text("observer: " + daemonText(st.observerRunning))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Ganchos (foi ou não)", style = MaterialTheme.typography.titleMedium)
                        if (st.hooks.isEmpty()) {
                            Text("—")
                        } else {
                            st.hooks.forEach { h -> MonoLine(h) }
                        }
                    }
                }
            }
        }
    }

    private fun daemonText(running: Boolean): String = if (running) "vivo" else "parado"

    @Composable
    private fun FlagsTab(state: FusaoUiState) {
        val st = state.status
        val busy = state.anyBusy()
        val defs = listOf(
            "fusaod" to "Escritor CPU/GPU/MIF/INT/DISP (sobe no boot)",
            "framework" to "Triads + overlay + tune no game-enter",
            "perf" to "renice/IO do jogo no game-enter",
            "dnd" to "Arma auto-DND no game-enter (off = desarmado)",
            "preload" to "WILLNEED dos assets no game-enter",
            "ramclean" to "Limpa RAM ANTES do preload no game-enter",
            "gos" to "Mata o GOS da Samsung (off = pausado)",
        )
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(defs) { (cmd, desc) ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cmd, style = MaterialTheme.typography.titleSmall)
                            Text(desc, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = st.flag(cmd),
                            onCheckedChange = { vm.toggleFlag(cmd, it) },
                            enabled = state.rooted && !busy
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PreloadTab(state: FusaoUiState) {
        val busy = state.anyBusy()
        LaunchedEffect(Unit) {
            if (state.preloadInfo.isEmpty()) vm.loadPreload()
            if (state.ramcleanInfo.isEmpty()) vm.loadRamclean()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Preload (WILLNEED)", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.loadPreload() },
                            enabled = state.rooted && !busy
                        ) { Text("Atualizar") }
                        Button(
                            onClick = { vm.preloadNow() },
                            enabled = state.rooted && !busy && state.status.inGame
                        ) { Text("Preload agora") }
                    }
                    OutlinedButton(
                        onClick = { vm.preloadRecord() },
                        enabled = state.rooted && !busy && state.status.inGame
                    ) { Text(if (state.isBusy(Busy.RECORD)) "Observando..." else "Record 20s (jogo aberto)") }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (state.preloadInfo.isEmpty()) {
                        Text("—")
                    } else {
                        state.preloadInfo.forEach { l -> MonoLine(l) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("RAM clean (antes do preload)", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.loadRamclean() },
                            enabled = state.rooted && !busy
                        ) { Text("Atualizar") }
                        Button(
                            onClick = { vm.ramcleanNow() },
                            enabled = state.rooted && !busy
                        ) { Text("Limpar agora") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (state.ramcleanInfo.isEmpty()) {
                        Text("—")
                    } else {
                        state.ramcleanInfo.forEach { l -> MonoLine(l) }
                    }
                }
            }
        }
    }

    @Composable
    private fun GamesTab(state: FusaoUiState) {
        val ctx = LocalContext.current
        var pickerOpen by remember { mutableStateOf(false) }
        val busy = state.anyBusy()
        LaunchedEffect(Unit) {
            if (state.games.isEmpty()) vm.loadGames()
        }
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.loadGames() },
                    enabled = state.rooted && !busy
                ) { Text("Atualizar") }
                Button(
                    onClick = {
                        vm.loadApps(ctx)
                        pickerOpen = true
                    },
                    enabled = state.rooted && !busy
                ) { Text("Adicionar") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.games) { pkg ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(vm.appLabel(pkg), style = MaterialTheme.typography.titleSmall)
                                Text(pkg, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "Remover",
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .clickable(enabled = state.rooted && !busy) { vm.gameDel(pkg) },
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
        if (pickerOpen) {
            AppPicker(
                state = state,
                onPick = { pkg ->
                    pickerOpen = false
                    vm.gameAdd(pkg)
                },
                onClose = { pickerOpen = false }
            )
        }
    }

    @Composable
    private fun AppPicker(state: FusaoUiState, onPick: (String) -> Unit, onClose: () -> Unit) {
        var query by remember { mutableStateOf("") }
        val inList = state.games.toSet()
        val shown = state.apps.filter { a ->
            a.pkg !in inList &&
                (query.isEmpty() || a.label.contains(query, ignoreCase = true) || a.pkg.contains(query, ignoreCase = true))
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Adicionar jogo") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Buscar") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        if (state.isBusy(Busy.APPS)) {
                            item { Text("Listando apps...") }
                        } else {
                            items(shown.take(200)) { a ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(a.pkg) }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(a.label, style = MaterialTheme.typography.titleSmall)
                                    Text(a.pkg, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = onClose) { Text("Fechar") }
            }
        )
    }

    @Composable
    private fun LogTab(state: FusaoUiState) {
        val busy = state.anyBusy()
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.loadLog(false) },
                    enabled = state.rooted && !busy
                ) { Text("fusao.log") }
                OutlinedButton(
                    onClick = { vm.loadLog(true) },
                    enabled = state.rooted && !busy
                ) { Text("fusaod.log") }
                OutlinedButton(
                    onClick = { vm.bugreport() },
                    enabled = state.rooted && !busy
                ) { Text("Bugreport") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (state.logFusaod) "fusaod.log (writer)" else "fusao.log (ganchos)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.logLines) { l -> MonoLine(l) }
            }
        }
    }

    @Composable
    private fun MonoLine(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 1.dp)
        )
    }
}
