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

/** Casa (medidor) à qual este aparelho está conectado. */
data class HomeInfo(
    val id: String,
    val name: String,
    /** "central" = Smart Life vinculado ao projeto do administrador; "own" = projeto Tuya próprio. */
    val mode: String,
    val deviceId: String,
    /** Vencimento do plano gratuito da Tuya (AAAA-MM-DD) — próprio (own) ou do projeto central. */
    val planExpires: String?,
    /** ok | error | expired */
    val status: String?,
    val lastOk: Long?,
    val lastError: String?,
    val isAdmin: Boolean,
    /** Responsável pela casa: quem conectou o projeto Tuya (recebe os avisos e renova o plano). */
    val isOwner: Boolean,
    /** Administrador vendo uma casa que não é a dele. */
    val viewing: Boolean = false,
) {
    val own get() = mode == "own"
}

data class Code(val code: String, val expires: Long)

data class TuyaDevice(val id: String, val name: String, val product: String, val online: Boolean?)

data class AdminHome(val id: String, val name: String, val mode: String, val status: String?, val lastOk: Long?, val lastError: String?, val planExpires: String?)

/** Erro da API com código opcional (ex.: "tuya_expired" = plano da Tuya vencido). */
class ApiException(message: String, val code: String?) : Exception(message)

/** Versão publicada no servidor. Abaixo de [minVersionCode] a atualização é obrigatória. */
data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val size: Long,
    val minVersionCode: Int,
)

/** Cliente do backend (Cloudflare Worker). */
class Api(private val baseUrl: String, private val token: String, private val viewHome: String? = null) {

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

    suspend fun home(): HomeInfo = parseHome(JSONObject(request("/api/home")))

    /** Código para outra pessoa da casa entrar (vale 7 dias). */
    suspend fun shareCode(): Code = parseCode(JSONObject(request("/api/home/share", "POST")))

    suspend fun setPlan(expires: String): HomeInfo = parseHome(JSONObject(request("/api/home/plan?expires=${enc(expires)}", "PUT")))

    suspend fun setName(name: String): HomeInfo = parseHome(JSONObject(request("/api/home/name?name=${enc(name)}", "PUT")))

    /** Administrador: convite para cadastrar uma casa nova (vale 30 dias, 1 uso). */
    suspend fun createInvite(): Code = parseCode(JSONObject(request("/api/admin/invites", "POST")))

    suspend fun adminHomes(): Pair<String?, List<AdminHome>> {
        val o = JSONObject(request("/api/admin/homes"))
        val a = o.getJSONArray("homes")
        return o.stringOrNull("centralPlanExpires") to (0 until a.length()).map {
            val h = a.getJSONObject(it)
            AdminHome(
                h.getString("id"), h.getString("name"), h.getString("mode"), h.stringOrNull("status"),
                h.longOrNull("last_ok"), h.stringOrNull("last_error"), h.stringOrNull("plan_expires"),
            )
        }
    }

    /** Passa esta casa para o projeto Tuya do próprio usuário (mantém o histórico). */
    suspend fun switchToOwn(clientId: String, secret: String, dataCenter: String, planExpires: String?): HomeInfo {
        val body = JSONObject().put("clientId", clientId).put("secret", secret).put("dataCenter", dataCenter)
        planExpires?.let { body.put("planExpires", it) }
        return parseHome(JSONObject(request("/api/home/connection", "PUT", body)))
    }

    // ---- Cadastro (sem token) ----

    /** "share" (código de acesso a uma casa) ou "invite" (convite para cadastrar medidor). */
    suspend fun checkCode(code: String): String = JSONObject(request("/api/codes/check?code=${enc(code)}", "POST")).getString("kind")

    /** Entra numa casa existente; devolve o token deste aparelho. */
    suspend fun join(code: String): Pair<String, HomeInfo> {
        val o = JSONObject(request("/api/join?code=${enc(code)}", "POST"))
        return o.getString("token") to parseHome(o.getJSONObject("home"))
    }

    /** Projeto próprio: valida Access ID/Secret e lista os aparelhos do projeto. */
    suspend fun registerDevices(invite: String, clientId: String, secret: String, dataCenter: String): List<TuyaDevice> {
        val body = JSONObject().put("invite", invite).put("clientId", clientId).put("secret", secret).put("dataCenter", dataCenter)
        val a = JSONArray(request("/api/register/devices", "POST", body))
        return (0 until a.length()).map {
            val d = a.getJSONObject(it)
            TuyaDevice(d.getString("id"), d.optString("name"), d.optString("product"), if (d.isNull("online")) null else d.getBoolean("online"))
        }
    }

    /** Cria a casa; devolve o token deste aparelho. */
    suspend fun register(body: JSONObject): Pair<String, HomeInfo> {
        val o = JSONObject(request("/api/register", "POST", body))
        return o.getString("token") to parseHome(o.getJSONObject("home"))
    }

    private fun parseHome(o: JSONObject) = HomeInfo(
        id = o.getString("id"),
        name = o.optString("name"),
        mode = o.optString("mode"),
        deviceId = o.optString("deviceId"),
        planExpires = o.stringOrNull("planExpires"),
        status = o.stringOrNull("status"),
        lastOk = o.longOrNull("lastOk"),
        lastError = o.stringOrNull("lastError"),
        isAdmin = o.optBoolean("isAdmin"),
        isOwner = o.optBoolean("isOwner"),
        viewing = o.optBoolean("viewing"),
    )

    private fun parseCode(o: JSONObject) = Code(o.getString("code"), o.getLong("expires"))

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
        viewHome?.let { conn.setRequestProperty("X-Home", it) } // administrador abrindo outra casa
        conn.connectTimeout = 15_000
        return conn
    }

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val conn = open(path)
        conn.readTimeout = 30_000
        conn.requestMethod = method
        if (method != "GET") {
            val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            conn.doOutput = true
            if (body != null) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val err = runCatching { JSONObject(text) }.getOrNull()
                throw ApiException(err?.optString("error")?.ifBlank { null } ?: "Erro HTTP $code", err?.stringOrNull("code"))
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
}

/** Configurações salvas no aparelho. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    // Padrões embutidos no build (publish.properties); o usuário pode sobrescrever em "Avançado".
    var url: String
        get() = sp.getString("url", null) ?: BuildConfig.DEFAULT_URL
        set(v) = sp.edit().putString("url", v.ifBlank { BuildConfig.DEFAULT_URL }).apply()

    /** Token deste aparelho na sua casa (obtido no cadastro ou com o código de acesso). */
    var token: String
        get() = sp.getString("token", null) ?: ""
        set(v) = sp.edit().putString("token", v).apply()

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

    // ---- Notificações ----
    var notifyChargeStart: Boolean
        get() = sp.getBoolean("notifyChargeStart", true)
        set(v) = sp.edit().putBoolean("notifyChargeStart", v).apply()
    var notifyChargeEnd: Boolean
        get() = sp.getBoolean("notifyChargeEnd", true)
        set(v) = sp.edit().putBoolean("notifyChargeEnd", v).apply()

    /** Estado da última verificação em segundo plano (para detectar início/fim de recarga). */
    var charging: Boolean
        get() = sp.getBoolean("charging", false)
        set(v) = sp.edit().putBoolean("charging", v).apply()
    var chargeSeenAt: Long
        get() = sp.getLong("chargeSeenAt", 0L)
        set(v) = sp.edit().putLong("chargeSeenAt", v).apply()

    /** Último dia (AAAA-MM-DD) em que foi mostrado o aviso de renovação do plano. */
    var lastPlanNotice: String?
        get() = sp.getString("lastPlanNotice", null)
        set(v) = sp.edit().putString("lastPlanNotice", v).apply()

    val configured: Boolean get() = url.isNotBlank() && token.isNotBlank()

    /** Administrador: casa aberta para suporte (null = a própria casa). */
    var viewHome: String?
        get() = sp.getString("viewHome", null)
        set(v) = sp.edit().putString("viewHome", v).apply()

    /** Id da casa do próprio aparelho (para saber quando o admin voltou para ela). */
    var ownHomeId: String?
        get() = sp.getString("ownHomeId", null)
        set(v) = sp.edit().putString("ownHomeId", v).apply()

    fun api() = Api(url, token, viewHome)

    /** Sempre a casa do próprio aparelho (usado pelas notificações em segundo plano). */
    fun ownApi() = Api(url, token)
}
