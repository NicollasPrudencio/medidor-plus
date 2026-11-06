# Medidor+

Acompanhe o consumo de energia diário, por hora e por período (início/fim) de um medidor Tuya com garra (Dual Channel Smart Meter), com app Android e backend gratuito no Cloudflare.

```
Tuya Cloud ◄── a cada 10 min ── Cloudflare Worker + D1 (grava leituras)
                                       ▲
                                App Android (só consulta)
```

- `backend/`: Cloudflare Worker (gratuito). Coleta o contador acumulado do medidor e calcula o consumo.
- `android/`: app Kotlin + Jetpack Compose.

## Por que é robusto

O medidor guarda um **contador acumulado** de energia (`fwd_acc_kwh + rev_acc_kwh`). O consumo de qualquer período é a diferença do contador entre o fim e o início, com interpolação linear nas bordas. Por isso:

- **Coletas perdidas não perdem energia.** O total entre duas leituras fica exato; só a divisão entre dias vizinhos vira uma estimativa linear.
- **A garra invertida não importa.** Os contadores direto e reverso são somados.
- **Dados zerados no medidor** (`clr`) não geram consumo negativo.
- **Falhas de token da Tuya** são tratadas: o token é renovado e a chamada é repetida.

## 1. Backend (Cloudflare)

Pré-requisitos: uma conta gratuita em https://dash.cloudflare.com e o Node.js.

```powershell
cd backend
npm install
npx wrangler login

# cria o banco e copia o "database_id" exibido para o wrangler.toml
npx wrangler d1 create consumo-tuya
npx wrangler d1 execute consumo-tuya --remote --file=schema.sql

# segredos (o valor é digitado no prompt e não fica em arquivo)
npx wrangler secret put TUYA_CLIENT_ID      # Access ID do projeto Tuya
npx wrangler secret put TUYA_CLIENT_SECRET  # Access Secret do projeto Tuya
npx wrangler secret put APP_TOKEN           # uma senha longa inventada por você (o app usa)

npx wrangler deploy
```

O deploy mostra a URL, algo como `https://app.<seu-subdominio>.workers.dev`.

### Testar

```powershell
$h = @{ Authorization = "Bearer SEU_APP_TOKEN" }
$u = "https://app.<seu-subdominio>.workers.dev"
Invoke-RestMethod "$u/api/collect" -Headers $h   # força uma coleta
Invoke-RestMethod "$u/api/now" -Headers $h
Invoke-RestMethod "$u/api/daily?from=2026-11-01&to=2026-11-06" -Headers $h
```

Logs em tempo real: `npx wrangler tail`.

### API

| Rota | Retorno |
|---|---|
| `GET /api/now` | Potência, tensão, corrente, kWh de hoje e de ontem (também grava uma leitura) |
| `GET /api/daily?from=AAAA-MM-DD&to=AAAA-MM-DD` | `[{date, kwh}]`, um item por dia (`null` = sem leituras) |
| `GET /api/period?start=<ms>&end=<ms>` | `{kwh}` entre dois instantes (epoch ms) |
| `GET /api/series?start=<ms>&end=<ms>&step=hour\|day\|week\|month` | `[{start, kwh, estimated}]`, consumo agrupado (usado pelos gráficos) |
| `GET /api/power?start=<ms>&end=<ms>` | `[{ts, w}]`, potência registrada em cada coleta (máx. 7 dias) |
| `GET /api/collect` | Força uma coleta |

Todas exigem o header `Authorization: Bearer <APP_TOKEN>`.

Cada valor de consumo vem acompanhado de `estimated` (`todayEstimated`/`yesterdayEstimated` no `/api/now`). Ele é `true` quando:
- uma borda do período cai num buraco de coleta maior que 30 min;
- os dados começam depois do início do período;
- a última leitura tem mais de 30 min.

No app, esses valores aparecem com o ícone ⓘ, que explica o motivo ao ser tocado. No gráfico, as barras estimadas ficam mais claras.

## 2. App Android

1. Instale o **Android Studio** (ele já traz o JDK e o SDK).
2. **File → Open** e selecione a pasta `android/`. Aguarde o Gradle sincronizar.
   Se o Studio sugerir atualizar o AGP ou o Gradle, pode aceitar.
3. Conecte o celular com a **depuração USB** ativada e clique em **Run ▶**.
4. Na primeira abertura, informe a **URL do Worker**, o **APP_TOKEN** e a **tarifa** (R$/kWh, com impostos; veja na conta de luz: valor total ÷ kWh).

Telas: potência atual, consumo de hoje e de ontem, gráfico e lista dos últimos 30 dias, e seletor de período com total em kWh e em R$.

## 3. Atualizações do app (sem Play Store)

O APK fica guardado no Cloudflare KV, e o app verifica se há versão nova a cada abertura.

- **Obrigatória (padrão):** o aviso não fecha até o usuário atualizar.
- **Opcional (`-Opcional`):** o usuário pode tocar em "Depois".
- O Android sempre pede que o usuário toque em **Instalar**. Na primeira vez, ele também precisa liberar "Instalar apps desconhecidos" para o app Consumo, que o próprio app abre para ele.

**Para publicar uma versão:**
1. Aumente `versionCode` (e `versionName`) em `android/app/build.gradle.kts`.
2. Na pasta `android`, rode:
   ```powershell
   .\publicar.ps1 -Notas "O que mudou"
   ```

**Primeira instalação de um novo usuário:** envie o link de convite, que o `publicar.ps1` mostra no final da publicação. Ele abre uma página com o botão "Baixar app" e as instruções. A URL do servidor e o token já vêm embutidos no APK: a pessoa instala e usa, sem configurar nada. Dentro do app, em **Config → Compartilhar app**, o mesmo link pode ser enviado direto.

O link leva uma chave secreta (`DOWNLOAD_KEY`). Para invalidar links antigos, troque esse segredo no Worker e o `downloadKey` em `publish.properties`.

**Arquivos locais (fora do git):**
- `android/keystore/release.jks` e `android/keystore.properties`: a **chave de assinatura**. ⚠️ **Faça backup.** Sem ela, nenhuma versão nova consegue atualizar o app instalado, e cada aparelho teria que desinstalar e instalar de novo.
- `android/publish.properties`: URL do servidor, `appToken` (embutido no app), `downloadKey` (link de convite) e `adminToken` (publicação).

## Mapeamento do medidor (Data Model)

| Código | Significado | Escala |
|---|---|---|
| `fwd_acc_kwh` / `rev_acc_kwh` | Energia acumulada direta/reversa (kWh) | ÷100 |
| `p_tot`, `a_p`, `b_p` | Potência total e por canal (W) | ÷10 |
| `v` | Tensão (V) | ÷10 |
| `a_i`, `b_i` | Corrente (mA) | ÷1 |
| `a_pf`, `b_pf` | Fator de potência | ÷100 |
| `clr` | **Zera os dados** (nunca escrever) | — |

## Manutenção

- **Plano Trial da Tuya (IoT Core):** expira periodicamente. Renove em *platform.tuya.com → Cloud → Projeto → Overview/Purchase*. Enquanto estiver expirado, as coletas falham, mas a energia do período não se perde: ela aparece na primeira coleta depois da renovação.
- O cron roda a cada 10 min, com cache do token, o que dá cerca de 5 mil chamadas por mês à API da Tuya.
