package com.consumo.tuya

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Now(
    val powerW: Double,
    val voltageV: Double,
    val currentA: Double,
    val totalKwh: Double,
    val updatedAt: Long,
    val todayKwh: Double?,
    val todayEstimated: Boolean,
    val yesterdayKwh: Double?,
    val yesterdayEstimated: Boolean,
)

/** Consumo de um intervalo (hora, dia, mês...) que começa em [start] (epoch ms). */
data class Bucket(val start: Long, val kwh: Double?, val estimated: Boolean)

data class PowerPoint(val ts: Long, val w: Double)

/**
 * Uma recarga: período com potência alta (interrupções curtas não separam).
 * [manual] = veio do histórico anotado (sem horário: [end], [avgW] e [peakW] nulos).
 */
data class Session(
    val start: Long,
    val end: Long?,
    val kwh: Double,
    val avgW: Double?,
    val peakW: Double?,
    val estimated: Boolean,
    val ongoing: Boolean,
    val manual: Boolean,
) {
    val durationMs: Long? get() = end?.let { it - start }
}

/** Versão publicada no servidor. Abaixo de [minVersionCode] a atualização é obrigatória. */
data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val size: Long,
    val minVersionCode: Int,
)

/** Cliente do backend (Cloudflare Worker). */
class Api(private val baseUrl: String, private val token: String) {

    suspend fun now(): Now {
        val o = JSONObject(request("/api/now"))
        return Now(
            powerW = o.getDouble("powerW"),
            voltageV = o.getDouble("voltageV"),
            currentA = o.getDouble("currentA"),
            totalKwh = o.getDouble("totalKwh"),
            updatedAt = o.getLong("updatedAt"),
            todayKwh = o.doubleOrNull("todayKwh"),
            todayEstimated = o.optBoolean("todayEstimated"),
            yesterdayKwh = o.doubleOrNull("yesterdayKwh"),
            yesterdayEstimated = o.optBoolean("yesterdayEstimated"),
        )
    }

    /** step = hour | day | week | month */
    suspend fun series(start: Long, end: Long, step: String): List<Bucket> {
        val a = JSONArray(request("/api/series?start=$start&end=$end&step=$step"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Bucket(o.getLong("start"), o.doubleOrNull("kwh"), o.optBoolean("estimated"))
        }
    }

    suspend fun power(start: Long, end: Long): List<PowerPoint> {
        val a = JSONArray(request("/api/power?start=$start&end=$end"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            PowerPoint(o.getLong("ts"), o.getDouble("w"))
        }
    }

    suspend fun sessions(start: Long, end: Long): List<Session> {
        val a = JSONArray(request("/api/sessions?start=$start&end=$end"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Session(
                start = o.getLong("start"),
                end = if (o.isNull("end")) null else o.getLong("end"),
                kwh = o.getDouble("kwh"),
                avgW = o.doubleOrNull("avgW"),
                peakW = o.doubleOrNull("peakW"),
                estimated = o.optBoolean("estimated"),
                ongoing = o.optBoolean("ongoing"),
                manual = o.optBoolean("manual"),
            )
        }
    }

    /** Bandeira de cada mês ("AAAA-MM" → bandeira). */
    suspend fun flags(): Map<String, Flag> = parseFlags(JSONObject(request("/api/bandeiras")))

    /** Define (ou remove, com null) a bandeira de um mês; devolve todas. */
    suspend fun setFlag(month: String, flag: Flag?): Map<String, Flag> =
        parseFlags(JSONObject(request("/api/bandeiras?month=${enc(month)}&cor=${flag?.code ?: ""}", "PUT")))

    private fun parseFlags(o: JSONObject): Map<String, Flag> =
        o.keys().asSequence().mapNotNull { k -> Flag.of(o.optString(k))?.let { k to it } }.toMap()

    suspend fun version(): AppVersion {
        val o = JSONObject(request("/api/version"))
        return AppVersion(
            versionCode = o.optInt("versionCode"),
            versionName = o.optString("versionName"),
            notes = o.optString("notes"),
            size = o.optLong("size"),
            minVersionCode = o.optInt("minVersionCode"),
        )
    }

    /** Baixa o APK publicado para [dest]; [onProgress] recebe 0..1. */
    suspend fun downloadApk(dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val conn = open("/api/apk")
        conn.readTimeout = 120_000
        try {
            if (conn.responseCode != 200) throw Exception("Erro HTTP ${conn.responseCode} ao baixar a atualização")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15_000
        return conn
    }

    private suspend fun request(path: String, method: String = "GET"): String = withContext(Dispatchers.IO) {
        val conn = open(path)
        conn.readTimeout = 30_000
        conn.requestMethod = method
        if (method != "GET") {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.close()
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).getString("error") }.getOrNull()
                throw Exception(msg ?: "Erro HTTP $code")
            }
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)
}

/** Configurações salvas no aparelho. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    // Padrões embutidos no build (publish.properties); o usuário pode sobrescrever em "Avançado".
    var url: String
        get() = sp.getString("url", null) ?: BuildConfig.DEFAULT_URL
        set(v) = sp.edit().putString("url", v.ifBlank { BuildConfig.DEFAULT_URL }).apply()

    var token: String
        get() = sp.getString("token", null) ?: BuildConfig.DEFAULT_TOKEN
        set(v) = sp.edit().putString("token", v.ifBlank { BuildConfig.DEFAULT_TOKEN }).apply()

    /** Tarifa única (R$/kWh, com impostos) — usada no modo simples. */
    var tariff: Double
        get() = sp.getFloat("tariff", 0.90f).toDouble()
        set(v) = sp.edit().putFloat("tariff", v.toFloat()).apply()

    // ---- Tarifa detalhada (opcional) ----
    var detailed: Boolean
        get() = sp.getBoolean("detailed", false)
        set(v) = sp.edit().putBoolean("detailed", v).apply()

    // Valores digitados exatamente como aparecem na fatura; o app faz as contas.
    /** Conta da distribuidora, coluna "Tarifa Unit. (R$)" — linhas Consumo TE e Consumo TU. */
    var te: Double
        get() = double("te", 0.0)
        set(v) = putDouble("te", v)
    var tu: Double
        get() = double("tu", 0.0)
        set(v) = putDouble("tu", v)

    /** Quadro "Tributo", coluna "Alíquota (%)". */
    var icmsPct: Double
        get() = double("icms", 0.0)
        set(v) = putDouble("icms", v)
    var pisPct: Double
        get() = double("pis", 0.0)
        set(v) = putDouble("pis", v)
    var cofinsPct: Double
        get() = double("cofins", 0.0)
        set(v) = putDouble("cofins", v)

    /** Geração compartilhada (ex.: fatura FIT): preço cheio da energia e quanto foi cobrado (R$). */
    var energyFull: Double
        get() = double("energyFull", 0.0)
        set(v) = putDouble("energyFull", v)
    var energyCharged: Double
        get() = double("energyCharged", 0.0)
        set(v) = putDouble("energyCharged", v)

    val base: Double get() = te + tu
    val discountPct: Double get() = discountFrom(energyFull, energyCharged)

    fun flagAdd(f: Flag): Double = double("flag_${f.code}", f.defaultAdd)
    fun setFlagAdd(f: Flag, v: Double) = putDouble("flag_${f.code}", v)

    fun costModel(flags: Map<String, Flag>): CostModel =
        // detalhada sem tarifa base preenchida → continua no modo simples
        if (!detailed || base <= 0.0) CostModel.simple(tariff)
        else CostModel(
            detailed = true,
            simpleTariff = tariff,
            base = base,
            icmsPct = icmsPct,
            pisCofinsPct = pisPct + cofinsPct,
            discountPct = discountPct,
            flagAdd = Flag.entries.associateWith { flagAdd(it) },
            flags = flags,
        )

    private fun double(key: String, def: Double) = sp.getString(key, null)?.toDoubleOrNull() ?: def
    private fun putDouble(key: String, v: Double) = sp.edit().putString(key, v.toString()).apply()

    val configured: Boolean get() = url.isNotBlank() && token.isNotBlank()

    fun api() = Api(url, token)
}
