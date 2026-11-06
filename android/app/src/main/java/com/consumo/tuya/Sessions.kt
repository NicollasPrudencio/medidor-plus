package com.consumo.tuya

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", PT)

private fun localDateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM)

/** "2h05" / "37 min" */
fun fmtDuration(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min < 60) "$min min" else String.format(PT, "%dh%02d", min / 60, min % 60)
}

/** Resumo de um dia: quantas recargas, tempo total, consumo e custo. */
private data class DaySessions(val date: LocalDate, val sessions: List<Session>, val cost: CostModel) {
    val count get() = sessions.size
    val kwh get() = sessions.sumOf { it.kwh }
    val brl get() = sessions.sumOf { cost.cost(it.kwh, it.start) ?: 0.0 }
    val durationMs: Long? get() = sessions.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()
    val estimated get() = sessions.any { it.estimated }
}

@Composable
fun SessionsCard(prefs: Prefs, cost: CostModel, reload: Int, anchor: LocalDate, onAnchor: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var measure by rememberSaveable { mutableStateOf(Measure.KWH) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val month = YearMonth.from(anchor)
    var openDay by remember(month) { mutableStateOf<LocalDate?>(null) }
    var selected by remember(month, measure) { mutableStateOf<Int?>(null) }
    val startMs = startOfDayMs(month.atDay(1))
    val endMs = startOfDayMs(month.plusMonths(1).atDay(1))

    LaunchedEffect(month, reload) {
        loading = true
        error = null
        try {
            sessions = prefs.api().sessions(startMs, endMs)
        } catch (e: Exception) {
            e.rethrowIfCancelled() // troca de mês: não é erro
            error = e.message ?: e.toString()
        }
        loading = false
    }

    val days = sessions.groupBy { localDateOf(it.start) }
        .map { (d, list) -> DaySessions(d, list, cost) }
        .sortedByDescending { it.date }
    val value: (Double, Long) -> Double? = { k, at -> if (measure == Measure.BRL) cost.cost(k, at) else k }
    val fmt: (Double?) -> String = { v -> if (measure == Measure.BRL) brl(v) else kwh(v) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recargas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            PeriodNav(
                title = month.format(MONTH_TITLE),
                canForward = canGoForward(Range.MONTH, anchor),
                onPrev = { onAnchor(shiftAnchor(Range.MONTH, anchor, -1)) },
                onNext = { onAnchor(shiftAnchor(Range.MONTH, anchor, 1)) },
                onPick = { picking = true },
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Measure.entries.forEach { m ->
                    FilterChip(selected = measure == m, onClick = { measure = m }, label = { Text(m.label) })
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loading) Text("Carregando...", style = MaterialTheme.typography.bodySmall)

            if (!loading && sessions.isEmpty() && error == null) EmptyPeriod()

            if (sessions.isNotEmpty()) {
                // Resumo do mês
                val total = sessions.sumOf { value(it.kwh, it.start) ?: 0.0 }
                val time = sessions.mapNotNull { it.durationMs }.sum()
                Text(
                    "${sessions.size} recargas em ${days.size} dias · ${fmt(total)}",
                    fontWeight = FontWeight.Bold,
                )
                if (time > 0) {
                    Text("Tempo carregando: ${fmtDuration(time)}", style = MaterialTheme.typography.bodySmall)
                }

                // Gráfico: uma barra por dia do mês
                val n = month.lengthOfMonth()
                val byDay = days.associateBy { it.date.dayOfMonth }
                val ys = List(n) { i ->
                    byDay[i + 1]?.let { d -> if (measure == Measure.BRL) d.brl else d.kwh }
                }
                val sel = selected?.takeIf { ys.getOrNull(it) != null }
                sel?.let { i ->
                    val d = byDay[i + 1]!!
                    Text(
                        "${d.date.format(DAY_FMT)}: ${d.count} recarga(s) · ${fmt(ys[i])}" +
                            (d.durationMs?.let { " · ${fmtDuration(it)}" } ?: ""),
                        fontWeight = FontWeight.Bold,
                    )
                }
                ChartCanvas(
                    xs = List(n) { (it + 0.5f) / n },
                    ys = ys,
                    estimated = List(n) { byDay[it + 1]?.estimated == true },
                    style = ChartStyle.BARS,
                    barWidth = 0.7f / n,
                    selected = sel,
                    onSelect = { i ->
                        selected = i
                        openDay = i?.let { month.atDay(it + 1) }
                    },
                )
                Row(Modifier.fillMaxWidth()) {
                    val small = MaterialTheme.typography.bodySmall
                    Text("01", style = small, modifier = Modifier.weight(1f))
                    Text("${n / 2}", style = small, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("$n", style = small, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Toque num dia para ver as recargas", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    ExportMenu { f ->
                        val name = "medidor-plus_recargas_$month"
                        val title = "Recargas — ${month.format(MONTH_TITLE)}"
                        runCatching { Exporter.share(ctx, sessionsTable(name, title, sessions, cost), f) }
                            .onFailure { error = it.message }
                    }
                }

                // Lista por dia (toque abre as recargas do dia)
                days.forEach { d ->
                    HorizontalDivider()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openDay = if (openDay == d.date) null else d.date }
                            .padding(vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.date.format(DAY_FMT), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            if (d.estimated) EstimateIcon()
                            Text(fmt(if (measure == Measure.BRL) d.brl else d.kwh), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "${d.count} ${if (d.count == 1) "recarga" else "recargas"}" +
                                (d.durationMs?.let { " · ${fmtDuration(it)}" } ?: "") +
                                " · ${if (measure == Measure.BRL) kwh(d.kwh) else brl(d.brl)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (openDay == d.date) {
                        d.sessions.sortedBy { it.start }.forEach { s -> SessionRow(prefs, s, cost, measure) }
                    }
                }
            }
        }
    }

    if (picking) {
        MonthYearPicker(
            initial = month,
            showMonths = true,
            onPick = { picking = false; onAnchor(anchorFor(it)) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun SessionRow(prefs: Prefs, s: Session, cost: CostModel, measure: Measure) {
    var showCurve by remember(s.start) { mutableStateOf(false) }
    var curve by remember(s.start) { mutableStateOf<List<PowerPoint>?>(null) }
    val main = if (measure == Measure.BRL) brl(cost.cost(s.kwh, s.start)) else kwh(s.kwh)
    val other = if (measure == Measure.BRL) kwh(s.kwh) else brl(cost.cost(s.kwh, s.start))

    LaunchedEffect(showCurve) {
        if (showCurve && curve == null && s.end != null) {
            curve = runCatching { prefs.api().power(s.start - 5 * 60_000, s.end + 5 * 60_000) }.getOrDefault(emptyList())
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            .clickable(enabled = !s.manual) { showCurve = !showCurve }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val time = when {
                s.manual -> "Horário não registrado"
                s.ongoing -> "${hm(s.start)} – agora"
                else -> "${hm(s.start)} – ${hm(s.end!!)}"
            }
            Text(time, Modifier.weight(1f))
            if (s.ongoing) LiveBadge()
            if (s.estimated) EstimateIcon()
            Spacer(Modifier.width(4.dp))
            Text(main, fontWeight = FontWeight.Bold)
        }
        val details = buildList {
            s.durationMs?.let { add(fmtDuration(it)) }
            add(other)
            s.avgW?.let { add(String.format(PT, "média %.1f kW", it / 1000)) }
            s.peakW?.let { add(String.format(PT, "pico %.1f kW", it / 1000)) }
            if (s.manual) add("anotação manual")
        }
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        if (!s.manual) {
            Text(
                if (showCurve) "Ocultar curva de potência" else "Ver curva de potência",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showCurve) {
            val pts = curve
            when {
                pts == null -> Text("Carregando...", style = MaterialTheme.typography.bodySmall)
                pts.isEmpty() -> Text("Sem leituras de potência.", style = MaterialTheme.typography.bodySmall)
                else -> {
                    val t0 = pts.first().ts
                    val span = (pts.last().ts - t0).coerceAtLeast(1).toFloat()
                    ChartCanvas(
                        xs = pts.map { (it.ts - t0) / span },
                        ys = pts.map { it.w },
                        estimated = List(pts.size) { false },
                        style = ChartStyle.AREA,
                        barWidth = 0f,
                        selected = null,
                        onSelect = {},
                    )
                    Row(Modifier.fillMaxWidth()) {
                        val small = MaterialTheme.typography.bodySmall
                        Text(hm(pts.first().ts), style = small, modifier = Modifier.weight(1f))
                        Text(hm(pts.last().ts), style = small, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}
