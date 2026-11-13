package com.orkgabb.fusao.block

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orkgabb.fusao.ui.CyberSwitch
import com.orkgabb.fusao.ui.FusaoPalette
import com.orkgabb.fusao.ui.HudSectionHeader
import com.orkgabb.fusao.ui.TacticalButton
import com.orkgabb.fusao.ui.TacticalPanel
import com.orkgabb.fusao.ui.TelemetryPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private val BlockAccent: Color = FusaoPalette.CrimsonSoft
private const val LOG_SHOWN: Int = 40

/** Regra aberta no diálogo; [id] null = regra nova. */
private data class RuleDraft(val id: Long?)

/**
 * Aba Bloqueio: regras, ajustes, teste e registro do bloqueio de chamadas e SMS. Lê e escreve só o
 * [BlockStore]; quem bloqueia de fato são os serviços do pacote `block`. Regras e ajustes funcionam sem root;
 * o root (pelo módulo) só entra para conceder de uma vez o papel de triagem, o listener e as permissões.
 */
@Composable
fun BlockTab(
    rooted: Boolean,
    moduleInstalled: Boolean,
    grantBusy: Boolean,
    anyBusy: Boolean,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    val context: Context = LocalContext.current
    val store: BlockStore = remember(context) { BlockStore.get(context) }
    val cfg: BlockConfig by store.config.collectAsStateWithLifecycle()
    val logVersion: Int by store.logVersion.collectAsStateWithLifecycle()

    var access: BlockAccessState by remember { mutableStateOf(BlockAccess.read(context)) }
    val refreshAccess: () -> Unit = remember(context) { { access = BlockAccess.read(context) } }
    // papel, listener e permissões mudam fora do app (configurações) e pelo módulo (root)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(grantBusy) {
        if (!grantBusy) refreshAccess()
    }

    var saveFailed: Boolean by remember { mutableStateOf(false) }
    val update: ((BlockConfig) -> BlockConfig) -> Unit = remember(store) {
        { transform -> saveFailed = !store.update(transform) }
    }
    var draft: RuleDraft? by remember { mutableStateOf(null) }

    val events: List<BlockEvent> by produceState(initialValue = emptyList(), store, logVersion) {
        value = withContext(Dispatchers.IO) { store.readLog(LOG_SHOWN) }
    }
    val canGrantByRoot: Boolean = rooted && moduleInstalled

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "block_header") {
            HeaderPanel(
                cfg = cfg,
                access = access,
                saveFailed = saveFailed,
                onToggle = { on: Boolean ->
                    update { it.copy(enabled = on) }
                    if (on && !access.complete && canGrantByRoot && !anyBusy) onGrant()
                },
            )
        }
        item(key = "block_access") {
            AccessPanel(
                access = access,
                canGrantByRoot = canGrantByRoot,
                grantBusy = grantBusy,
                anyBusy = anyBusy,
                onGrant = onGrant,
                onRevoke = onRevoke,
                onChanged = refreshAccess,
            )
        }
        item(key = "block_rules_header") {
            RulesHeaderPanel(cfg = cfg, onAdd = { draft = RuleDraft(null) }, store = store, onSaveFailed = { saveFailed = it })
        }
        items(items = cfg.rules, key = { "block_rule_${it.id}" }) { rule: BlockRule ->
            RuleRow(rule = rule, onClick = { draft = RuleDraft(rule.id) })
        }
        item(key = "block_settings") {
            SettingsPanel(cfg = cfg, access = access, update = update)
        }
        item(key = "block_test") {
            TestPanel(cfg = cfg)
        }
        item(key = "block_log") {
            LogPanel(events = events, onClear = { store.clearLog() })
        }
        item(key = "block_about") {
            AboutPanel()
        }
    }

    val editing: RuleDraft? = draft
    if (editing != null) {
        RuleDialog(
            existing = editing.id?.let { id: Long -> cfg.rules.firstOrNull { it.id == id } },
            onClose = { draft = null },
            onSave = { pattern: String, match: MatchType, channels: RuleChannels, action: RuleAction, label: String ->
                val id: Long = editing.id ?: cfg.nextRuleId
                val rule: BlockRule? = BlockRule.create(id, pattern, match, channels, action, label)
                if (rule != null) {
                    update { current: BlockConfig ->
                        if (current.rules.any { it.id == id }) {
                            current.copy(rules = current.rules.map { if (it.id == id) rule else it })
                        } else {
                            current.copy(rules = current.rules + rule)
                        }
                    }
                    draft = null
                }
                rule != null
            },
            onDelete = {
                update { current: BlockConfig -> current.copy(rules = current.rules.filterNot { it.id == editing.id }) }
                draft = null
            },
        )
    }
}

@Composable
private fun HeaderPanel(
    cfg: BlockConfig,
    access: BlockAccessState,
    saveFailed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val working: Boolean = cfg.enabled && (access.callsReady || access.smsReady)
    val pillText: String = when {
        !cfg.enabled -> "DESLIGADO"
        !access.callsReady && !access.smsReady -> "SEM PERMISSÃO"
        !access.complete -> "PARCIAL"
        else -> "ATIVO"
    }
    val pillColor: Color = when {
        !cfg.enabled -> FusaoPalette.TextMuted
        !access.callsReady && !access.smsReady -> FusaoPalette.CrimsonCore
        !access.complete -> FusaoPalette.IgnisAmber
        else -> FusaoPalette.BioEmerald
    }
    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = if (cfg.enabled) BlockAccent else FusaoPalette.BorderSubtle,
        highlighted = working,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(
                title = "Bloqueio",
                subtitle = "Chamadas rejeitadas antes de tocar · SMS sem notificação",
                accentColor = BlockAccent,
                trailing = { TelemetryPill(text = pillText, color = pillColor, filled = cfg.enabled) },
            )
            SwitchRow(
                text = if (cfg.enabled) {
                    "Ligado · ${cfg.rules.size} ${if (cfg.rules.size == 1) "regra" else "regras"}"
                } else {
                    "Desligado: nada é bloqueado"
                },
                checked = cfg.enabled,
                onCheckedChange = onToggle,
            )
            if (saveFailed) {
                Text(
                    text = "FALHOU: não foi possível gravar a configuração. O ajuste anterior continua valendo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.CrimsonCore,
                )
            }
        }
    }
}

@Composable
private fun AccessPanel(
    access: BlockAccessState,
    canGrantByRoot: Boolean,
    grantBusy: Boolean,
    anyBusy: Boolean,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
    onChanged: () -> Unit,
) {
    val context: Context = LocalContext.current
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = if (access.complete) FusaoPalette.BioEmerald else FusaoPalette.IgnisAmber,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(
                title = "Permissões",
                accentColor = if (access.complete) FusaoPalette.BioEmerald else FusaoPalette.IgnisAmber,
                trailing = {
                    TelemetryPill(
                        text = "${access.grantedCount}/4",
                        color = if (access.complete) FusaoPalette.BioEmerald else FusaoPalette.IgnisAmber,
                        filled = access.complete,
                    )
                },
            )
            AccessRow("Triagem de chamadas", "Sem ela nenhuma chamada passa pelo bloqueio", access.role)
            AccessRow("Acesso a notificações", "Cancela a notificação do SMS bloqueado", access.listener)
            AccessRow("Receber SMS", "Mostra o número real de quem mandou", access.sms)
            AccessRow("Contatos", "Regra \"fora dos contatos\" e triagem de quem está na agenda", access.contacts)

            if (canGrantByRoot) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!access.complete) {
                        TacticalButton(
                            text = "Conceder tudo",
                            onClick = onGrant,
                            enabled = !anyBusy,
                            filled = true,
                            isLoading = grantBusy,
                            accentColor = BlockAccent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (access.role || access.listener) {
                        TacticalButton(
                            text = "Devolver",
                            onClick = onRevoke,
                            enabled = !anyBusy,
                            isLoading = grantBusy,
                            accentColor = FusaoPalette.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (access.role) {
                    Text(
                        text = "A triagem só pode ter um app: enquanto for do FUSAO, a identificação de spam da " +
                            "Samsung fica sem ela. \"Devolver\" entrega de volta a quem tinha antes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FusaoPalette.TextSecondary,
                    )
                }
            } else if (!access.complete) {
                // sem root (ou sem o módulo): os mesmos pedidos pelas telas do sistema
                Text(
                    text = "Sem root, conceda pelas telas do sistema:",
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.TextSecondary,
                )
                if (!access.role) {
                    TacticalButton(
                        text = "Pedir triagem de chamadas",
                        onClick = {
                            BlockAccess.roleRequestIntent(context)?.let { runCatching { roleLauncher.launch(it) } }
                        },
                        accentColor = BlockAccent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!access.sms || !access.contacts) {
                    TacticalButton(
                        text = "Pedir SMS e contatos",
                        onClick = { runCatching { permissionLauncher.launch(BlockAccess.RUNTIME_PERMISSIONS) } },
                        accentColor = BlockAccent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!access.listener) {
                    TacticalButton(
                        text = "Abrir acesso a notificações",
                        onClick = { runCatching { context.startActivity(BlockAccess.listenerSettingsIntent()) } },
                        accentColor = BlockAccent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessRow(title: String, detail: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = FusaoPalette.TextPrimary)
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = FusaoPalette.TextSecondary)
        }
        TelemetryPill(
            text = if (granted) "OK" else "FALTA",
            color = if (granted) FusaoPalette.BioEmerald else FusaoPalette.IgnisAmber,
            filled = granted,
        )
    }
}

@Composable
private fun RulesHeaderPanel(
    cfg: BlockConfig,
    onAdd: () -> Unit,
    store: BlockStore,
    onSaveFailed: (Boolean) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var transferNote: String by remember { mutableStateOf("") }

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = BlockAccent,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(
                title = "Regras",
                subtitle = "A primeira que casar vence · toque para editar",
                accentColor = BlockAccent,
                trailing = { TelemetryPill(text = "${cfg.rules.size}", color = BlockAccent, filled = cfg.rules.isNotEmpty()) },
            )
            TacticalButton(
                text = "Nova regra",
                onClick = onAdd,
                filled = true,
                accentColor = BlockAccent,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TacticalButton(
                    text = "Copiar regras",
                    onClick = {
                        clipboard.setText(AnnotatedString(BlockRulesText.export(cfg.rules)))
                        transferNote = "${cfg.rules.size} regras copiadas"
                    },
                    enabled = cfg.rules.isNotEmpty(),
                    accentColor = FusaoPalette.CryoCyan,
                    modifier = Modifier.weight(1f),
                )
                TacticalButton(
                    text = "Colar regras",
                    onClick = {
                        val text: String = clipboard.getText()?.text.orEmpty()
                        val current: BlockConfig = store.current()
                        val result: BlockRulesText.ImportResult =
                            BlockRulesText.import(text, current.rules, current.nextRuleId)
                        val saved: Boolean =
                            result.rules.isEmpty() || store.update { it.copy(rules = it.rules + result.rules) }
                        onSaveFailed(!saved)
                        transferNote = when {
                            !saved -> "FALHOU: não foi possível gravar"
                            result.rules.isEmpty() && result.skipped == 0 -> "Nada para colar"
                            else -> "${result.rules.size} adicionadas · ${result.skipped} ignoradas"
                        }
                    },
                    accentColor = FusaoPalette.CryoCyan,
                    modifier = Modifier.weight(1f),
                )
            }
            if (transferNote.isNotEmpty()) {
                Text(
                    text = transferNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (transferNote.startsWith("FALHOU")) FusaoPalette.CrimsonCore else FusaoPalette.TextSecondary,
                )
            }
            if (cfg.rules.isEmpty()) {
                Text(
                    text = "Sem regras. Exemplos: 0800* (curinga) bloqueia telemarketing; 11912345678 (exato) " +
                        "bloqueia um número; 28000 (exato, SMS) cala um código curto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun RuleRow(rule: BlockRule, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FusaoPalette.VoidBlack.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = if (rule.valid) FusaoPalette.BorderSubtle else FusaoPalette.CrimsonCore.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = rule.pattern,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                color = FusaoPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    rule.match.label,
                    rule.channels.label,
                    rule.label.takeIf { it.isNotEmpty() },
                    "padrão inválido: nunca casa".takeIf { !rule.valid },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (rule.valid) FusaoPalette.TextSecondary else FusaoPalette.CrimsonCore,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TelemetryPill(
            text = rule.action.label.uppercase(),
            color = if (rule.action == RuleAction.BLOCK) BlockAccent else FusaoPalette.IgnisAmber,
            filled = true,
        )
    }
}

@Composable
private fun SettingsPanel(
    cfg: BlockConfig,
    access: BlockAccessState,
    update: ((BlockConfig) -> BlockConfig) -> Unit,
) {
    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = FusaoPalette.CryoCyan,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(title = "Ajustes", accentColor = FusaoPalette.CryoCyan)

            SectionLabel("CHAMADAS")
            SwitchRow("Bloquear chamadas", cfg.blockCalls) { on -> update { it.copy(blockCalls = on) } }
            SwitchRow("Sem identificação (número privado)", cfg.blockPrivate) { on -> update { it.copy(blockPrivate = on) } }
            SwitchRow("Fora dos contatos", cfg.blockNonContacts) { on -> update { it.copy(blockNonContacts = on) } }
            if (cfg.blockNonContacts && !access.contacts) {
                Text(
                    text = "Sem a permissão de contatos esta regra não bloqueia ninguém.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.IgnisAmber,
                )
            }
            SwitchRow("Silenciar em vez de rejeitar", cfg.silenceCalls) { on -> update { it.copy(silenceCalls = on) } }
            SwitchRow("Horário de silêncio (só chamadas)", cfg.quietEnabled) { on -> update { it.copy(quietEnabled = on) } }
            if (cfg.quietEnabled) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField(
                        label = "Das",
                        value = cfg.quietStart,
                        onCommit = { time: String -> update { it.copy(quietStart = time) } },
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        label = "Até",
                        value = cfg.quietEnd,
                        onCommit = { time: String -> update { it.copy(quietEnd = time) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                SwitchRow("Rejeitar no horário (desligado: só silencia)", cfg.quietBlock) { on ->
                    update { it.copy(quietBlock = on) }
                }
            }

            SectionLabel("SMS")
            SwitchRow("Bloquear SMS (cancela a notificação)", cfg.blockSms) { on -> update { it.copy(blockSms = on) } }
            PackagesField(
                value = cfg.notifPackages,
                onCommit = { packages: Set<String> -> update { it.copy(notifPackages = packages) } },
            )

            SectionLabel("REGRAS")
            SwitchRow("Regra exata também casa pelo final (10+ dígitos)", cfg.suffixMatch) { on ->
                update { it.copy(suffixMatch = on) }
            }
        }
    }
}

/** Campo HH:MM: só grava quando o texto vira um horário válido. */
@Composable
private fun TimeField(label: String, value: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    var text: String by remember(value) { mutableStateOf(value) }
    val clean: String? = BlockConfig.cleanTime(text)
    BlockField(
        value = text,
        onValueChange = { typed: String ->
            text = typed.take(5)
            val parsed: String? = BlockConfig.cleanTime(text)
            if (parsed != null && text.length == 5 && parsed != value) onCommit(parsed)
        },
        placeholder = "$label HH:MM",
        isError = clean == null,
        keyboardType = KeyboardType.Ascii,
        modifier = modifier,
    )
}

/** Apps de SMS monitorados: texto livre, gravado pelo botão (pacote digitado pela metade não vale). */
@Composable
private fun PackagesField(value: Set<String>, onCommit: (Set<String>) -> Unit) {
    val joined: String = value.joinToString(", ")
    var text: String by remember(joined) { mutableStateOf(joined) }
    val parsed: Set<String> = BlockConfig.cleanPackages(text)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Apps de SMS monitorados (pacotes, separados por vírgula)",
            style = MaterialTheme.typography.bodySmall,
            color = FusaoPalette.TextSecondary,
        )
        BlockField(
            value = text,
            onValueChange = { text = it },
            placeholder = "com.samsung.android.messaging",
            isError = parsed.isEmpty(),
            keyboardType = KeyboardType.Ascii,
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        if (parsed != value) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TacticalButton(
                    text = "Gravar apps",
                    onClick = { onCommit(parsed) },
                    enabled = parsed.isNotEmpty(),
                    filled = true,
                    accentColor = FusaoPalette.CryoCyan,
                    modifier = Modifier.weight(1f),
                )
                TacticalButton(
                    text = "Padrão",
                    onClick = { onCommit(BlockConfig.DEFAULT_NOTIF_PACKAGES) },
                    accentColor = FusaoPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Testa um número no motor com a configuração e a agenda de agora. Não faz chamada nem manda SMS. */
@Composable
private fun TestPanel(cfg: BlockConfig) {
    val context: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    var number: String by rememberSaveable { mutableStateOf("") }
    var result: String by remember { mutableStateOf("") }

    val run: (BlockChannel) -> Unit = { channel: BlockChannel ->
        scope.launch {
            val now: Calendar = Calendar.getInstance()
            val decision: BlockDecision = withContext(Dispatchers.IO) {
                BlockEngine.evaluate(
                    cfg = cfg,
                    rawNumber = number,
                    channel = channel,
                    hour = now.get(Calendar.HOUR_OF_DAY),
                    minute = now.get(Calendar.MINUTE),
                    isContact = { lookupContact(context, number) },
                )
            }
            val what: String = if (channel == BlockChannel.CALL) "Chamada" else "SMS"
            result = when (decision.verdict) {
                Verdict.ALLOW -> if (cfg.enabled) "$what: passa" else "$what: passa (bloqueio desligado)"
                Verdict.BLOCK -> "$what: BLOQUEIA · ${decision.reason}"
                Verdict.SILENCE -> "$what: SILENCIA · ${decision.reason}"
            }
        }
    }

    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = FusaoPalette.PlasmaViolet,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudSectionHeader(
                title = "Testar número",
                subtitle = "Só consulta as regras: não liga nem manda SMS",
                accentColor = FusaoPalette.PlasmaViolet,
            )
            BlockField(
                value = number,
                onValueChange = { number = it.take(40) },
                placeholder = "Número (vazio = sem identificação)",
                keyboardType = KeyboardType.Phone,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TacticalButton(
                    text = "Como chamada",
                    onClick = { run(BlockChannel.CALL) },
                    accentColor = FusaoPalette.PlasmaViolet,
                    modifier = Modifier.weight(1f),
                )
                TacticalButton(
                    text = "Como SMS",
                    onClick = { run(BlockChannel.SMS) },
                    accentColor = FusaoPalette.PlasmaViolet,
                    modifier = Modifier.weight(1f),
                )
            }
            if (result.isNotEmpty()) {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = if (result.contains("passa")) FusaoPalette.BioEmerald else BlockAccent,
                )
            }
        }
    }
}

@Composable
private fun LogPanel(events: List<BlockEvent>, onClear: () -> Unit) {
    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = FusaoPalette.CryoTurquoise,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HudSectionHeader(
                title = "Registro",
                subtitle = "Últimos bloqueios (fica só no aparelho)",
                accentColor = FusaoPalette.CryoTurquoise,
                trailing = {
                    TelemetryPill(text = "${events.size}", color = FusaoPalette.CryoTurquoise, filled = events.isNotEmpty())
                },
            )
            if (events.isEmpty()) {
                Text(
                    text = "Nada bloqueado ainda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FusaoPalette.TextMuted,
                )
            } else {
                events.forEach { event: BlockEvent ->
                    Column {
                        Text(
                            text = "${event.time.drop(5).dropLast(3)} · ${if (event.channel == "CALL") "chamada" else "SMS"} · " +
                                event.number,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = FusaoPalette.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${if (event.verdict == "SILENCE") "silenciada" else "bloqueada"} · ${event.detail}",
                            style = MaterialTheme.typography.bodySmall,
                            color = FusaoPalette.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TacticalButton(
                    text = "Apagar registro",
                    onClick = onClear,
                    accentColor = FusaoPalette.TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AboutPanel() {
    TacticalPanel(
        modifier = Modifier.fillMaxWidth(),
        accentColor = FusaoPalette.BorderSubtle,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HudSectionHeader(title = "Como funciona", accentColor = FusaoPalette.TextSecondary)
            ABOUT_LINES.forEach { line: String ->
                Text(text = line, style = MaterialTheme.typography.bodySmall, color = FusaoPalette.TextSecondary)
            }
        }
    }
}

/** Criar/editar regra. [onSave] devolve false quando o padrão não serve (o diálogo mostra o motivo). */
@Composable
private fun RuleDialog(
    existing: BlockRule?,
    onClose: () -> Unit,
    onSave: (String, MatchType, RuleChannels, RuleAction, String) -> Boolean,
    onDelete: () -> Unit,
) {
    var pattern: String by rememberSaveable { mutableStateOf(existing?.pattern.orEmpty()) }
    var match: MatchType by rememberSaveable { mutableStateOf(existing?.match ?: MatchType.EXACT) }
    var channels: RuleChannels by rememberSaveable { mutableStateOf(existing?.channels ?: RuleChannels.BOTH) }
    var action: RuleAction by rememberSaveable { mutableStateOf(existing?.action ?: RuleAction.BLOCK) }
    var label: String by rememberSaveable { mutableStateOf(existing?.label.orEmpty()) }
    var rejected: Boolean by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TacticalPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            accentColor = BlockAccent,
            containerColor = FusaoPalette.ObsidianSurface,
            highlighted = true,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HudSectionHeader(
                    title = if (existing == null) "Nova regra" else "Editar regra",
                    subtitle = "Só os dígitos do número são comparados",
                    accentColor = BlockAccent,
                )
                BlockField(
                    value = pattern,
                    onValueChange = {
                        pattern = it.take(BlockRule.MAX_PATTERN)
                        rejected = false
                    },
                    placeholder = when (match) {
                        MatchType.EXACT -> "11912345678"
                        MatchType.PREFIX -> "0800"
                        MatchType.WILDCARD -> "0800*  (* = qualquer sequência, ? = um dígito)"
                        MatchType.REGEX -> "^55119[0-9]{8}$"
                    },
                    isError = rejected,
                    keyboardType = if (match == MatchType.EXACT || match == MatchType.PREFIX) {
                        KeyboardType.Phone
                    } else {
                        KeyboardType.Ascii
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (rejected) {
                    Text(
                        text = when (match) {
                            MatchType.REGEX -> "Expressão regular inválida."
                            MatchType.WILDCARD -> "O curinga precisa de dígitos, * ou ?."
                            else -> "O padrão precisa ter pelo menos um dígito."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = FusaoPalette.CrimsonCore,
                    )
                }

                SectionLabel("TIPO")
                ChoiceRow(MatchType.entries, match, { it.label }) {
                    match = it
                    rejected = false
                }
                SectionLabel("ONDE VALE")
                ChoiceRow(RuleChannels.entries, channels, { it.label }) { channels = it }
                SectionLabel("AÇÃO (EM SMS, SILENCIAR = BLOQUEAR)")
                ChoiceRow(RuleAction.entries, action, { it.label }) { action = it }

                BlockField(
                    value = label,
                    onValueChange = { label = it.take(BlockRule.MAX_LABEL) },
                    placeholder = "Rótulo (opcional): aparece no registro",
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TacticalButton(
                        text = "Cancelar",
                        onClick = onClose,
                        accentColor = FusaoPalette.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TacticalButton(
                        text = "Gravar",
                        onClick = { rejected = !onSave(pattern, match, channels, action, label) },
                        filled = true,
                        accentColor = BlockAccent,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (existing != null) {
                    TacticalButton(
                        text = "Remover regra",
                        onClick = onDelete,
                        accentColor = FusaoPalette.CrimsonCore,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option: T ->
            TacticalButton(
                text = label(option),
                onClick = { onSelect(option) },
                filled = option == selected,
                accentColor = BlockAccent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = FusaoPalette.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        )
        CyberSwitch(checked = checked, onCheckedChange = onCheckedChange, accentColor = BlockAccent)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = FusaoPalette.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BlockField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = FusaoPalette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        isError = isError,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = FusaoPalette.TextPrimary,
            fontFamily = FontFamily.Monospace,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BlockAccent,
            unfocusedBorderColor = FusaoPalette.BorderSubtle,
            errorBorderColor = FusaoPalette.CrimsonCore,
            focusedContainerColor = FusaoPalette.VoidBlack.copy(alpha = 0.65f),
            unfocusedContainerColor = FusaoPalette.VoidBlack.copy(alpha = 0.45f),
            errorContainerColor = FusaoPalette.VoidBlack.copy(alpha = 0.65f),
            cursorColor = BlockAccent,
        ),
        modifier = modifier,
    )
}

private val ABOUT_LINES: List<String> = listOf(
    "Chamadas: o sistema pergunta ao FUSAO antes de tocar. Bloqueada, a chamada é rejeitada sem tocar e sem " +
        "aviso de perdida, mas fica no histórico. Silenciada, ela segue chamando sem som. Número de " +
        "emergência nunca é bloqueado.",
    "SMS: a mensagem NÃO é apagada. O FUSAO só cancela a notificação; ela continua no app de mensagens. " +
        "Isso mantém o app de SMS e o MMS funcionando.",
    "Quem escapa: mensagem curta (menos de 12 caracteres) de um contato salvo, quando a notificação mostra " +
        "só o nome; e o resumo de várias conversas que alguns apps de SMS deixam.",
    "Remetente com letras (nome de empresa no lugar do número) não tem dígitos: nenhuma regra casa com ele.",
    "Sem root: as regras e os ajustes ficam no app e seguem valendo depois de um reboot. O root só serve " +
        "para conceder as permissões de uma vez.",
    "Privacidade: regras e registro ficam só no aparelho. O app continua sem permissão de internet.",
)
