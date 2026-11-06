package com.consumo.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class PeriodTest {
    private val today = LocalDate.now()

    @Test
    fun mesAnteriorEProximoNaoPassamDeHoje() {
        val past = shiftAnchor(Range.MONTH, today, -1)
        assertEquals(YearMonth.from(today).minusMonths(1), YearMonth.from(past))
        assertEquals(YearMonth.from(today).minusMonths(1).atEndOfMonth(), past) // mês passado: último dia
        assertEquals(today, shiftAnchor(Range.MONTH, past, 1)) // voltar ao mês atual = hoje
        assertEquals(today, shiftAnchor(Range.MONTH, today, 1)) // não vai para o futuro
        assertFalse(canGoForward(Range.MONTH, today))
        assertTrue(canGoForward(Range.MONTH, past))
    }

    @Test
    fun anoAnteriorAbreNoUltimoMes() {
        val lastYear = shiftAnchor(Range.YEAR, today, -1)
        assertEquals(LocalDate.of(today.year - 1, 12, 31), lastYear)
        assertEquals(today, shiftAnchor(Range.YEAR, lastYear, 1))
        // mês escolhido no seletor
        assertEquals(LocalDate.of(2025, 8, 31), anchorFor(YearMonth.of(2025, 8)))
        assertEquals(today, anchorFor(YearMonth.from(today)))
    }

    @Test
    fun janelaDoMesCobreOMesInteiro() {
        val w = window(Range.MONTH, LocalDate.of(2025, 8, 15))
        assertEquals(startOfDayMs(LocalDate.of(2025, 8, 1)), w.startMs)
        assertEquals(startOfDayMs(LocalDate.of(2025, 9, 1)), w.endMs)
        assertEquals("agosto 2025", w.title)
        val semana = window(Range.WEEK, LocalDate.of(2025, 8, 15))
        assertEquals(startOfDayMs(LocalDate.of(2025, 8, 9)), semana.startMs)
    }
}
