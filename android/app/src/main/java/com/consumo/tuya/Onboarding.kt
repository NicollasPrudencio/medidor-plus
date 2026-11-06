package com.consumo.tuya

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

const val TUYA_PORTAL = "https://platform.tuya.com"

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** Data centers da Tuya (código do servidor → nome no portal). */
val DATA_CENTERS = listOf(
    "us" to "Western America (Brasil)",
    "ueaz" to "Eastern America",
    "eu" to "Central Europe",
    "weaz" to "Western Europe",
    "in" to "India",
    "cn" to "China",
)

private enum class Step { WELCOME, JOIN, INVITE, MODE, CENTRAL, OWN_GUIDE, OWN_KEYS, OWN_PICK, DETAILS }

/** Passo a passo do portal da Tuya para quem usa projeto próprio. */
val OWN_GUIDE = listOf(
    "Crie sua conta de desenvolvedor" to listOf(
        "Abra platform.tuya.com e toque em Sign Up. É gratuito.",
        "Dica: faça isso no computador — o portal é mais fácil de usar numa tela grande.",
    ),
    "Crie um projeto na nuvem" to listOf(
        "Menu Cloud → Development → Create Cloud Project.",
        "• Project Name: Medidor+",
        "• Industry: Smart Home",
        "• Development Method: Smart Home",
        "• Data Center: Western America (para contas do Brasil)",
        "Toque em Create.",
    ),
    "Autorize as APIs" to listOf(
        "Na tela seguinte (Authorize API Services), deixe marcados pelo menos:",
        "• IoT Core",
        "• Authorization Token Management",
        "• Smart Home Basic Service",
        "Toque em Authorize.",
    ),
    "Vincule seu app Smart Life" to listOf(
        "No projeto, abra a aba Devices → Link App Account → Add App Account.",
        "Aparece um QR code. No celular, abra o app Smart Life (ou Tuya Smart) → Eu → ícone de escanear (canto superior direito) → escaneie e confirme.",
        "Escolha Automatic Link. Seu medidor deve aparecer em All Devices.",
    ),
)

/** Tela cheia mostrada enquanto o aparelho não está conectado a uma casa. */
@Composable
fun OnboardingScreen(prefs: Prefs, onDone: (HomeInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.WELCOME) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var joinCode by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("central") }
    var guidePage by remember { mutableIntStateOf(0) }
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var dataCenter by remember { mutableStateOf("us") }
    var devices by remember { mutableStateOf<List<TuyaDevice>>(emptyList()) }
    var deviceId by remember { mutableStateOf("") }
    var homeName by remember { mutableStateOf("") }
    var solar by remember { mutableStateOf(false) }
    var planExpires by remember { mutableStateOf<String?>(null) }

    val api = Api(prefs.url, "")
    fun go(s: Step) {
        error = null
        step = s
    }
    fun finish(r: Pair<String, HomeInfo>) {
        prefs.token = r.first
        onDone(r.second)
    }
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (step != Step.WELCOME) {
            TextButton(onClick = {
                go(
                    when (step) {
                        Step.JOIN, Step.INVITE -> Step.WELCOME
                        Step.MODE -> Step.INVITE
                        Step.CENTRAL, Step.OWN_GUIDE -> Step.MODE
                        Step.OWN_KEYS -> Step.OWN_GUIDE
                        Step.OWN_PICK -> Step.OWN_KEYS
                        Step.DETAILS -> if (mode == "central") Step.CENTRAL else Step.OWN_PICK
                        Step.WELCOME -> Step.WELCOME
                    }
                )
            }) { Text("‹ Voltar") }
        }

        when (step) {
            Step.WELCOME -> {
                Spacer(Modifier.height(24.dp))
                Text("Medidor+", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("Consumo de energia, recargas e custo do seu medidor Tuya.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                ChoiceCard(
                    "Entrar numa casa que já usa o Medidor+",
                    "Alguém da sua casa já usa? Peça a ele o código de acesso (Config → Compartilhar acesso).",
                ) { go(Step.JOIN) }
                ChoiceCard(
                    "Cadastrar o meu medidor",
                    "Primeira vez com o seu medidor. Você vai precisar do código de convite do administrador do Medidor+.",
                ) { go(Step.INVITE) }
            }

            Step.JOIN -> {
                Title("Código de acesso", "Digite o código que recebeu de quem já usa o Medidor+ na sua casa.")
                CodeField(joinCode, "Código de acesso") { joinCode = it }
                PrimaryButton("Entrar", busy, joinCode.length >= 8) {
                    run {
                        if (api.checkCode(joinCode) == "invite") {
                            // é um convite para cadastrar medidor
                            invite = joinCode
                            go(Step.MODE)
                        } else {
                            finish(api.join(joinCode))
                        }
                    }
                }
            }

            Step.INVITE -> {
                Title("Código de convite", "Para cadastrar um medidor novo, use o convite enviado pelo administrador do Medidor+.")
                CodeField(invite, "Código de convite") { invite = it }
                PrimaryButton("Continuar", busy, invite.length >= 8) {
                    run {
                        if (api.checkCode(invite) == "share") {
                            // é um código de acesso a uma casa existente: entra direto
                            finish(api.join(invite))
                        } else {
                            go(Step.MODE)
                        }
                    }
                }
            }

            Step.MODE -> {
                Title("Como conectar o medidor", "Escolha uma das formas:")
                ChoiceCard(
                    "Pelo app Smart Life (mais fácil)",
                    "Você escaneia um QR code com o Smart Life e pronto. Não precisa criar conta de desenvolvedor nem renovar nada — o administrador cuida disso.",
                ) {
                    mode = "central"
                    go(Step.CENTRAL)
                }
                ChoiceCard(
                    "Com meu próprio projeto Tuya (avançado)",
                    "Você cria um projeto gratuito no portal da Tuya e não depende do administrador. Leva uns 15 minutos e o plano precisa ser renovado de tempos em tempos (o app avisa e ensina).",
                ) {
                    mode = "own"
                    guidePage = 0
                    go(Step.OWN_GUIDE)
                }
            }

            Step.CENTRAL -> {
                Title("Conectar pelo Smart Life", null)
                StepItem(1, "Peça ao administrador do Medidor+ o QR code de vínculo. Ele vale poucos minutos — combine para fazer na hora.")
                StepItem(2, "No app Smart Life (ou Tuya Smart): toque em Eu → ícone de escanear (canto superior direito) → escaneie o QR → Confirmar.")
                StepItem(3, "Ainda no Smart Life, abra o seu medidor → toque no lápis ✏️ (canto superior direito) → Informações do dispositivo → copie o ID virtual.")
                OutlinedTextField(
                    value = deviceId, onValueChange = { deviceId = it.trim() }, singleLine = true,
                    label = { Text("ID virtual do medidor") }, modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton("Continuar", false, deviceId.length >= 10) { go(Step.DETAILS) }
            }

            Step.OWN_GUIDE -> {
                val (title, lines) = OWN_GUIDE[guidePage]
                LinearProgressIndicator(
                    progress = { (guidePage + 1f) / (OWN_GUIDE.size + 2) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Title("Passo ${guidePage + 1}: $title", null)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                OutlinedButton(onClick = { openUrl(ctx, TUYA_PORTAL) }) { Text("Abrir platform.tuya.com") }
                PrimaryButton("Feito, próximo", false, true) {
                    if (guidePage < OWN_GUIDE.lastIndex) guidePage++ else go(Step.OWN_KEYS)
                }
            }

            Step.OWN_KEYS -> {
                LinearProgressIndicator(
                    progress = { (OWN_GUIDE.size + 1f) / (OWN_GUIDE.size + 2) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Title(
                    "Passo ${OWN_GUIDE.size + 1}: copie as chaves",
                    "No projeto, aba Overview: copie o Access ID/Client ID e o Access Secret/Client Secret (toque no ícone do olho para ver).",
                )
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it.trim() }, singleLine = true,
                    label = { Text("Access ID / Client ID") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it.trim() }, singleLine = true,
                    label = { Text("Access Secret / Client Secret") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Text("Data center do projeto (aparece no Overview):", style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DATA_CENTERS.forEach { (code, label) ->
                        FilterChip(selected = dataCenter == code, onClick = { dataCenter = code }, label = { Text(label) })
                    }
                }
                Text(
                    "Suas chaves ficam guardadas criptografadas no servidor do Medidor+, para ler o medidor 24h por dia.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PrimaryButton("Testar e buscar medidores", busy, clientId.isNotBlank() && secret.isNotBlank()) {
                    run {
                        devices = api.registerDevices(invite, clientId, secret, dataCenter)
                        if (devices.isEmpty()) error = "Nenhum aparelho no projeto. Confira o passo 4 (vincular o Smart Life)."
                        else go(Step.OWN_PICK)
                    }
                }
            }

            Step.OWN_PICK -> {
                Title("Escolha o medidor", "Aparelhos encontrados no seu projeto:")
                devices.forEach { d ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                deviceId = d.id
                                if (homeName.isBlank()) homeName = "Minha casa"
                                go(Step.DETAILS)
                            },
                        colors = if (deviceId == d.id) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.cardColors(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(d.name, fontWeight = FontWeight.Bold)
                            Text(
                                listOfNotNull(d.product.takeIf { it.isNotBlank() && it != d.name }, d.online?.let { if (it) "online" else "offline" })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Step.DETAILS -> {
                Title("Últimos detalhes", null)
                OutlinedTextField(
                    value = homeName, onValueChange = { homeName = it }, singleLine = true,
                    label = { Text("Nome da casa") }, placeholder = { Text("Ex.: Casa da praia") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { solar = !solar }) {
                    Checkbox(checked = solar, onCheckedChange = { solar = it })
                    Text("Tenho placas solares (geração própria) neste medidor")
                }
                if (mode == "own") {
                    Text("Quando vence o plano gratuito da Tuya?", fontWeight = FontWeight.Bold)
                    Text(
                        "No portal: Cloud → seu projeto → Service API (ou Purchase): veja a data de expiração do IoT Core. O app avisa alguns dias antes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PlanDateField(planExpires) { planExpires = it }
                }
                PrimaryButton("Concluir cadastro", busy, deviceId.isNotBlank()) {
                    run {
                        val body = JSONObject()
                            .put("invite", invite)
                            .put("mode", mode)
                            .put("deviceId", deviceId)
                            .put("name", homeName.ifBlank { "Minha casa" })
                            .put("solar", solar)
                        if (mode == "own") {
                            body.put("clientId", clientId).put("secret", secret).put("dataCenter", dataCenter)
                            planExpires?.let { body.put("planExpires", it) }
                        }
                        finish(api.register(body))
                    }
                }
            }
        }

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String?) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun ChoiceCard(title: String, body: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun StepItem(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$n.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CodeField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8)) },
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text("8 letras/números") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        textStyle = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(text)
    }
}
