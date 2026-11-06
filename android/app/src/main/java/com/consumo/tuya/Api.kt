package com.consumo.tuya

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

data class PeriodKwh(val kwh: Double?, val estimated: Boolean)

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
        val o = JSONObject(get("/api/now"))
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
        val a = JSONArray(get("/api/series?start=$start&end=$end&step=$step"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Bucket(o.getLong("start"), o.doubleOrNull("kwh"), o.optBoolean("estimated"))
        }
    }

    suspend fun power(start: Long, end: Long): List<PowerPoint> {
        val a = JSONArray(get("/api/power?start=$start&end=$end"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            PowerPoint(o.getLong("ts"), o.getDouble("w"))
        }
    }

    /** start/end em epoch ms. */
    suspend fun period(start: Long, end: Long): PeriodKwh {
        val o = JSONObject(get("/api/period?start=$start&end=$end"))
        return PeriodKwh(o.doubleOrNull("kwh"), o.optBoolean("estimated"))
    }

    suspend fun version(): AppVersion {
        val o = JSONObject(get("/api/version"))
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

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
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

    var tariff: Double
        get() = sp.getFloat("tariff", 0.90f).toDouble()
        set(v) = sp.edit().putFloat("tariff", v.toFloat()).apply()

    val configured: Boolean get() = url.isNotBlank() && token.isNotBlank()

    fun api() = Api(url, token)
}
