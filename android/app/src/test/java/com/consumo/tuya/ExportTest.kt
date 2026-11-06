package com.consumo.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ExportTest {
    private val buckets = listOf(
        Bucket(1793934000000, 12.34, false),
        Bucket(1794020400000, 8.5, true),
        Bucket(1794106800000, null, false),
    )
    private val table = energyTable("teste", "Novembro 2026", "day", buckets, CostModel.simple(0.9))
    private val outDir = File(System.getProperty("exportDir") ?: "build/export-test").apply { mkdirs() }

    @Test
    fun csvUsaPadraoBrasileiro() {
        val text = Exporter.writeTo(outDir, table, ExportFormat.CSV).readText()
        assertTrue("BOM UTF-8", text.startsWith("﻿"))
        val lines = text.removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals("Período;Início;Consumo (kWh);Custo (R$);Estimado", lines[0])
        assertTrue(lines[1].endsWith(";12,34;11,11;Não"))
        assertTrue(lines[2].endsWith(";8,50;7,65;Sim"))
        assertTrue("dia sem dados fica vazio", lines[3].endsWith(";;;"))
        assertEquals("Total;;20,84;18,76;", lines[4])
    }

    @Test
    fun tarifaDetalhadaBateComContaCeee() {
        // Conta CEEE set/2026: Consumo TE tarifa sem tributos 0,343370 → com tributos 0,425474
        // (ICMS 17%, PIS 0,4885%, COFINS 2,2785%)
        val te = CostModel(detailed = true, simpleTariff = 0.0, base = 0.343370, icmsPct = 17.0, pisCofinsPct = 0.4885 + 2.2785)
        assertEquals(0.425474, te.pricePerKwh(1793934000000), 1e-5) // a fatura arredonda na 6ª casa

        // TE + TU (0,343370 + 0,478630) + bandeira amarela, sem desconto ≈ "Tarifa CEEE" da FIT (1,04188814)
        val set = monthKey(1790305200000) // 25/09/2026
        val full = te.copy(base = 0.822, flags = mapOf(set to Flag.AMARELA))
        assertEquals(1.04188814, full.pricePerKwh(1790305200000), 1e-4)

        // Com desconto de 12% da FIT ≈ 461,18 ÷ 503 = 0,91686
        val fit = full.copy(discountPct = 12.0)
        assertEquals(0.91686, fit.pricePerKwh(1790305200000), 1e-4)
        val b = fit.breakdown(503.0, 1790305200000)
        assertEquals(461.18, b.total, 0.05)
        assertTrue(b.discount < 0)

        // mês sem bandeira cadastrada = verde
        assertEquals(0.822 / (0.83 * (1 - 0.02767)), full.pricePerKwh(1793934000000), 1e-9)
        // modo simples ignora tudo e usa a tarifa única
        assertEquals(9.0, CostModel.simple(0.9).cost(10.0, 1793934000000)!!, 1e-9)
    }

    @Test
    fun xlsxTemEstruturaValida() {
        val file = Exporter.writeTo(outDir, table, ExportFormat.XLSX)
        ZipFile(file).use { z ->
            listOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml",
            ).forEach { assertTrue("falta $it", z.getEntry(it) != null) }
            val sheet = z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
            assertTrue(sheet.contains("""<c r="C2" s="2"><v>12.34</v></c>"""))
            assertTrue(sheet.contains("Consumo (kWh)"))
        }
    }
}
