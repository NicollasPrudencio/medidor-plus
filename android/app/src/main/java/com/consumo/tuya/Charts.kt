package com.consumo.tuya

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs

enum class Range(val label: String) { DAY("Dia"), WEEK("Semana"), MONTH("Mês"), YEAR("Ano"), POWER("Potência") }

enum class ChartType(val label: String) { BARS("Barras"), LINE("Linha"), CUMULATIVE("Acumulado") }

enum class Measure(val label: String) { KWH("kWh"), BRL("R$") }

enum class ChartStyle { BARS, LINE, AREA }

/** Janela de tempo exibida: [startMs, endMs) agrupada por [step]. */
data class Window(val startMs: Long, val endMs: Long, val step: String, val title: String)

private val SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM")
private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", PT)

fun window(range: Range, offset: Int): Window {
    val today = LocalDate.now()
    val k = offset.toLong()
    return when (range) {
        Range.DAY, Range.POWER -> {
            val d = today.plusDays(k)
            Window(startOfDayMs(d), startOfDayMs(d.plusDays(1)), "hour", d.format(DAY_FMT))
        }
        Range.WEEK -> {
            val e = today.plusWeeks(k)
            val s = e.minusDays(6)
            Window(startOfDayMs(s), startOfDayMs(e.plusDays(1)), "day", "${s.format(SHORT_DATE)} – ${e.format(SHORT_DATE)}")
        }
        Range.MONTH -> {
            val m = YearMonth.now().plusMonths(k)
            Window(startOfDayMs(m.atDay(1)), startOfDayMs(m.plusMonths(1).atDay(1)), "day", m.format(MONTH_TITLE))
        }
        Range.YEAR -> {
            val y = today.year + offset
            Window(startOfDayMs(LocalDate.of(y, 1, 1)), startOfDayMs(LocalDate.of(y + 1, 1, 1)), "month", "$y")
        }
    }
}

private fun zoned(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

/** Rótulo curto (eixo X). */
fun axisLabel(step: String, ms: Long): String = zoned(ms).format(
    DateTimeFormatter.ofPattern(
        when (step) {
            "hour" -> "HH'h'"
            "month" -> "MMM"
            else -> "dd/MM"
        },
        PT,
    )
)

/** Rótulo completo (seleção e lista). */
fun fullLabel(step: String, ms: Long): String = zoned(ms).format(
    DateTimeFormatter.ofPattern(
        when (step) {
            "hour" -> "EEE dd/MM HH'h'"
            "month" -> "MMMM yyyy"
            else -> "EEE dd/MM"
        },
        PT,
    )
)

private fun stepName(step: String) = when (step) {
    "hour" -> "hora"
    "month" -> "mês"
    else -> "dia"
}

@Composable
fun ChartCard(prefs: Prefs, tariff: Double, reload: Int) {
    var range by rememberSaveable { mutableStateOf(Range.MONTH) }
    var type by rememberSaveable { mutableStateOf(ChartType.BARS) }
    var measure by rememberSaveable { mutableStateOf(Measure.KWH) }
    var offset by remember(range) { mutableIntStateOf(0) }
    var buckets by remember { mutableStateOf<List<Bucket>>(emptyList()) }
    var power by remember { mutableStateOf<List<PowerPoint>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var selected by remember(range, offset, type, measure) { mutableStateOf<Int?>(null) }
    val w = window(range, offset)
    val ctx = LocalContext.current

    LaunchedEffect(range, offset, reload) {
        loading = true
        error = null
        buckets = emptyList()
        power = emptyList()
        try {
            if (range == Range.POWER) power = prefs.api().power(w.startMs, w.endMs)
            else buckets = prefs.api().series(w.startMs, w.endMs, w.step)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        loading = false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Período
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Range.entries.forEach { r ->
                    FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
                }
            }
            // Navegação
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset-- }) { Text("‹", fontSize = 22.sp) }
                Text(
                    w.title.replaceFirstChar { it.uppercase() },
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { offset++ }, enabled = offset < 0) { Text("›", fontSize = 22.sp) }
            }
            // Tipo e unidade
            if (range != Range.POWER) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                    Spacer(Modifier.width(8.dp))
                    Measure.entries.forEach { m ->
                        FilterChip(selected = measure == m, onClick = { measure = m }, label = { Text(m.label) })
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loading) Text("Carregando...", style = MaterialTheme.typography.bodySmall)

            val fileName = "medidor-plus_${range.name.lowercase()}_${fileDate(w.startMs)}"
            if (range == Range.POWER) {
                PowerChart(power, w, selected) { selected = it }
                if (power.isNotEmpty()) {
                    ExportMenu { f ->
                        val t = ExportTable(
                            fileName = fileName,
                            title = "Potência — ${w.title}",
                            cols = listOf(Col("Data/hora", ColFmt.TEXT), Col("Potência (W)", ColFmt.WATT)),
                            rows = power.map { listOf(fmtDateTime(it.ts), it.w) },
                        )
                        runCatching { Exporter.share(ctx, t, f) }.onFailure { error = it.message }
                    }
                }
            } else if (buckets.isNotEmpty()) {
                EnergyChart(buckets, w, type, measure, tariff, selected) { selected = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showList = !showList }) {
                        Text(if (showList) "Ocultar lista" else "Mostrar lista")
                    }
                    Spacer(Modifier.weight(1f))
                    ExportMenu { f ->
                        val t = energyTable(fileName, w.title, w.step, buckets, tariff)
                        runCatching { Exporter.share(ctx, t, f) }.onFailure { error = it.message }
                    }
                }
                if (showList) BucketList(buckets, w, tariff)
            }
        }
    }
}

@Composable
private fun EnergyChart(
    buckets: List<Bucket>,
    w: Window,
    type: ChartType,
    measure: Measure,
    tariff: Double,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val factor = if (measure == Measure.BRL) tariff else 1.0
    val raw = buckets.map { it.kwh?.times(factor) }
    val values = if (type == ChartType.CUMULATIVE) {
        var acc = 0.0
        raw.map { v -> v?.let { acc += it; acc } }
    } else raw
    val fmt: (Double?) -> String = { v -> if (measure == Measure.BRL) money(v, 1.0) else kwh(v) }

    val n = buckets.size
    val xs = List(n) { (it + 0.5f) / n }
    val style = when (type) {
        ChartType.BARS -> ChartStyle.BARS
        ChartType.LINE -> ChartStyle.LINE
        ChartType.CUMULATIVE -> ChartStyle.AREA
    }

    // Cabeçalho: valor selecionado ou resumo
    val sel = selected?.takeIf { values.getOrNull(it) != null }
    if (sel != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fullLabel(w.step, buckets[sel].start) + ": ", style = MaterialTheme.typography.bodyMedium)
            Text(fmt(values[sel]), fontWeight = FontWeight.Bold)
            if (buckets[sel].estimated) EstimateIcon()
        }
    } else {
        val known = raw.filterNotNull()
        val total = known.sum()
        val peakIdx = raw.indices.filter { raw[it] != null }.maxByOrNull { raw[it]!! }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total ${fmt(total)}", fontWeight = FontWeight.Bold)
            if (buckets.any { it.estimated && it.kwh != null }) EstimateIcon()
        }
        if (known.isNotEmpty()) {
            Text(
                "Média ${fmt(total / known.size)}/${stepName(w.step)}" +
                    (peakIdx?.let { " · Pico ${fmt(raw[it])} (${axisLabel(w.step, buckets[it].start)})" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    ChartCanvas(xs, values, buckets.map { it.estimated }, style, barWidth = 0.7f / n, selected = sel, onSelect = onSelect)

    Row(Modifier.fillMaxWidth()) {
        val small = MaterialTheme.typography.bodySmall
        Text(axisLabel(w.step, buckets.first().start), style = small, modifier = Modifier.weight(1f))
        Text(axisLabel(w.step, buckets[n / 2].start), style = small, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(axisLabel(w.step, buckets.last().start), style = small, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    Text("Toque no gráfico para ver cada valor", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun PowerChart(points: List<PowerPoint>, w: Window, selected: Int?, onSelect: (Int?) -> Unit) {
    if (points.isEmpty()) {
        Text("Sem leituras de potência neste dia.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val span = (w.endMs - w.startMs).toFloat()
    val xs = points.map { (it.ts - w.startMs) / span }
    val ys = points.map { it.w }
    val sel = selected?.takeIf { it in points.indices }

    if (sel != null) {
        Text(
            "${zoned(points[sel].ts).format(DateTimeFormatter.ofPattern("HH:mm"))}: " +
                String.format(PT, "%.0f W", points[sel].w),
            fontWeight = FontWeight.Bold,
        )
    } else {
        val peak = points.maxBy { it.w }
        Text(
            String.format(PT, "Pico %.0f W às %s · Média %.0f W", peak.w,
                zoned(peak.ts).format(DateTimeFormatter.ofPattern("HH:mm")), ys.average()),
            fontWeight = FontWeight.Bold,
        )
    }

    ChartCanvas(xs, ys, List(ys.size) { false }, ChartStyle.LINE, barWidth = 0f, selected = sel, onSelect = onSelect)

    Row(Modifier.fillMaxWidth()) {
        val small = MaterialTheme.typography.bodySmall
        Text("00h", style = small, modifier = Modifier.weight(1f))
        Text("12h", style = small, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text("24h", style = small, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    Text("Potência instantânea registrada a cada coleta (~10 min)", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun BucketList(buckets: List<Bucket>, w: Window, tariff: Double) {
    Column {
        buckets.asReversed().filter { it.kwh != null }.forEach { b ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fullLabel(w.step, b.start), Modifier.weight(1f))
                if (b.estimated) EstimateIcon()
                Text(kwh(b.kwh))
                Text(money(b.kwh, tariff), Modifier.width(96.dp), textAlign = TextAlign.End)
            }
        }
    }
}

/** Gráfico genérico. xs em 0..1 (posição horizontal), ys podem ser null (sem dados). */
@Composable
fun ChartCanvas(
    xs: List<Float>,
    ys: List<Double?>,
    estimated: List<Boolean>,
    style: ChartStyle,
    barWidth: Float,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.tertiary
    val max = (ys.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
    val maxLabel = ys.filterNotNull().maxOrNull()

    Column {
        if (maxLabel != null) {
            Text(String.format(PT, "%.2f", maxLabel), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(xs, ys) {
                    detectTapGestures { o ->
                        val fx = o.x / size.width
                        val i = ys.indices.filter { ys[it] != null }.minByOrNull { abs(xs[it] - fx) }
                        onSelect(if (i == selected) null else i)
                    }
                }
        ) {
            val h = size.height
            val wd = size.width
            for (k in 0..4) {
                val y = h * k / 4f
                drawLine(grid, Offset(0f, y), Offset(wd, y), strokeWidth = 1f)
            }
            fun py(v: Double) = (h - v / max * h).toFloat()

            if (style == ChartStyle.BARS) {
                ys.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    val bw = barWidth * wd
                    val top = py(v)
                    val color = when {
                        i == selected -> highlight
                        estimated[i] -> primary.copy(alpha = 0.4f)
                        else -> primary
                    }
                    drawRect(color, topLeft = Offset(xs[i] * wd - bw / 2, top), size = Size(bw, h - top))
                }
            } else {
                val idx = ys.indices.filter { ys[it] != null }
                if (idx.isNotEmpty()) {
                    val pts = idx.map { Offset(xs[it] * wd, py(ys[it]!!)) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    if (style == ChartStyle.AREA) {
                        val fill = Path().apply {
                            addPath(path)
                            lineTo(pts.last().x, h)
                            lineTo(pts.first().x, h)
                            close()
                        }
                        drawPath(fill, primary.copy(alpha = 0.2f))
                    }
                    drawPath(path, primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (pts.size <= 40) {
                        idx.forEachIndexed { j, i ->
                            drawCircle(if (estimated[i]) primary.copy(alpha = 0.4f) else primary, 3.dp.toPx(), pts[j])
                        }
                    }
                }
                selected?.let { i ->
                    ys.getOrNull(i)?.let { v ->
                        val x = xs[i] * wd
                        drawLine(highlight, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
                        drawCircle(highlight, 5.dp.toPx(), Offset(x, py(v)))
                    }
                }
            }
        }
    }
}
