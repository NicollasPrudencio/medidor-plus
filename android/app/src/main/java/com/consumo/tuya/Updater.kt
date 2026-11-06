package com.consumo.tuya

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object Updater {
    fun apkFile(ctx: Context) = File(ctx.cacheDir, "updates/update.apk").apply { parentFile?.mkdirs() }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Abre a tela "Instalar apps desconhecidos" deste app. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Abre o instalador do Android com o APK baixado. */
    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Verifica se há versão nova ao abrir o app.
 * Se a versão instalada for menor que minVersionCode, o diálogo não pode ser fechado.
 */
@Composable
fun UpdateGate(prefs: Prefs, checkKey: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<AppVersion?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var downloaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(checkKey) {
        if (!prefs.configured) return@LaunchedEffect
        runCatching { prefs.api().version() }
            .onSuccess { if (it.versionCode > BuildConfig.VERSION_CODE) info = it }
    }

    val v = info ?: return
    val force = BuildConfig.VERSION_CODE < v.minVersionCode
    if (dismissed && !force) return

    fun startInstall() {
        if (!Updater.canInstall(ctx)) {
            error = "Permita \"Instalar apps desconhecidos\" para o Medidor+, volte e toque em Instalar."
            Updater.openInstallPermission(ctx)
        } else {
            error = null
            Updater.install(ctx, Updater.apkFile(ctx))
        }
    }

    AlertDialog(
        onDismissRequest = { if (!force) dismissed = true },
        properties = DialogProperties(dismissOnBackPress = !force, dismissOnClickOutside = !force),
        title = { Text(if (force) "Atualização obrigatória" else "Nova versão disponível") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Versão ${v.versionName} (instalada: ${BuildConfig.VERSION_NAME})")
                if (v.size > 0) {
                    Text(String.format(Locale.US, "%.1f MB", v.size / 1_048_576.0), style = MaterialTheme.typography.bodySmall)
                }
                if (v.notes.isNotBlank()) Text(v.notes)
                if (force) {
                    Text(
                        "Esta versão do app não é mais suportada. Atualize para continuar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                progress?.takeIf { !downloaded }?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress == null || downloaded,
                onClick = {
                    if (downloaded) {
                        startInstall()
                        return@TextButton
                    }
                    error = null
                    progress = 0f
                    scope.launch {
                        try {
                            prefs.api().downloadApk(Updater.apkFile(ctx)) { p ->
                                scope.launch(Dispatchers.Main) { progress = p }
                            }
                            withContext(Dispatchers.Main) {
                                downloaded = true
                                startInstall()
                            }
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                            progress = null
                        }
                    }
                },
            ) { Text(if (downloaded) "Instalar" else "Atualizar") }
        },
        dismissButton = if (force) null else {
            { TextButton(onClick = { dismissed = true }) { Text("Depois") } }
        },
    )
}
