package com.consumo.tuya

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.TextStyle

/**
 * Data de referência ao abrir o app: o ano escolhido por último fica salvo; o mês não.
 * Ano atual → hoje; ano passado → último dia daquele ano (último mês).
 */
fun initialAnchor(prefs: Prefs): LocalDate {
    val today = LocalDate.now()
    val y = prefs.selYear
    return if (y == 0 || y >= today.year) today else LocalDate.of(y, 12, 31)
}

/** Data de referência para um mês/ano escolhido (nunca no futuro). */
fun anchorFor(ym: YearMonth): LocalDate {
    val today = LocalDate.now()
    return if (ym >= YearMonth.from(today)) today else ym.atEndOfMonth()
}

/** Avança/volta um período (dia, semana, mês ou ano), sem passar de hoje. */
fun shiftAnchor(range: Range, anchor: LocalDate, dir: Int): LocalDate {
    val k = dir.toLong()
    val next = when (range) {
        Range.DAY, Range.POWER -> anchor.plusDays(k)
        Range.WEEK -> anchor.plusWeeks(k)
        Range.MONTH -> YearMonth.from(anchor).plusMonths(k).let(::anchorFor)
        Range.YEAR -> YearMonth.of(anchor.year + dir, 12).let(::anchorFor)
    }
    val today = LocalDate.now()
    return if (next > today) today else next
}

/** Existe período seguinte (não futuro)? */
fun canGoForward(range: Range, anchor: LocalDate): Boolean {
    val today = LocalDate.now()
    return when (range) {
        Range.DAY, Range.POWER, Range.WEEK -> anchor < today
        Range.MONTH -> YearMonth.from(anchor) < YearMonth.from(today)
        Range.YEAR -> anchor.year < today.year
    }
}

/** Relança o cancelamento (troca de período) em vez de tratá-lo como erro. */
fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) throw this
}

private fun monthShort(m: Month) = m.getDisplayName(TextStyle.SHORT, PT).replace(".", "").replaceFirstChar { it.uppercase() }

/** Título clicável com setas: "‹  Novembro 2026 ▾  ›". */
@Composable
fun PeriodNav(title: String, canForward: Boolean, onPrev: () -> Unit, onNext: () -> Unit, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrev) { Text("‹", fontSize = 22.sp) }
        Text(
            "${title.replaceFirstChar { it.uppercase() }} ▾",
            Modifier
                .weight(1f)
                .clickable(onClick = onPick),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onNext, enabled = canForward) { Text("›", fontSize = 22.sp) }
    }
}

/** Seletor de mês e ano (ou só ano). Meses futuros ficam desativados. */
@Composable
fun MonthYearPicker(initial: YearMonth, showMonths: Boolean, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    val now = YearMonth.now()
    var year by remember { mutableIntStateOf(initial.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (showMonths) "Escolha o mês" else "Escolha o ano") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { year-- }) { Text("‹", fontSize = 22.sp) }
                    Text("$year", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { year++ }, enabled = year < now.year) { Text("›", fontSize = 22.sp) }
                }
                if (showMonths) {
                    Month.entries.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { m ->
                                val ym = YearMonth.of(year, m)
                                FilterChip(
                                    selected = ym == initial,
                                    enabled = ym <= now,
                                    onClick = { onPick(ym) },
                                    label = { Text(monthShort(m)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!showMonths) TextButton(onClick = { onPick(YearMonth.of(year, 12)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Calendário para escolher um dia (até hoje). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayMs = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMs
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = state.selectedDateMillis != null, onClick = {
                onPick(Instant.ofEpochMilli(state.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate())
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    ) { DatePicker(state = state) }
}

/** Mensagem padrão quando o período não tem dados. */
@Composable
fun EmptyPeriod() {
    Text(
        "Não há nada aqui para ver",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

