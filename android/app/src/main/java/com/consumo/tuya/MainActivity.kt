package com.consumo.tuya

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

val PT: Locale = Locale("pt", "BR")
private val BRL = NumberFormat.getCurrencyInstance(PT)
private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd/MM", PT)
private val TIME_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMM/yyyy", PT)

fun kwh(v: Double?) = if (v == null) "—" else String.format(PT, "%.2f kWh", v)
fun brl(v: Double?) = if (v == null) "" else BRL.format(v)
private fun fmtTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FMT)
fun startOfDayMs(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun pickerDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

/** Número para campo de texto (vírgula decimal; vazio quando zero). */
private fun fieldNum(v: Double) = if (v == 0.0) "" else v.toString().replace('.', ',')
private fun parseNum(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

class MainActivity : ComponentActivity() {
    /** Notificação de plano tocada: abrir direto a tela de renovação. */
    private val openRenew = mutableStateOf(false)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        openRenew.value = intent?.getBooleanExtra(Notifier.EXTRA_OPEN_RENEW, false) == true
        Notifier.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    App(prefs, openRenew.value) { openRenew.value = false }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Notifier.EXTRA_OPEN_RENEW, false)) openRenew.value = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(prefs: Prefs, openRenew: Boolean = false, onRenewOpened: () -> Unit = {}) {
    var connected by remember { mutableStateOf(prefs.configured) }
    var showSettings by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showRenew by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var home by remember { mutableStateOf<HomeInfo?>(null) }
    var settingsVersion by remember { mutableIntStateOf(0) }
    var flags by remember { mutableStateOf<Map<String, Flag>>(emptyMap()) }
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf<Now?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val cost = remember(flags, settingsVersion) { prefs.costModel(flags) }

    LaunchedEffect(reload, connected) {
        if (!connected) return@LaunchedEffect
        loading = true
        error = null
        runCatching { prefs.api().home() }.onSuccess {
            home = it
            if (!it.viewing) prefs.ownHomeId = it.id
        }.onFailure {
            // token removido/inválido: volta para o cadastro
            if (it.message == "não autorizado") {
                if (prefs.viewHome != null) {
                    // casa aberta pelo admin não existe mais: volta para a própria casa
                    prefs.viewHome = null
                    reload++
                } else {
                    prefs.token = ""
                    connected = false
                }
            }
        }
        try {
            now = prefs.api().now()
            runCatching { prefs.api().flags() }.onSuccess { flags = it }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            // plano vencido: atualiza o status da casa para mostrar o aviso certo
            if ((e as? ApiException)?.code == "tuya_expired") runCatching { prefs.api().home() }.onSuccess { home = it }
        }
        loading = false
    }

    // Administrador: abrir outra casa (ou voltar para a própria, com null)
    fun openHome(id: String?) {
        prefs.viewHome = id?.takeIf { it != prefs.ownHomeId }
        home = null
        now = null
        error = null
        showSettings = false
        reload++
    }

    // Veio da notificação de plano: abre a renovação assim que a casa carregar
    LaunchedEffect(openRenew, home) {
        val h = home
        if (openRenew && h != null) {
            if (canRenew(h)) showRenew = true
            onRenewOpened()
        }
    }

    if (!connected) {
        OnboardingScreen(prefs) { h ->
            home = h
            connected = true
            reload++
        }
        if (showManual) ManualScreen(onClose = { showManual = false })
        return
    }

    // Atualiza "Agora" só enquanto o app está visível (pausa em segundo plano):
    // a cada 5 s com consumo alto (ex.: carro carregando), senão a cada 30 s.
    val live = (now?.powerW ?: 0.0) >= LIVE_THRESHOLD_W
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val fast = (now?.powerW ?: 0.0) >= LIVE_THRESHOLD_W
                delay(if (fast) 5_000 else 30_000)
                if (connected && !loading) {
                    runCatching { prefs.api().now() }.onSuccess { now = it; error = null }
                }
            }
        }
    }

    // Puxar a tela para baixo atualiza tudo (potência, gráficos, recargas e verificação de versão)
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { reload++ },
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(home?.name ?: "Medidor+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { showManual = true }) { Text("Ajuda") }
                TextButton(onClick = { showSettings = true }) { Text("Config") }
            }

            home?.takeIf { it.viewing }?.let { h ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Administrador — vendo: ${h.name}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { openHome(null) }) { Text("Voltar à minha casa") }
                    }
                }
            }
            home?.let { HomeBanner(it, onRenew = { showRenew = true }, onSwitch = { showSwitch = true }) }

            error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, Modifier.padding(16.dp))
                }
            }

            now?.let { NowCard(it, cost, live) }
            run {
                SessionsCard(prefs, cost, reload)
                ChartCard(prefs, cost, reload)
                PeriodCard(prefs, cost)
            }
        }
    }

    UpdateGate(prefs, reload)

    if (showManual) ManualScreen(onClose = { showManual = false })

    if (showSwitch) home?.let { h ->
        SwitchToOwnScreen(prefs, h, onClose = { showSwitch = false; reload++ }, onUpdated = { home = it })
    }

    if (showRenew) home?.let { h ->
        RenewScreen(prefs, h, onClose = { showRenew = false; reload++ }, onUpdated = { home = it })
    }

    if (showSettings) {
        SettingsDialog(
            prefs, flags, home,
            onFlagsChange = { flags = it },
            onHome = { home = it },
            onRenew = { showSettings = false; showRenew = true },
            onOpenHome = { openHome(it) },
            onLeave = {
                prefs.token = ""
                home = null
                now = null
                showSettings = false
                connected = false
            },
        ) {
            showSettings = false
            settingsVersion++
            reload++
        }
    }
}

@Composable
fun NowCard(n: Now, cost: CostModel, live: Boolean) {
    val t = System.currentTimeMillis()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Agora", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (live) LiveBadge()
                else Text("atualiza a cada 30 s", style = MaterialTheme.typography.labelSmall)
            }
            Text(String.format(PT, "%.0f W", n.powerW), fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(String.format(PT, "%.1f V · %.2f A", n.voltageV, n.currentA))
            Text("Última leitura: ${fmtTime(n.updatedAt)}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row {
                Stat("Hoje", n.todayKwh, n.todayEstimated, brl(cost.cost(n.todayKwh, t)), Modifier.weight(1f))
                Stat("Ontem", n.yesterdayKwh, n.yesterdayEstimated, brl(cost.cost(n.yesterdayKwh, t - 86_400_000)), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Contador do medidor: ${kwh(n.totalKwh)}", style = MaterialTheme.typography.bodySmall)
            if (cost.detailed) {
                Text(
                    "Bandeira ${cost.flagAt(t).label.lowercase()} · ${brl(cost.pricePerKwh(t))}/kWh",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Potência a partir da qual o app entra em modo "ao vivo" (atualiza a cada 5 s). */
const val LIVE_THRESHOLD_W = 1500.0

/** Indicador pulsante "● Ao vivo". */
@Composable
fun LiveBadge() {
    val pulse = rememberInfiniteTransition(label = "live")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha",
    )
    val color = MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(alpha)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text("Ao vivo · 5 s", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Stat(label: String, v: Double?, estimated: Boolean, costText: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kwh(v), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (estimated && v != null) EstimateIcon()
        }
        Text(costText, style = MaterialTheme.typography.bodySmall)
    }
}

/** Ícone ⓘ que indica valor estimado; ao tocar, explica o motivo. */
@Composable
fun EstimateIcon() {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = "Valor estimado",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("Valor estimado") },
            text = {
                Text(
                    "Faltaram leituras do medidor perto do início ou do fim deste período " +
                        "(coleta falhou, medidor offline ou sem dados ainda), ou o valor do dia foi " +
                        "estimado a partir do contador do medidor. O consumo total entre as leituras " +
                        "existentes é exato; a divisão no tempo é uma estimativa."
                )
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Entendi") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodCard(prefs: Prefs, cost: CostModel) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var days by remember { mutableStateOf<List<Bucket>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val known = days?.filter { it.kwh != null }.orEmpty()
    val total = known.sumOf { it.kwh!! }
    val breakdown = known.fold(CostBreakdown.ZERO) { acc, b -> acc + cost.breakdown(b.kwh!!, b.start) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Período personalizado", style = MaterialTheme.typography.labelLarge)
            range?.let { (s, e) -> Text("${s.format(DATE_FMT)} a ${e.format(DATE_FMT)}") }
            if (known.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(kwh(total), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (known.any { it.estimated }) EstimateIcon()
                }
                Text(brl(breakdown.total), fontWeight = FontWeight.Bold)
                if (cost.detailed) {
                    Text(
                        "Energia ${brl(breakdown.energy)} · Bandeira ${brl(breakdown.flag)} · Impostos ${brl(breakdown.taxes)}" +
                            (if (breakdown.discount < 0) " · Desconto ${brl(breakdown.discount)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "${known.size} dias com dados · média ${kwh(total / known.size)}/dia",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { open = true }) { Text("Escolher período") }
                Spacer(Modifier.weight(1f))
                // Exporta o consumo dia a dia do período escolhido
                range?.let { (s, e) ->
                    ExportMenu(label = "Exportar dias", enabled = known.isNotEmpty()) { f ->
                        val name = "medidor-plus_periodo_${s}_a_${e}"
                        val title = "${s.format(DATE_FMT)} a ${e.format(DATE_FMT)}"
                        runCatching { Exporter.share(ctx, energyTable(name, title, "day", days.orEmpty(), cost), f) }
                            .onFailure { status = it.message }
                    }
                }
            }
        }
    }

    if (open) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val s = pickerDate(state.selectedStartDateMillis!!)
                        val e = state.selectedEndDateMillis?.let(::pickerDate) ?: s
                        open = false
                        range = s to e
                        days = null
                        status = "Calculando..."
                        scope.launch {
                            try {
                                val r = prefs.api().series(startOfDayMs(s), startOfDayMs(e.plusDays(1)), "day")
                                days = r
                                status = if (r.none { it.kwh != null }) "Sem leituras nesse período" else null
                            } catch (ex: Exception) {
                                status = ex.message ?: ex.toString()
                            }
                        }
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun SettingsDialog(
    prefs: Prefs,
    flags: Map<String, Flag>,
    home: HomeInfo?,
    onFlagsChange: (Map<String, Flag>) -> Unit,
    onHome: (HomeInfo) -> Unit,
    onRenew: () -> Unit,
    onLeave: () -> Unit,
    onOpenHome: (String) -> Unit,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.url) }
    var tariff by remember { mutableStateOf(String.format(PT, "%.4f", prefs.tariff)) }
    var detailed by remember { mutableStateOf(prefs.detailed) }
    var te by remember { mutableStateOf(fieldNum(prefs.te)) }
    var tu by remember { mutableStateOf(fieldNum(prefs.tu)) }
    var icms by remember { mutableStateOf(fieldNum(prefs.icmsPct)) }
    var pis by remember { mutableStateOf(fieldNum(prefs.pisPct)) }
    var cofins by remember { mutableStateOf(fieldNum(prefs.cofinsPct)) }
    var energyFull by remember { mutableStateOf(fieldNum(prefs.energyFull)) }
    var energyCharged by remember { mutableStateOf(fieldNum(prefs.energyCharged)) }
    val flagAdd = remember {
        mutableStateOf(Flag.entries.filter { it != Flag.VERDE }.associateWith { fieldNum(prefs.flagAdd(it)) })
    }
    var showFlagValues by remember { mutableStateOf(false) }
    var flagError by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(!prefs.configured) }
    var notifyStart by remember { mutableStateOf(prefs.notifyChargeStart) }
    var notifyEnd by remember { mutableStateOf(prefs.notifyChargeEnd) }
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Configurações") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ---- Tarifa ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tarifa detalhada", fontWeight = FontWeight.Bold)
                        Text(
                            if (detailed) "Tarifa base + bandeira do mês + impostos"
                            else "Desligada: usa só o valor do kWh",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = detailed, onCheckedChange = { detailed = it })
                }

                if (!detailed) {
                    OutlinedTextField(
                        value = tariff, onValueChange = { tariff = it }, singleLine = true,
                        label = { Text("Tarifa (R$/kWh, com impostos)") },
                        supportingText = {
                            Text(
                                "Valor da conta ÷ kWh consumidos. Com FIT: Custo de geração FIT ÷ kWh " +
                                    "(ex.: 461,18 ÷ 503 = 0,9169). Para mais precisão, ligue a tarifa detalhada."
                            )
                        },
                        keyboardOptions = decimal,
                    )
                } else {
                    // Digite os números como estão impressos na fatura — o app faz as contas
                    Text("Conta de luz (CEEE)", fontWeight = FontWeight.Bold)
                    Text(
                        "Tabela \"Itens da Fatura\", última coluna \"Tarifa Unit. (R$)\":",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = te, onValueChange = { te = it }, singleLine = true,
                            label = { Text("Consumo TE") }, placeholder = { Text("0,343370") },
                            keyboardOptions = decimal, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = tu, onValueChange = { tu = it }, singleLine = true,
                            label = { Text("Consumo TU") }, placeholder = { Text("0,478630") },
                            keyboardOptions = decimal, modifier = Modifier.weight(1f),
                        )
                    }
                    Text("Quadro \"Tributo\", coluna \"Alíquota (%)\":", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = icms, onValueChange = { icms = it }, singleLine = true,
                        label = { Text("ICMS (%)") }, placeholder = { Text("17") },
                        keyboardOptions = decimal, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pis, onValueChange = { pis = it }, singleLine = true,
                        label = { Text("PIS (%)") }, placeholder = { Text("0,4885") },
                        keyboardOptions = decimal, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = cofins, onValueChange = { cofins = it }, singleLine = true,
                        label = { Text("COFINS (%)") }, placeholder = { Text("2,2785") },
                        keyboardOptions = decimal, modifier = Modifier.fillMaxWidth(),
                    )

                    Text("Geração compartilhada — opcional (fatura FIT)", fontWeight = FontWeight.Bold)
                    Text(
                        "\"Detalhamento da fatura\", coluna Valores (R$). Deixe vazio se não tiver.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = energyFull, onValueChange = { energyFull = it }, singleLine = true,
                        label = { Text("Preço energia CEEE (R$)") }, placeholder = { Text("524,07") },
                        supportingText = { Text("O valor riscado") },
                        keyboardOptions = decimal, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = energyCharged, onValueChange = { energyCharged = it }, singleLine = true,
                        label = { Text("Custo de geração própria FIT (R$)") }, placeholder = { Text("461,18") },
                        keyboardOptions = decimal, modifier = Modifier.fillMaxWidth(),
                    )

                    // Prévia do resultado, para conferir com a fatura enquanto digita
                    val preview = CostModel(
                        detailed = true,
                        simpleTariff = 0.0,
                        base = (parseNum(te) ?: 0.0) + (parseNum(tu) ?: 0.0),
                        icmsPct = parseNum(icms) ?: 0.0,
                        pisCofinsPct = (parseNum(pis) ?: 0.0) + (parseNum(cofins) ?: 0.0),
                        discountPct = discountFrom(parseNum(energyFull) ?: 0.0, parseNum(energyCharged) ?: 0.0),
                        flagAdd = Flag.entries.associateWith { f ->
                            (if (f == Flag.VERDE) null else parseNum(flagAdd.value[f] ?: "")) ?: f.defaultAdd
                        },
                        flags = flags,
                    )
                    val nowMs = System.currentTimeMillis()
                    if (preview.base > 0) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "Seu kWh: ${String.format(PT, "R$ %.4f", preview.pricePerKwh(nowMs))}",
                                    fontWeight = FontWeight.Bold,
                                )
                                val noDiscount = preview.copy(discountPct = 0.0).pricePerKwh(nowMs)
                                Text(
                                    "Bandeira ${preview.flagAt(nowMs).label.lowercase()} · " +
                                        (if (preview.discountPct > 0)
                                            String.format(PT, "sem desconto R$ %.4f · desconto %.1f%%", noDiscount, preview.discountPct)
                                        else "sem desconto"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    } else {
                        Text(
                            "Enquanto TE e TU estiverem vazios, o app continua usando a tarifa simples.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    // Bandeira de cada mês (salva no servidor, vale para todos os aparelhos)
                    HorizontalDivider()
                    Text("Bandeira de cada mês", fontWeight = FontWeight.Bold)
                    Text("Sem seleção = verde. Toque para alterar.", style = MaterialTheme.typography.bodySmall)
                    val current = YearMonth.now()
                    (0..5).map { current.minusMonths(it.toLong()) }.forEach { m ->
                        val key = m.toString()
                        Text(m.format(MONTH_LABEL).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Flag.entries.forEach { f ->
                                FilterChip(
                                    selected = flags[key] == f,
                                    onClick = {
                                        val newFlag = if (flags[key] == f) null else f
                                        scope.launch {
                                            runCatching { prefs.api().setFlag(key, newFlag) }
                                                .onSuccess { onFlagsChange(it); flagError = null }
                                                .onFailure { flagError = it.message }
                                        }
                                    },
                                    label = { Text(f.label) },
                                )
                            }
                        }
                    }
                    flagError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    TextButton(onClick = { showFlagValues = !showFlagValues }) {
                        Text(if (showFlagValues) "Ocultar valores das bandeiras" else "Valores das bandeiras (R$/kWh)")
                    }
                    if (showFlagValues) {
                        Text(
                            "Adicional sem impostos definido pela ANEEL. Ajuste se a ANEEL mudar os valores.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        flagAdd.value.forEach { (f, v) ->
                            OutlinedTextField(
                                value = v,
                                onValueChange = { nv -> flagAdd.value = flagAdd.value + (f to nv) },
                                singleLine = true,
                                label = { Text(f.label) },
                                keyboardOptions = decimal,
                            )
                        }
                    }
                }

                HorizontalDivider()
                HomeSection(prefs, home, onHome = onHome, onRenew = onRenew, onLeave = onLeave, onOpenHome = onOpenHome)
                HorizontalDivider()
                Text("Notificações", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Carro começou a carregar", Modifier.weight(1f))
                    Switch(checked = notifyStart, onCheckedChange = { notifyStart = it; prefs.notifyChargeStart = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recarga concluída (com resumo)", Modifier.weight(1f))
                    Switch(checked = notifyEnd, onCheckedChange = { notifyEnd = it; prefs.notifyChargeEnd = it })
                }
                Text(
                    "Chegam em até ~15 min, mesmo com o app fechado. O aviso de renovação do plano da Tuya é sempre enviado para quem renova.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Ocultar avançado" else "Avançado")
                }
                if (advanced) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true,
                        label = { Text("URL do servidor") },
                        placeholder = { Text("https://consumo-tuya.xxx.workers.dev") },
                    )
                }
                Text(
                    "Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.url = url.trim()
                parseNum(tariff)?.let { prefs.tariff = it }
                prefs.detailed = detailed
                prefs.te = parseNum(te) ?: 0.0
                prefs.tu = parseNum(tu) ?: 0.0
                prefs.icmsPct = parseNum(icms) ?: 0.0
                prefs.pisPct = parseNum(pis) ?: 0.0
                prefs.cofinsPct = parseNum(cofins) ?: 0.0
                prefs.energyFull = parseNum(energyFull) ?: 0.0
                prefs.energyCharged = parseNum(energyCharged) ?: 0.0
                flagAdd.value.forEach { (f, v) -> parseNum(v)?.let { prefs.setFlagAdd(f, it) } }
                onDone()
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } },
    )
}
