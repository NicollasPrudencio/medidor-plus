package com.consumo.tuya

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ColFmt { TEXT, KWH, BRL, WATT }

data class Col(val name: String, val fmt: ColFmt)

/** Tabela a exportar. Células: String, Number ou null. */
data class ExportTable(val fileName: String, val title: String, val cols: List<Col>, val rows: List<List<Any?>>)

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    CSV("CSV", "csv", "text/csv"),
    XLSX("Excel (.xlsx)", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private val FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

fun fmtDateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DATE_TIME_FMT)
fun fileDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(FILE_DATE_FMT)

object Exporter {

    /** Gera o arquivo e abre o menu de compartilhar do Android (Drive, Arquivos, WhatsApp, e-mail...). */
    fun share(ctx: Context, t: ExportTable, f: ExportFormat) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = writeTo(dir, t, f)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(f.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Medidor+ — ${t.title}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        ctx.startActivity(Intent.createChooser(send, "Exportar ${f.label}"))
    }

    /** Grava o arquivo em [dir] (sem dependência do Android — usado também nos testes). */
    fun writeTo(dir: File, t: ExportTable, f: ExportFormat): File {
        val file = File(dir, "${t.fileName}.${f.ext}")
        when (f) {
            ExportFormat.CSV -> file.writeText(csv(t), Charsets.UTF_8)
            ExportFormat.XLSX -> file.outputStream().use { xlsx(t, it) }
        }
        return file
    }

    // ---------- CSV (padrão brasileiro: ";" e vírgula decimal, UTF-8 com BOM para o Excel) ----------

    private fun csv(t: ExportTable): String = buildString {
        append('﻿')
        append(t.cols.joinToString(";") { quote(it.name) }).append("\r\n")
        t.rows.forEach { r ->
            append(r.mapIndexed { i, v -> csvCell(v, t.cols[i].fmt) }.joinToString(";")).append("\r\n")
        }
    }

    private fun csvCell(v: Any?, fmt: ColFmt): String = when (v) {
        null -> ""
        is Number -> String.format(
            PT,
            when (fmt) {
                ColFmt.WATT -> "%.1f"
                else -> "%.2f"
            },
            v.toDouble(),
        )
        else -> quote(v.toString())
    }

    private fun quote(s: String) =
        if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---------- XLSX (Office Open XML mínimo, sem bibliotecas) ----------

    private fun xlsx(t: ExportTable, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", WORKBOOK)
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheet(t))
        }
    }

    // índices de estilo em STYLES/cellXfs
    private fun styleOf(fmt: ColFmt) = when (fmt) {
        ColFmt.TEXT -> 0
        ColFmt.KWH -> 2
        ColFmt.BRL -> 3
        ColFmt.WATT -> 4
    }

    private fun sheet(t: ExportTable): String = buildString {
        append(XML_HEAD)
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        // congela a linha de cabeçalho
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<cols>")
        t.cols.forEachIndexed { i, c ->
            val width = if (c.fmt == ColFmt.TEXT) 24 else 16
            append("""<col min="${i + 1}" max="${i + 1}" width="$width" customWidth="1"/>""")
        }
        append("</cols><sheetData>")
        append("""<row r="1">""")
        t.cols.forEachIndexed { i, c -> append(textCell(ref(i, 1), c.name, style = 1)) }
        append("</row>")
        t.rows.forEachIndexed { ri, r ->
            val rowNum = ri + 2
            append("""<row r="$rowNum">""")
            r.forEachIndexed { i, v ->
                when (v) {
                    null -> Unit
                    is Number -> append("""<c r="${ref(i, rowNum)}" s="${styleOf(t.cols[i].fmt)}"><v>${v.toDouble()}</v></c>""")
                    else -> append(textCell(ref(i, rowNum), v.toString(), style = 0))
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun ref(col: Int, row: Int) = "${'A' + col}$row"

    private fun textCell(ref: String, s: String, style: Int) =
        """<c r="$ref" t="inlineStr" s="$style"><is><t xml:space="preserve">${xmlEsc(s)}</t></is></c>"""

    private fun xmlEsc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val XML_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private const val CONTENT_TYPES = XML_HEAD +
        """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
        """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
        """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
        """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
        """</Types>"""

    private const val ROOT_RELS = XML_HEAD +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
        """</Relationships>"""

    private const val WORKBOOK = XML_HEAD +
        """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
        """<sheets><sheet name="Medidor+" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private const val WORKBOOK_RELS = XML_HEAD +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
        """</Relationships>"""

    // cellXfs: 0 normal · 1 negrito (cabeçalho) · 2 kWh "0.00" · 3 R$ · 4 W "0"
    private const val STYLES = XML_HEAD +
        """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
        """<numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;R$&quot; #,##0.00"/></numFmts>""" +
        """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
        """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="5">""" +
        """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
        """<xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="1" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """</cellXfs>""" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        """</styleSheet>"""
}

/** Tabela de consumo (uma linha por hora/dia/mês) com linha de total. */
fun energyTable(fileName: String, title: String, step: String, buckets: List<Bucket>, cost: CostModel): ExportTable {
    val rows = buckets.map { b ->
        listOf(
            fullLabel(step, b.start),
            fmtDateTime(b.start),
            b.kwh,
            cost.cost(b.kwh, b.start),
            if (b.kwh == null) null else if (b.estimated) "Sim" else "Não",
        ) + (if (cost.detailed) listOf(cost.flagAt(b.start).label) else emptyList())
    }
    val total = buckets.sumOf { it.kwh ?: 0.0 }
    val totalCost = buckets.sumOf { cost.cost(it.kwh, it.start) ?: 0.0 }
    val cols = listOf(
        Col("Período", ColFmt.TEXT),
        Col("Início", ColFmt.TEXT),
        Col("Consumo (kWh)", ColFmt.KWH),
        Col("Custo (R$)", ColFmt.BRL),
        Col("Estimado", ColFmt.TEXT),
    ) + (if (cost.detailed) listOf(Col("Bandeira", ColFmt.TEXT)) else emptyList())
    val totalRow = listOf("Total", null, total, totalCost, null) + (if (cost.detailed) listOf(null) else emptyList())
    return ExportTable(fileName = fileName, title = title, cols = cols, rows = rows + listOf(totalRow))
}

/** Uma linha por recarga, com linha de total. */
fun sessionsTable(fileName: String, title: String, sessions: List<Session>, cost: CostModel): ExportTable {
    val rows = sessions.map { s ->
        listOf(
            fullLabel("day", s.start),
            if (s.manual) "sem horário" else fmtTime(s.start),
            s.end?.let { fmtTime(it) },
            s.durationMs?.let { it / 60_000.0 },
            s.kwh,
            cost.cost(s.kwh, s.start),
            s.avgW,
            s.peakW,
            when {
                s.manual -> "Anotação manual"
                s.ongoing -> "Em andamento"
                s.estimated -> "Estimado"
                else -> "Medido"
            },
        )
    }
    val totalMin = sessions.sumOf { (it.durationMs ?: 0L) / 60_000.0 }
    val totalKwh = sessions.sumOf { it.kwh }
    val totalCost = sessions.sumOf { cost.cost(it.kwh, it.start) ?: 0.0 }
    return ExportTable(
        fileName = fileName,
        title = title,
        cols = listOf(
            Col("Dia", ColFmt.TEXT),
            Col("Início", ColFmt.TEXT),
            Col("Fim", ColFmt.TEXT),
            Col("Duração (min)", ColFmt.WATT),
            Col("Consumo (kWh)", ColFmt.KWH),
            Col("Custo (R$)", ColFmt.BRL),
            Col("Potência média (W)", ColFmt.WATT),
            Col("Pico (W)", ColFmt.WATT),
            Col("Origem", ColFmt.TEXT),
        ),
        rows = rows + listOf(listOf("Total (${sessions.size} recargas)", null, null, totalMin, totalKwh, totalCost, null, null, null)),
    )
}

private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm")
private fun fmtTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_ONLY)

/** Menu "Exportar" → CSV / Excel. */
@Composable
fun ExportMenu(label: String = "Exportar", enabled: Boolean = true, onExport: (ExportFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExportFormat.entries.forEach { f ->
                DropdownMenuItem(text = { Text(f.label) }, onClick = {
                    open = false
                    onExport(f)
                })
            }
        }
    }
}
