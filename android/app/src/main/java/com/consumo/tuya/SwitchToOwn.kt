package com.consumo.tuya

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Passa uma casa existente para o projeto Tuya do próprio usuário (dono do medidor),
 * para ele mesmo renovar o plano gratuito sem depender do administrador. O histórico continua.
 */
@Composable
fun SwitchToOwnScreen(prefs: Prefs, home: HomeInfo, onClose: () -> Unit, onUpdated: (HomeInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var dataCenter by remember { mutableStateOf("us") }
    var plan by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Meu próprio projeto Tuya", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(if (done) "Concluir" else "Fechar") }
                }

                when {
                    done -> {
                        Text("Pronto! \"${home.name}\" agora usa o seu projeto Tuya.", fontWeight = FontWeight.Bold)
                        Text("O histórico continua igual. Quando o plano estiver para vencer, o app avisa e mostra como renovar.")
                    }

                    page == 0 -> {
                        Text(
                            "Com o seu próprio projeto na Tuya, você não depende do administrador do Medidor+: " +
                                "o plano gratuito fica na sua conta e o app avisa quando for preciso renovar. " +
                                "Leva uns 15 minutos (é mais fácil no computador). Seu histórico continua.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Dica: quem instalou o seu medidor pode fazer estes passos com você — mas use o SEU e-mail " +
                                "na conta da Tuya, para a renovação ficar com você.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        PrimaryButton("Começar", false, true) { page = 1 }
                    }

                    page <= OWN_GUIDE.size -> {
                        val (title, lines) = OWN_GUIDE[page - 1]
                        Text("Passo $page: $title", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        if (page == OWN_GUIDE.size) {
                            Text(
                                "Use a mesma conta do Smart Life onde o medidor já está. Não remova o medidor do app nem refaça o pareamento.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(onClick = { openUrl(ctx, TUYA_PORTAL) }) { Text("Abrir platform.tuya.com") }
                        PrimaryButton("Feito, próximo", false, true) { page++ }
                        TextButton(onClick = { page-- }) { Text("‹ Voltar") }
                    }

                    else -> {
                        Text("Passo ${OWN_GUIDE.size + 1}: copie as chaves", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "No projeto, aba Overview: copie o Access ID/Client ID e o Access Secret/Client Secret (ícone do olho).",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text("Fez o portal no computador? Mande as duas chaves para você mesmo (ex.: WhatsApp Web) e copie aqui no celular. Use o botão Colar.", style = MaterialTheme.typography.bodySmall)
                        KeyField(clientId, "Access ID / Client ID") { clientId = it }
                        KeyField(secret, "Access Secret / Client Secret", secret = true) { secret = it }
                        Text("Data center do projeto (aparece no Overview):", style = MaterialTheme.typography.bodySmall)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DATA_CENTERS.forEach { (code, label) ->
                                FilterChip(selected = dataCenter == code, onClick = { dataCenter = code }, label = { Text(label) })
                            }
                        }
                        Text(
                            "Quando vence o plano gratuito? (Cloud → projeto → Service API ou Purchase → IoT Core)",
                            fontWeight = FontWeight.Bold,
                        )
                        PlanDateField(plan) { plan = it }
                        PrimaryButton("Conectar meu projeto", busy, clientId.isNotBlank() && secret.isNotBlank()) {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    onUpdated(prefs.api().switchToOwn(clientId, secret, dataCenter, plan))
                                    done = true
                                } catch (e: Exception) {
                                    error = e.message
                                }
                                busy = false
                            }
                        }
                        TextButton(onClick = { page-- }) { Text("‹ Voltar") }
                        error?.let {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Text(it, Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
