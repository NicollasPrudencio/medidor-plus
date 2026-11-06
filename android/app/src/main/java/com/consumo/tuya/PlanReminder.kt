package com.consumo.tuya

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Notificações em segundo plano (mesmo com o app fechado), verificadas a cada ~15 min
 * (mínimo permitido pelo Android):
 *  - recarga: avisa quando o carro começa a carregar e quando a recarga termina (com resumo);
 *  - plano da Tuya: avisa quem renova 7, 3 e 1 dia antes, no dia e diariamente se vencido.
 */
object Notifier {
    private const val CH_PLAN = "plano_tuya"
    private const val CH_CHARGE = "recargas"
    private const val WORK = "monitor"
    private const val ID_PLAN = 1
    private const val ID_CHARGE = 2
    const val EXTRA_OPEN_RENEW = "open_renew"

    /** Potência que caracteriza recarga (início) e abaixo da qual ela terminou (histerese). */
    const val CHARGE_ON_W = 1000.0
    const val CHARGE_OFF_W = 300.0

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Texto do aviso de plano, ou null se não é dia de avisar. */
    fun planMessage(home: HomeInfo): Pair<String, String>? {
        if (!canRenew(home)) return null
        if (home.status == "expired") {
            return "O plano da Tuya venceu" to "O Medidor+ parou de registrar o consumo de \"${home.name}\". Toque para renovar."
        }
        val days = daysUntil(home.planExpires) ?: return null
        return when {
            days < 0 -> "O plano da Tuya venceu" to "Toque para renovar e voltar a registrar o consumo."
            days == 0L -> "O plano da Tuya vence hoje" to "Renove agora para o Medidor+ não parar. Toque para ver como."
            days == 1L || days == 3L || days == 7L ->
                "O plano da Tuya vence em $days ${if (days == 1L) "dia" else "dias"}" to
                    "A aprovação da Tuya pode levar 1 a 2 dias. Toque para ver como renovar."
            else -> null
        }
    }

    fun notifyPlan(ctx: Context, title: String, text: String) =
        show(ctx, CH_PLAN, "Plano da Tuya", "Avisos para renovar o plano gratuito da Tuya", ID_PLAN, title, text, openRenew = true)

    fun notifyCharge(ctx: Context, title: String, text: String) =
        show(ctx, CH_CHARGE, "Recargas", "Quando o carro começa a carregar e quando termina", ID_CHARGE, title, text, openRenew = false)

    private fun show(
        ctx: Context, channel: String, channelName: String, channelDesc: String,
        id: Int, title: String, text: String, openRenew: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH).apply { description = channelDesc }
        )
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_RENEW, openRenew)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

class MonitorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.configured) return Result.success()
        val api = prefs.api()
        runCatching { checkCharge(prefs, api) }
        runCatching { checkPlan(prefs, api) }
        return Result.success()
    }

    /** Detecta início e fim de recarga pela potência atual. */
    private suspend fun checkCharge(prefs: Prefs, api: Api) {
        val now = api.now()
        val ctx = applicationContext
        if (!prefs.charging && now.powerW >= Notifier.CHARGE_ON_W) {
            prefs.charging = true
            prefs.chargeSeenAt = System.currentTimeMillis()
            if (prefs.notifyChargeStart) {
                Notifier.notifyCharge(
                    ctx,
                    "🔌 Carro carregando",
                    String.format(PT, "Recarga iniciada — %.1f kW agora. Toque para acompanhar ao vivo.", now.powerW / 1000),
                )
            }
        } else if (prefs.charging && now.powerW < Notifier.CHARGE_OFF_W) {
            prefs.charging = false
            if (prefs.notifyChargeEnd) {
                val t = System.currentTimeMillis()
                // Resumo da recarga que acabou (detectada pelo servidor)
                val s = runCatching { api.sessions(prefs.chargeSeenAt - 6 * 3_600_000L, t) }.getOrNull()
                    ?.lastOrNull { !it.manual && !it.ongoing }
                val cost = runCatching { prefs.costModel(api.flags()) }.getOrElse { prefs.costModel(emptyMap()) }
                val text = if (s != null) {
                    listOfNotNull(
                        kwh(s.kwh),
                        s.durationMs?.let { fmtDuration(it) },
                        cost.cost(s.kwh, s.start)?.let { brl(it) },
                    ).joinToString(" · ")
                } else {
                    "O carro parou de carregar."
                }
                Notifier.notifyCharge(ctx, "✅ Recarga concluída", text)
            }
        }
    }

    /** Aviso de renovação do plano (no máximo 1 por dia). */
    private suspend fun checkPlan(prefs: Prefs, api: Api) {
        val home = api.home()
        val (title, text) = Notifier.planMessage(home) ?: return
        val today = LocalDate.now().toString()
        if (prefs.lastPlanNotice == today) return
        Notifier.notifyPlan(applicationContext, title, text)
        prefs.lastPlanNotice = today
    }
}
