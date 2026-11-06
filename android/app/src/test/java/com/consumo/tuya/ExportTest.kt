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
    private val table = energyTable("teste", "Novembro 2026", "day", buckets, tariff = 0.9)
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
