package com.consumo.tuya

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val BR_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm")

fun fmtIsoDate(iso: String?): String =
    iso?.let { runCatching { LocalDate.parse(it).format(BR_DATE) }.getOrNull() } ?: "não informada"

/** Dias até o vencimento do plano (negativo = vencido); null se não informado. */
fun daysUntil(iso: String?): Long? =
    iso?.let { runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(it)) }.getOrNull() }

/** Quem deve renovar o plano desta casa: o próprio usuário (projeto próprio) ou o admin (central). */
fun canRenew(h: HomeInfo) = h.own || h.isAdmin

fun shareText(ctx: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, title))
}

/** Campo de data (abre calendário). Valor em AAAA-MM-DD. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDateField(value: String?, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(if (value == null) "Escolher data de vencimento" else "Vence em ${fmtIsoDate(value)} (alterar)")
    }
    if (open) {
        val initial = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().plusMonths(1)
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = {
                    val d = Instant.ofEpochMilli(state.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    onChange(d.toString())
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
        ) { DatePicker(state = state) }
    }
}

/** Aviso no topo da tela sobre o plano da Tuya (vencendo, vencido ou sem data). */
@Composable
fun HomeBanner(home: HomeInfo, onRenew: () -> Unit) {
    val days = daysUntil(home.planExpires)
    val expired = home.status == "expired"
    val (title, body, urgent) = when {
        expired && canRenew(home) -> Triple(
            "O plano da Tuya venceu",
            "Sem ele o Medidor+ não consegue ler o medidor. A energia consumida não se perde — aparece quando renovar.",
            true,
        )
        expired -> Triple(
            "Serviço temporariamente indisponível",
            "O plano da Tuya do administrador venceu. Ele já foi avisado; seus dados voltam quando ele renovar.",
            true,
        )
        !canRenew(home) -> return
        days == null -> Triple(
            "Informe quando vence o plano da Tuya",
            if (home.own) "Assim o app avisa antes de vencer." else "Plano central (todas as casas vinculadas). O app avisa antes de vencer.",
            false,
        )
        days <= 7 -> Triple(
            if (days <= 0) "O plano da Tuya vence hoje" else "O plano da Tuya vence em $days ${if (days == 1L) "dia" else "dias"}",
            "Renove agora — a aprovação da Tuya pode levar 1 a 2 dias.",
            true,
        )
        else -> return
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (canRenew(home)) {
                TextButton(onClick = onRenew) { Text(if (days == null && !expired) "Informar data" else "Como renovar") }
            }
        }
    }
}

/** Passo a passo de renovação do plano gratuito da Tuya + nova data de vencimento. */
@Composable
fun RenewScreen(prefs: Prefs, home: HomeInfo, onClose: () -> Unit, onUpdated: (HomeInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(home.planExpires) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Renovar o plano da Tuya", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Fechar") }
                }
                Text(
                    if (home.own) "O plano gratuito do seu projeto na Tuya precisa ser renovado de tempos em tempos."
                    else "Plano do projeto central — vale para todas as casas conectadas pelo Smart Life.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Vencimento atual: ${fmtIsoDate(home.planExpires)}", fontWeight = FontWeight.Bold)

                StepItem(1, "Entre em platform.tuya.com com a sua conta de desenvolvedor (de preferência no computador).")
                OutlinedButton(onClick = { openUrl(ctx, TUYA_PORTAL) }) { Text("Abrir platform.tuya.com") }
                StepItem(2, "Menu Cloud → Development → abra o projeto do Medidor+.")
                StepItem(3, "Na aba Service API (ou em Purchase / Overview), encontre o IoT Core e toque em Extend / Renew (extensão do plano gratuito).")
                StepItem(4, "Preencha o formulário (ex.: uso pessoal, monitoramento de energia da casa) e envie. A aprovação pode levar de algumas horas a 1–2 dias.")
                StepItem(5, "Quando aprovar, informe aqui a nova data de vencimento:")
                PlanDateField(date) { date = it }
                PrimaryButton("Salvar e testar conexão", busy, date != null) {
                    busy = true
                    scope.launch {
                        try {
                            val h = prefs.api().setPlan(date!!)
                            onUpdated(h)
                            result = when (h.status) {
                                "ok" -> "Tudo certo! O medidor voltou a ser lido normalmente."
                                "expired" -> "Data salva, mas a Tuya ainda recusa o acesso. Se você acabou de pedir a renovação, aguarde a aprovação e toque em Salvar de novo."
                                else -> "Data salva. Último erro: ${h.lastError ?: "desconhecido"}"
                            }
                        } catch (e: Exception) {
                            result = e.message
                        }
                        busy = false
                    }
                }
                result?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text(it, Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

/** Seção "Casa" (e "Administração", para o admin) dentro das configurações. */
@Composable
fun HomeSection(prefs: Prefs, home: HomeInfo?, onHome: (HomeInfo) -> Unit, onRenew: () -> Unit, onLeave: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var showHomes by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var name by remember(home?.id) { mutableStateOf(home?.name ?: "") }
    if (home == null) return

    Text("Casa", fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = name, onValueChange = { name = it }, singleLine = true,
        label = { Text("Nome da casa") }, modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            if (name.isNotBlank() && name != home.name) {
                TextButton(onClick = { scope.launch { runCatching { prefs.api().setName(name) }.onSuccess(onHome) } }) { Text("Salvar") }
            }
        },
    )
    Text(
        if (home.own) "Conexão: projeto Tuya próprio" else "Conexão: Smart Life (projeto central)",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(
        onClick = {
            scope.launch {
                runCatching { prefs.api().shareCode() }
                    .onSuccess { c ->
                        val install = if (BuildConfig.DOWNLOAD_URL.isNotEmpty()) "1) Instale: ${BuildConfig.DOWNLOAD_URL}\n" else ""
                        shareText(
                            ctx,
                            "Use o Medidor+ na casa \"${home.name}\":\n$install" +
                                "2) Abra o app, toque em \"Entrar numa casa que já usa o Medidor+\" e digite o código: ${c.code}\n" +
                                "(o código vale 7 dias)",
                            "Compartilhar acesso",
                        )
                    }
                    .onFailure { msg = it.message }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Compartilhar acesso a esta casa") }

    if (canRenew(home)) {
        Text("Plano da Tuya: vence em ${fmtIsoDate(home.planExpires)}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onRenew, modifier = Modifier.fillMaxWidth()) { Text("Renovar / alterar vencimento") }
    }
    if (!home.own) {
        OutlinedButton(onClick = { showSwitch = true }, modifier = Modifier.fillMaxWidth()) { Text("Usar meu próprio projeto Tuya") }
        Text(
            "Para o dono do medidor não depender do administrador: ele mesmo renova o plano da Tuya. O histórico continua.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (home.isAdmin) {
        HorizontalDivider()
        Text("Administração", fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching { prefs.api().createInvite() }
                        .onSuccess { c ->
                            val install = if (BuildConfig.DOWNLOAD_URL.isNotEmpty()) "1) Instale: ${BuildConfig.DOWNLOAD_URL}\n" else ""
                            shareText(
                                ctx,
                                "Convite para usar o Medidor+ com o seu medidor:\n$install" +
                                    "2) Abra o app, toque em \"Cadastrar o meu medidor\" e use o convite: ${c.code}\n" +
                                    "(vale 30 dias, uso único). Se for conectar pelo Smart Life, me avise na hora para eu gerar o QR de vínculo.",
                                "Convidar nova casa",
                            )
                        }
                        .onFailure { msg = it.message }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Convidar nova casa") }
        OutlinedButton(onClick = { showHomes = true }, modifier = Modifier.fillMaxWidth()) { Text("Casas cadastradas") }
        Text(
            "Para vincular o Smart Life de uma casa nova: platform.tuya.com → projeto → Devices → Link App Account → Add App Account → mostre o QR para a pessoa escanear.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    TextButton(onClick = { confirmLeave = true }) { Text("Sair desta casa", color = MaterialTheme.colorScheme.error) }
    msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    if (showHomes) AdminHomesDialog(prefs) { showHomes = false }
    if (showSwitch) SwitchToOwnScreen(prefs, home, onClose = { showSwitch = false }, onUpdated = onHome)
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Sair desta casa?") },
            text = { Text("Este celular deixa de mostrar os dados de \"${home.name}\". Para voltar, você vai precisar de um código de acesso. Os dados da casa não são apagados.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onLeave()
                }) { Text("Sair", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun AdminHomesDialog(prefs: Prefs, onClose: () -> Unit) {
    var data by remember { mutableStateOf<Pair<String?, List<AdminHome>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { prefs.api().adminHomes() }.onSuccess { data = it }.onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Casas cadastradas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                data?.let { (central, homes) ->
                    Text("Plano central vence em ${fmtIsoDate(central)}", style = MaterialTheme.typography.bodySmall)
                    homes.forEach { h ->
                        HorizontalDivider()
                        Text(h.name, fontWeight = FontWeight.Bold)
                        val status = when (h.status) {
                            "ok" -> "funcionando"
                            "expired" -> "plano vencido"
                            "error" -> "com erro"
                            else -> h.status ?: "—"
                        }
                        Text(
                            "${if (h.mode == "own") "Projeto próprio" else "Smart Life"} · $status" +
                                (h.lastOk?.let { " · última leitura ${Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(BR_DATE_TIME)}" } ?: "") +
                                (if (h.mode == "own") " · plano até ${fmtIsoDate(h.planExpires)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (h.status != "ok" && h.lastError != null) {
                            Text(h.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (data == null && error == null) Text("Carregando...")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
    )
}

