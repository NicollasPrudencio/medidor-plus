package com.consumo.tuya

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Seção do manual: título + parágrafos (linhas começando com "• " viram tópicos). */
private data class ManualSection(val title: String, val body: List<String>)

private val MANUAL = listOf(
    ManualSection(
        "O que é o Medidor+",
        listOf(
            "O Medidor+ mostra o consumo de energia do seu medidor com garra (Tuya): potência em tempo real, consumo por hora, dia, mês e ano, recargas do carro e quanto isso custa em reais.",
            "O medidor envia os dados para a nuvem da Tuya. Um servidor do Medidor+ lê o medidor a cada 10 minutos e guarda o histórico — por isso os dados continuam sendo registrados mesmo com o app fechado.",
            "Cada casa vê só os próprios dados. Várias pessoas da mesma casa podem usar o app em celulares diferentes.",
        ),
    ),
    ManualSection(
        "Atualizar a tela",
        listOf(
            "Puxe a tela de cima para baixo e solte. Aparece um círculo girando enquanto o app busca os dados mais recentes: potência, consumo, gráficos e recargas.",
            "Você não precisa fazer isso o tempo todo: com o app aberto, a potência em \"Agora\" se atualiza sozinha.",
        ),
    ),
    ManualSection(
        "Card \"Agora\"",
        listOf(
            "• Potência (W): quanto está sendo consumido neste momento.",
            "• Tensão (V) e corrente (A) da rede.",
            "• Hoje e Ontem: consumo do dia em kWh e o custo em R$.",
            "• Contador do medidor: total acumulado pelo próprio medidor desde que foi zerado.",
            "Com consumo baixo, a potência é atualizada a cada 30 segundos. Acima de 1,5 kW (por exemplo, o carro carregando), aparece \"● Ao vivo · 5 s\" piscando e a atualização passa a ser a cada 5 segundos.",
            "A atualização automática só acontece com o app aberto na tela — em segundo plano ela pausa para economizar bateria.",
        ),
    ),
    ManualSection(
        "Recargas",
        listOf(
            "Uma recarga é um período em que a potência ficou alta. Pausas curtas (até 20 minutos) não separam a recarga em duas.",
            "• No topo: quantas recargas houve no mês, em quantos dias, o total e o tempo carregando.",
            "• O gráfico tem uma barra por dia do mês. Toque numa barra para ver o resumo daquele dia.",
            "• Na lista, cada dia mostra quantas recargas, o tempo total, o consumo e o custo. Toque no dia para ver cada recarga: horário de início e fim, duração, potência média e de pico.",
            "• Toque numa recarga para ver a curva de potência dela.",
            "• Use ‹ e › para mudar de mês e os botões kWh / R$ para alternar entre consumo e custo.",
            "Recargas do histórico anotado antes do app aparecem como \"Horário não registrado\".",
        ),
    ),
    ManualSection(
        "Gráficos de consumo",
        listOf(
            "• Dia: consumo hora a hora.",
            "• Semana: últimos 7 dias.",
            "• Mês: dia a dia do mês.",
            "• Ano: mês a mês.",
            "• Potência: a potência registrada ao longo do dia.",
            "Escolha o formato: Barras, Linha ou Acumulado (soma ao longo do período), e a unidade: kWh ou R$.",
            "Use ‹ e › para ir ao período anterior ou seguinte. Toque no gráfico para ver o valor exato de cada ponto; toque de novo para voltar ao resumo (total, média e pico).",
            "\"Mostrar lista\" exibe os valores em tabela.",
        ),
    ),
    ManualSection(
        "Período personalizado",
        listOf(
            "Toque em \"Escolher período\", marque a data de início e a de fim no calendário e toque em OK.",
            "O app mostra o consumo total, o custo e a média por dia. Com a tarifa detalhada ligada, o custo aparece separado em energia, bandeira e impostos.",
        ),
    ),
    ManualSection(
        "Exportar para CSV ou Excel",
        listOf(
            "Toque em \"Exportar\" (no gráfico ou nas recargas) ou em \"Exportar dias\" (no período personalizado) e escolha CSV ou Excel (.xlsx).",
            "Abre o menu de compartilhar do Android: você pode salvar no Google Drive, em Meus Arquivos, ou enviar por WhatsApp, e-mail etc.",
            "O arquivo exporta exatamente o que está na tela, com uma linha de total no final. O CSV usa o padrão brasileiro (ponto e vírgula e vírgula decimal) e abre direto no Excel.",
        ),
    ),
    ManualSection(
        "O ícone ⓘ (valor estimado)",
        listOf(
            "O ⓘ aparece quando parte do valor foi estimada. Acontece quando:",
            "• faltaram leituras (internet do medidor caiu, servidor fora do ar) perto do início ou fim do período;",
            "• o dia é anterior ao começo das leituras automáticas e foi estimado a partir do contador do medidor.",
            "O total entre as leituras existentes é sempre exato — o que é estimado é como esse total se divide entre horas ou dias vizinhos. Toque no ⓘ para ver a explicação.",
        ),
    ),
    ManualSection(
        "Tarifa simples (padrão)",
        listOf(
            "Em Config, informe o valor do kWh com impostos. Todo o custo do app é calculado com ele.",
            "Para descobrir o valor: na conta de luz, divida o valor total a pagar pelo total de kWh consumidos.",
        ),
    ),
    ManualSection(
        "Tarifa detalhada (opcional)",
        listOf(
            "Para um custo mais fiel à conta, ligue \"Tarifa detalhada\" em Config e preencha com os dados da sua conta de luz:",
            "• Tarifa base TE + TU (R$/kWh, sem impostos);",
            "• ICMS (%);",
            "• PIS + COFINS (%);",
            "• Desconto (%), se você tem geração compartilhada ou energia por assinatura (ex.: FIT Energia).",
            "Veja em \"Onde encontrar os valores na conta de luz\" exatamente onde cada número fica na fatura.",
            "Depois marque a bandeira de cada mês (verde, amarela, vermelha P1 ou P2). A bandeira aparece na conta de luz e é divulgada pela ANEEL todo mês. Mês sem bandeira marcada é considerado verde.",
            "Cálculo (igual ao da distribuidora): (tarifa base + adicional da bandeira) ÷ ((1 − ICMS) × (1 − PIS − COFINS)) × (1 − desconto).",
            "Os valores adicionais de cada bandeira já vêm preenchidos; se a ANEEL mudar, ajuste em \"Valores das bandeiras\".",
            "Itens fixos da conta (iluminação pública, taxa mínima) não entram, porque não mudam com o consumo do carro.",
            "Se a tarifa base ficar vazia, o app continua usando a tarifa simples.",
        ),
    ),
    ManualSection(
        "Onde encontrar os valores na conta de luz",
        listOf(
            "Use a fatura mais recente. Os exemplos abaixo são da conta CEEE (Grupo Equatorial) e da fatura FIT Energia.",
            "TARIFA SIMPLES (um número só):",
            "• Com FIT Energia: no \"Detalhamento da fatura\" da FIT, divida o valor do \"Custo de geração própria FIT\" pelos kWh da linha \"Preço energia CEEE\". Ex.: 461,18 ÷ 503 = 0,9169.",
            "• Sem FIT: use a \"Tarifa CEEE\" da fatura FIT (ex.: 1,04188814) ou divida o total da conta pelos kWh consumidos.",
            "TARIFA DETALHADA — digite os números exatamente como estão na fatura; o app faz as contas:",
            "• Consumo TE e Consumo TU: conta CEEE, tabela \"Itens da Fatura\", ÚLTIMA coluna \"Tarifa Unit. (R$)\", linhas \"Consumo TE (kWh)\" e \"Consumo TU (kWh)\". Ex.: 0,343370 e 0,478630. Não use a coluna \"Preço Unit. com Tributos\".",
            "• ICMS, PIS e COFINS: quadro \"Tributo\" (à direita da tabela), coluna \"Alíquota (%)\". Ex.: 17, 0,4885 e 2,2785.",
            "• Geração compartilhada (só se tiver FIT): na fatura FIT, \"Detalhamento da fatura\", coluna Valores — o valor riscado da linha \"Preço energia CEEE\" e o valor do \"Custo de geração própria FIT\". Ex.: 524,07 e 461,18. O app calcula o desconto (12%).",
            "• Bandeira: aparece em \"Mensagens importantes\" da conta CEEE (ex.: \"Band. Tarif.: Amarela\") e no rodapé da fatura FIT. Toque na cor do mês correspondente em Config.",
            "Enquanto você digita, o app mostra \"Seu kWh: R$ ...\". Confira: com os exemplos acima e bandeira amarela dá R$ 0,9169 com FIT e R$ 1,0419 sem desconto (igual à \"Tarifa CEEE\" da fatura FIT).",
            "As alíquotas de PIS e COFINS mudam um pouco todo mês; atualizar uma vez a cada poucos meses já é suficiente.",
        ),
    ),
    ManualSection(
        "Atualizações do app",
        listOf(
            "Quando sai uma versão nova, o app avisa ao abrir. Se a atualização for obrigatória, o aviso não fecha até atualizar.",
            "• Toque em \"Atualizar\" e aguarde o download.",
            "• Na primeira vez, o Android pede permissão: ative \"Permitir desta fonte\" para o Medidor+ e volte.",
            "• Toque em \"Instalar\" e confirme. Seus dados e configurações continuam lá.",
        ),
    ),
    ManualSection(
        "Primeiro acesso: entrar numa casa ou cadastrar um medidor",
        listOf(
            "Na primeira vez que abre o app, escolha:",
            "• Entrar numa casa que já usa o Medidor+: digite o código de acesso que alguém da casa enviou (Config → Compartilhar acesso a esta casa). Vale 7 dias.",
            "• Cadastrar o meu medidor: precisa do código de convite do administrador do Medidor+ (vale 30 dias, uso único). Depois escolha como conectar:",
            "   – Pelo app Smart Life (mais fácil): o administrador gera um QR code de vínculo; você escaneia no Smart Life (Eu → ícone de escanear) e depois copia o \"ID virtual\" do medidor (no Smart Life: abra o medidor → ✏️ → Informações do dispositivo). Você não precisa renovar nada.",
            "   – Com meu próprio projeto Tuya (avançado): o app mostra o passo a passo do portal platform.tuya.com, você copia o Access ID e o Access Secret, escolhe o medidor e informa quando vence o plano gratuito.",
            "Se o medidor tiver placas solares, marque a opção no último passo: assim a energia injetada na rede não é contada como consumo.",
        ),
    ),
    ManualSection(
        "Compartilhar com outra pessoa da casa",
        listOf(
            "Em Config → \"Compartilhar acesso a esta casa\": o app envia uma mensagem (WhatsApp, etc.) com o link de instalação e um código de acesso válido por 7 dias.",
            "A outra pessoa instala, abre o app, toca em \"Entrar numa casa que já usa o Medidor+\" e digita o código.",
            "Para tirar o app de um celular da casa, use Config → \"Sair desta casa\" naquele celular.",
        ),
    ),
    ManualSection(
        "Renovar o plano da Tuya",
        listOf(
            "A Tuya oferece um plano gratuito que precisa ser renovado de tempos em tempos. Quem renova:",
            "• Conectado pelo Smart Life: o administrador do Medidor+ renova por todos — você não precisa fazer nada.",
            "• Projeto próprio: você renova o seu.",
            "O app avisa no topo da tela 7 dias antes de vencer. Toque em \"Como renovar\" e siga o passo a passo: entrar em platform.tuya.com → Cloud → Development → seu projeto → Service API / Purchase → IoT Core → Extend/Renew → enviar o formulário. A aprovação pode levar de algumas horas a 1–2 dias.",
            "Depois de aprovado, informe a nova data de vencimento no próprio passo a passo (ou em Config → Renovar / alterar vencimento). O app testa a conexão na hora.",
            "Se vencer, o app avisa e para de registrar leituras, mas a energia consumida não se perde: aparece quando o plano voltar.",
        ),
    ),
    ManualSection(
        "Administrador do Medidor+",
        listOf(
            "Só aparece no celular do administrador (Config → Administração):",
            "• Convidar nova casa: gera um convite (30 dias, uso único) e já monta a mensagem com o link de instalação.",
            "• Para quem vai conectar pelo Smart Life: no portal da Tuya, abra o projeto → Devices → Link App Account → Add App Account e mostre o QR para a pessoa escanear (ele expira em poucos minutos).",
            "• Casas cadastradas: mostra todas as casas, se estão funcionando e a última leitura.",
            "• Renovar o plano central: vale para todas as casas conectadas pelo Smart Life. Informe sempre a data de vencimento para receber o aviso.",
        ),
    ),
    ManualSection(
        "Dúvidas comuns",
        listOf(
            "• O valor de \"Hoje\" não mudou: puxe a tela para atualizar. Sem consumo, o medidor não envia leituras novas.",
            "• Aparece erro de conexão: verifique a internet do celular e puxe para atualizar.",
            "• A potência aparece 0 W com o carro carregando: confira se o medidor está online no app da Tuya (Smart Life).",
            "• Dias sem dados no gráfico: o medidor ou a internet dele estava fora do ar e não há leituras desse período.",
            "• Aparece \"plano da Tuya venceu\" ou \"serviço indisponível\": veja \"Renovar o plano da Tuya\". A energia consumida nesse tempo não se perde.",
            "• Troquei de celular: no celular novo, peça um código de acesso a alguém da casa (ou use Compartilhar acesso no celular antigo) e entre na casa.",
        ),
    ),
)

/** Tela cheia com o manual do usuário (funciona sem internet). */
@Composable
fun ManualScreen(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Manual do usuário", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Fechar") }
                }
                Text(
                    "Toque num assunto para abrir.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                var open by remember { mutableStateOf<Int?>(1) } // começa com "Atualizar a tela" aberto
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(MANUAL) { i, s ->
                        Card(Modifier.fillMaxWidth().clickable { open = if (open == i) null else i }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(if (open == i) "−" else "+", style = MaterialTheme.typography.titleMedium)
                                }
                                if (open == i) {
                                    s.body.forEach { p ->
                                        Text(
                                            p,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = if (p.startsWith("• ")) Modifier.padding(start = 8.dp) else Modifier,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
