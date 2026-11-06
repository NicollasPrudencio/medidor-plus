package com.consumo.tuya

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

val PT: Locale = Locale("pt", "BR")
private val BRL = NumberFormat.getCurrencyInstance(PT)
private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd/MM", PT)
private val TIME_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm")

fun kwh(v: Double?) = if (v == null) "—" else String.format(PT, "%.2f kWh", v)
fun money(v: Double?, tariff: Double) = if (v == null) "" else BRL.format(v * tariff)
private fun fmtTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FMT)
fun startOfDayMs(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun pickerDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { App(prefs) }
            }
        }
    }
}

@Composable
fun App(prefs: Prefs) {
    var showSettings by remember { mutableStateOf(!prefs.configured) }
    var tariff by remember { mutableDoubleStateOf(prefs.tariff) }
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf<Now?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        if (!prefs.configured) return@LaunchedEffect
        loading = true
        error = null
        try {
            now = prefs.api().now()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        loading = false
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
                if (prefs.configured && !loading) {
                    runCatching { prefs.api().now() }.onSuccess { now = it; error = null }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Medidor+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { reload++ }, enabled = !loading) { Text(if (loading) "..." else "Atualizar") }
            TextButton(onClick = { showSettings = true }) { Text("Config") }
        }

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(16.dp))
            }
        }

        now?.let { NowCard(it, tariff, live) }
        if (prefs.configured) {
            ChartCard(prefs, tariff, reload)
            PeriodCard(prefs, tariff)
        }
    }

    UpdateGate(prefs, reload)

    if (showSettings) {
        SettingsDialog(prefs) {
            showSettings = false
            tariff = prefs.tariff
            reload++
        }
    }
}

@Composable
fun NowCard(n: Now, tariff: Double, live: Boolean) {
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
                Stat("Hoje", n.todayKwh, n.todayEstimated, tariff, Modifier.weight(1f))
                Stat("Ontem", n.yesterdayKwh, n.yesterdayEstimated, tariff, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Contador do medidor: ${kwh(n.totalKwh)}", style = MaterialTheme.typography.bodySmall)
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
fun Stat(label: String, v: Double?, estimated: Boolean, tariff: Double, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kwh(v), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (estimated && v != null) EstimateIcon()
        }
        Text(money(v, tariff), style = MaterialTheme.typography.bodySmall)
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
                        "(coleta falhou, medidor offline ou sem dados ainda). " +
                        "O consumo total entre as leituras existentes é exato, mas a parte que cai " +
                        "dentro do período foi estimada por interpolação linear."
                )
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Entendi") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodCard(prefs: Prefs, tariff: Double) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var result by remember { mutableStateOf<PeriodKwh?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Período personalizado", style = MaterialTheme.typography.labelLarge)
            range?.let { (s, e) -> Text("${s.format(DATE_FMT)} a ${e.format(DATE_FMT)}") }
            result?.kwh?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(kwh(it), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (result?.estimated == true) EstimateIcon()
                }
                Text(money(it, tariff))
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { open = true }) { Text("Escolher período") }
                Spacer(Modifier.weight(1f))
                // Exporta o consumo dia a dia do período escolhido
                range?.let { (s, e) ->
                    ExportMenu(label = "Exportar dias", enabled = result?.kwh != null) { f ->
                        scope.launch {
                            try {
                                val days = prefs.api().series(startOfDayMs(s), startOfDayMs(e.plusDays(1)), "day")
                                val name = "medidor-plus_periodo_${s}_a_${e}"
                                val title = "${s.format(DATE_FMT)} a ${e.format(DATE_FMT)}"
                                Exporter.share(ctx, energyTable(name, title, "day", days, tariff), f)
                            } catch (ex: Exception) {
                                status = ex.message ?: ex.toString()
                            }
                        }
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
                        result = null
                        status = "Calculando..."
                        scope.launch {
                            try {
                                val r = prefs.api().period(startOfDayMs(s), startOfDayMs(e.plusDays(1)))
                                result = r
                                status = if (r.kwh == null) "Sem leituras nesse período" else null
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
fun SettingsDialog(prefs: Prefs, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(prefs.url) }
    var token by remember { mutableStateOf(prefs.token) }
    var tariff by remember { mutableStateOf(String.format(PT, "%.4f", prefs.tariff)) }
    var advanced by remember { mutableStateOf(!prefs.configured) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Configurações") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = tariff, onValueChange = { tariff = it }, singleLine = true,
                    label = { Text("Tarifa (R$/kWh, com impostos)") },
                    supportingText = { Text("Na conta de luz: valor total ÷ kWh consumidos") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (BuildConfig.DOWNLOAD_URL.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "Instale o app Medidor+: ${BuildConfig.DOWNLOAD_URL}")
                            ctx.startActivity(Intent.createChooser(send, "Compartilhar app"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Compartilhar app") }
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Ocultar avançado" else "Avançado")
                }
                if (advanced) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true,
                        label = { Text("URL do servidor") },
                        placeholder = { Text("https://consumo-tuya.xxx.workers.dev") },
                    )
                    OutlinedTextField(
                        value = token, onValueChange = { token = it }, singleLine = true,
                        label = { Text("Token de acesso") },
                        visualTransformation = PasswordVisualTransformation(),
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
                prefs.token = token.trim()
                tariff.replace(',', '.').toDoubleOrNull()?.let { prefs.tariff = it }
                onDone()
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } },
    )
}
