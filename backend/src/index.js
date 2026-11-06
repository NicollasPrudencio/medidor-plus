// Worker do Cloudflare: coleta leituras do medidor Tuya (cron) e serve a API do app.
//
// Robustez: o medidor expõe um contador ACUMULADO de energia (fwd_acc_kwh + rev_acc_kwh).
// Se uma coleta falhar, nenhuma energia é perdida — a próxima leitura cobre o intervalo.
// Consumo(período) = contador(fim) − contador(início), com interpolação nas bordas.

const TUYA_BASE = "https://openapi.tuyaus.com"; // Western America Data Center
const DAY = 86_400_000;
const enc = new TextEncoder();

export default {
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(collect(env));
  },

  async fetch(req, env) {
    const url = new URL(req.url);
    const q = url.searchParams;
    const auth = req.headers.get("Authorization");

    // Link de convite para instalar o app: /baixar/<DOWNLOAD_KEY> (página) e .../app.apk (arquivo)
    const dl = url.pathname.match(/^\/baixar\/([^/]+)(\/app\.apk)?\/?$/);
    if (dl) {
      if (!env.DOWNLOAD_KEY || dl[1] !== env.DOWNLOAD_KEY) {
        return new Response("Link inválido ou expirado.", { status: 404 });
      }
      return dl[2] ? downloadApk(env, true) : downloadPage(env);
    }

    // Publicação de nova versão do app (usado pelo android/publicar.ps1)
    if (url.pathname === "/api/admin/apk") {
      if (!env.ADMIN_TOKEN || auth !== `Bearer ${env.ADMIN_TOKEN}`) return json({ error: "não autorizado" }, 401);
      if (req.method !== "PUT") return json({ error: "use PUT" }, 405);
      try {
        return json(await publishApk(env, req, q));
      } catch (e) {
        return json({ error: String(e?.message ?? e) }, 400);
      }
    }

    if (!env.APP_TOKEN || auth !== `Bearer ${env.APP_TOKEN}`) {
      return json({ error: "não autorizado" }, 401);
    }
    try {
      switch (url.pathname) {
        case "/api/version":
          return json(await appVersion(env));
        case "/api/apk":
          return await downloadApk(env);
        case "/api/now":
          return json(await now(env));
        case "/api/daily":
          return json(await daily(env, q.get("from"), q.get("to")));
        case "/api/period":
          return json(await period(env, Number(q.get("start")), Number(q.get("end"))));
        case "/api/series":
          return json(await seriesApi(env, Number(q.get("start")), Number(q.get("end")), q.get("step")));
        case "/api/power":
          return json(await powerApi(env, Number(q.get("start")), Number(q.get("end"))));
        case "/api/collect":
          return json(await collect(env));
        default:
          return json({ error: "rota não encontrada" }, 404);
      }
    } catch (e) {
      return json({ error: String(e?.message ?? e) }, 500);
    }
  },
};

// ---------- Coleta ----------

async function collect(env) {
  const reading = toReading(await readDevice(env));
  await insertReading(env, reading);
  return reading;
}

function toReading(p) {
  const fwd = p.fwd_acc_kwh;
  const rev = p.rev_acc_kwh;
  if (!fwd || !rev) throw new Error("medidor não retornou fwd_acc_kwh / rev_acc_kwh");
  return {
    ts: Math.max(fwd.time, rev.time), // momento em que o medidor reportou o valor
    energy: fwd.value + rev.value, // centésimos de kWh (escala 2); soma = imune à garra invertida
    power: Math.abs(p.p_tot?.value ?? 0), // décimos de W
    voltage: p.v?.value ?? null, // décimos de V
  };
}

async function insertReading(env, r) {
  // ts é PRIMARY KEY: leituras repetidas (medidor sem novo report) são ignoradas.
  await env.DB.prepare(
    "INSERT OR IGNORE INTO readings (ts, energy, power, voltage) VALUES (?, ?, ?, ?)"
  )
    .bind(r.ts, r.energy, r.power, r.voltage)
    .run();
}

// ---------- API Tuya ----------

async function readDevice(env) {
  const path = `/v2.0/cloud/thing/${env.TUYA_DEVICE_ID}/shadow/properties`;
  try {
    return toMap(await tuya(env, path, await getToken(env)));
  } catch {
    // token expirado/inválido ou falha transitória: renova o token e tenta mais uma vez
    return toMap(await tuya(env, path, await getToken(env, true)));
  }
}

const toMap = (result) => Object.fromEntries(result.properties.map((p) => [p.code, p]));

async function getToken(env, force = false) {
  if (!force) {
    const row = await env.DB.prepare("SELECT value FROM meta WHERE key = 'token' AND expires > ?")
      .bind(Date.now() + 60_000)
      .first();
    if (row) return row.value;
  }
  const r = await tuya(env, "/v1.0/token?grant_type=1");
  await env.DB.prepare("INSERT OR REPLACE INTO meta (key, value, expires) VALUES ('token', ?, ?)")
    .bind(r.access_token, Date.now() + r.expire_time * 1000)
    .run();
  return r.access_token;
}

async function tuya(env, path, token = "") {
  const t = Date.now().toString();
  const stringToSign = `GET\n${await sha256Hex("")}\n\n${path}`;
  const sign = await hmacHex(env.TUYA_CLIENT_SECRET, env.TUYA_CLIENT_ID + token + t + stringToSign);
  const headers = { client_id: env.TUYA_CLIENT_ID, sign, t, sign_method: "HMAC-SHA256" };
  if (token) headers.access_token = token;

  const res = await fetch(TUYA_BASE + path, { headers });
  const body = await res.json();
  if (!body.success) throw new Error(`Tuya ${body.code}: ${body.msg}`);
  return body.result;
}

async function sha256Hex(msg) {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(msg)));
}

async function hmacHex(secret, msg) {
  const key = await crypto.subtle.importKey(
    "raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  return hex(await crypto.subtle.sign("HMAC", key, enc.encode(msg))).toUpperCase();
}

const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");

// ---------- Cálculo de consumo ----------

// Leituras que cobrem [start, end], incluindo a última antes e a primeira depois (para interpolar).
async function loadRows(env, start, end) {
  const { results } = await env.DB.prepare(
    `SELECT ts, energy FROM readings
      WHERE ts >= COALESCE((SELECT MAX(ts) FROM readings WHERE ts <= ?1), 0)
        AND ts <= COALESCE((SELECT MIN(ts) FROM readings WHERE ts >= ?2), ?2)
      ORDER BY ts`
  )
    .bind(start, end)
    .all();
  return results;
}

// Série monotônica: se o contador cair (dados zerados no medidor), conta a partir do novo valor.
function series(rows) {
  const ts = [];
  const m = [];
  let acc = 0;
  rows.forEach((r, i) => {
    if (i > 0) {
      const d = r.energy - rows[i - 1].energy;
      acc += d >= 0 ? d : r.energy;
    }
    ts.push(r.ts);
    m.push(acc);
  });
  return { ts, m };
}

// Buraco entre leituras acima disso = borda interpolada é estimativa (cron roda a cada 10 min).
const GAP = 30 * 60_000;

// Índices lo/hi com ts[lo] <= t <= ts[hi] (t dentro da série).
function bracket(ts, t) {
  let lo = 0;
  let hi = ts.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (ts[mid] <= t) lo = mid;
    else hi = mid;
  }
  return [lo, hi];
}

function valueAt({ ts, m }, t) {
  const n = ts.length;
  if (t <= ts[0]) return m[0];
  if (t >= ts[n - 1]) return m[n - 1];
  const [lo, hi] = bracket(ts, t);
  return m[lo] + ((m[hi] - m[lo]) * (t - ts[lo])) / (ts[hi] - ts[lo]);
}

// A borda t não tem leitura próxima o bastante para ser considerada exata?
function isEstimated(ts, t) {
  const n = ts.length;
  if (t < ts[0]) return true; // dados começam depois do início do período
  if (t >= ts[n - 1]) return t - ts[n - 1] > GAP; // última leitura antiga
  const [lo, hi] = bracket(ts, t);
  if (ts[lo] === t || ts[hi] === t) return false;
  return ts[hi] - ts[lo] > GAP; // coletas perdidas em volta da borda
}

// {kwh, estimated} entre start e end; null se não há leituras cobrindo o intervalo.
function consumption(s, start, end) {
  const n = s.ts.length;
  if (!n || end <= s.ts[0] || start >= s.ts[n - 1]) return null;
  return {
    kwh: Math.round(valueAt(s, end) - valueAt(s, start)) / 100,
    estimated: isEstimated(s.ts, start) || isEstimated(s.ts, end),
  };
}

// ---------- Endpoints ----------

async function now(env) {
  const p = await readDevice(env);
  const r = toReading(p);
  await insertReading(env, r); // aproveita a consulta para gravar mais uma leitura

  const t = Date.now();
  const today = dayStart(localDate(t, env), env);
  const s = series(await loadRows(env, today - DAY, t));
  const td = consumption(s, today, t);
  const yd = consumption(s, today - DAY, today);
  return {
    powerW: r.power / 10,
    voltageV: (r.voltage ?? 0) / 10,
    currentA: ((p.a_i?.value ?? 0) + (p.b_i?.value ?? 0)) / 1000,
    totalKwh: r.energy / 100,
    updatedAt: Math.max(...Object.values(p).map((x) => x.time)),
    todayKwh: td?.kwh ?? null,
    todayEstimated: td?.estimated ?? false,
    yesterdayKwh: yd?.kwh ?? null,
    yesterdayEstimated: yd?.estimated ?? false,
  };
}

async function daily(env, from, to) {
  const re = /^\d{4}-\d{2}-\d{2}$/;
  if (!re.test(from ?? "") || !re.test(to ?? "")) throw new Error("use from=AAAA-MM-DD&to=AAAA-MM-DD");
  const start = dayStart(from, env);
  const end = dayStart(to, env) + DAY;
  if (end <= start || end - start > 400 * DAY) throw new Error("intervalo inválido (máx. 400 dias)");

  const s = series(await loadRows(env, start, end));
  const t = Date.now();
  const days = [];
  for (let d = start; d < end; d += DAY) {
    const c = consumption(s, d, Math.min(d + DAY, t));
    days.push({ date: localDate(d, env), kwh: c?.kwh ?? null, estimated: c?.estimated ?? false });
  }
  return days;
}

async function period(env, start, end) {
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    throw new Error("use start e end em milissegundos (epoch)");
  }
  const s = series(await loadRows(env, start, end));
  const c = consumption(s, start, Math.min(end, Date.now()));
  return { start, end, kwh: c?.kwh ?? null, estimated: c?.estimated ?? false };
}

// Consumo agrupado: step = hour | day | week | month (meses respeitam o calendário local).
async function seriesApi(env, start, end, step) {
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    throw new Error("use start e end em milissegundos (epoch)");
  }
  const s = series(await loadRows(env, start, end));
  const t = Date.now();
  const out = [];
  for (let b = start; b < end && out.length < 400; ) {
    const next = nextBoundary(b, step, env);
    const stop = Math.min(next, end, t);
    const c = stop > b ? consumption(s, b, stop) : null;
    out.push({ start: b, kwh: c?.kwh ?? null, estimated: c?.estimated ?? false });
    b = next;
  }
  return out;
}

function nextBoundary(t, step, env) {
  switch (step) {
    case "hour":
      return t + 3_600_000;
    case "day":
      return t + DAY;
    case "week":
      return t + 7 * DAY;
    case "month": {
      const d = new Date(t + tzOffsetMs(env));
      return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1) - tzOffsetMs(env);
    }
    default:
      throw new Error("step inválido (hour, day, week, month)");
  }
}

// Potência instantânea registrada em cada leitura (máx. 7 dias).
async function powerApi(env, start, end) {
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end - start > 7 * DAY) {
    throw new Error("use start e end em ms (máx. 7 dias)");
  }
  const { results } = await env.DB.prepare(
    "SELECT ts, power FROM readings WHERE ts BETWEEN ? AND ? ORDER BY ts"
  )
    .bind(start, end)
    .all();
  return results.map((r) => ({ ts: r.ts, w: (r.power ?? 0) / 10 }));
}

// ---------- Atualização do app (APK guardado no KV) ----------

async function appVersion(env) {
  return (await env.APK.get("version", "json")) ?? { versionCode: 0, minVersionCode: 0 };
}

async function downloadApk(env, attachment = false) {
  const data = await env.APK.get("app.apk", "arrayBuffer");
  if (!data) return json({ error: "nenhum APK publicado" }, 404);
  const headers = {
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": String(data.byteLength),
  };
  if (attachment) {
    const v = await appVersion(env);
    headers["Content-Disposition"] = `attachment; filename="medidor-plus-${v.versionName || v.versionCode}.apk"`;
  }
  return new Response(data, { headers });
}

// Mesmo desenho do ícone do app (android/app/src/main/res/drawable/ic_launcher_*.xml)
const ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="64" height="64"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#2F6FDE"/><stop offset="1" stop-color="#1B3F9E"/></linearGradient></defs><rect x="18" y="18" width="72" height="72" rx="18" fill="url(#g)"/><path d="M37.03,70.97 A24,24 0 1,1 70.97,70.97" fill="none" stroke="#fff" stroke-opacity=".28" stroke-width="5" stroke-linecap="round"/><path d="M37.03,70.97 A24,24 0 1,1 73.66,40.23" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round"/><path d="M56.5,35 L44,54 L52.5,54 L50,67 L64,46 L55.5,46 L58,35 Z" fill="#FFC83D" stroke="#FFC83D" stroke-width="1.2" stroke-linejoin="round"/><path d="M52,71 h4 v4 h4 v4 h-4 v4 h-4 v-4 h-4 v-4 h4 Z" fill="#fff"/></svg>`;

const esc = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

async function downloadPage(env) {
  const v = await appVersion(env);
  const has = v.versionCode > 0;
  const mb = has ? (v.size / 1_048_576).toFixed(1).replace(".", ",") : "";
  const body = has
    ? `<p class="meta">Versão ${esc(v.versionName)} · ${mb} MB</p>
       ${v.notes ? `<p class="notes">${esc(v.notes)}</p>` : ""}
       <a class="btn" href="app.apk" download>Baixar app</a>
       <ol>
         <li>Toque em <b>Baixar app</b> e depois abra o arquivo baixado.</li>
         <li>Se o Android pedir, toque em <b>Configurações</b> e ative <b>Permitir desta fonte</b>. Depois volte.</li>
         <li>Toque em <b>Instalar</b> e abra o app. Ele já vem configurado.</li>
       </ol>
       <p class="foot">As próximas versões chegam pelo próprio app. Não é preciso voltar aqui.</p>`
    : `<p class="meta">Nenhuma versão publicada ainda.</p>`;

  const html = `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>Medidor+</title>
<link rel="icon" href="data:image/svg+xml,${encodeURIComponent(ICON_SVG)}">
<style>
:root{--bg:#f6f7f9;--card:#fff;--fg:#14171a;--muted:#5b6470;--accent:#2f6fde;--accent-fg:#fff;--line:#e3e6ea}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#181b21;--fg:#e8eaed;--muted:#9aa3ad;--accent:#6b9cf5;--accent-fg:#0f1115;--line:#2a2f37}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:16px}
main{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:28px 24px;max-width:420px;width:100%}
.icon{width:64px;height:64px;margin-bottom:16px;display:block}
h1{font-size:22px;margin:0 0 4px}.meta{color:var(--muted);margin:0 0 16px}.notes{background:var(--bg);border-radius:12px;padding:12px 14px;margin:0 0 20px;white-space:pre-line}
.btn{display:block;text-align:center;background:var(--accent);color:var(--accent-fg);text-decoration:none;font-weight:600;padding:14px;border-radius:12px;margin-bottom:20px}
ol{padding-left:20px;margin:0 0 16px;color:var(--fg)}li{margin-bottom:8px}.foot{color:var(--muted);font-size:14px;margin:0}
</style></head><body><main>
<div class="icon">${ICON_SVG}</div>
<h1>Medidor+</h1>
${body}
</main></body></html>`;
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}

// force=true (padrão): versões anteriores a esta ficam bloqueadas até atualizar.
async function publishApk(env, req, q) {
  const versionCode = Number(q.get("versionCode"));
  if (!Number.isInteger(versionCode) || versionCode < 1) throw new Error("versionCode inválido");
  const prev = await appVersion(env);
  // replace=true: substitui a versão publicada com o mesmo versionCode (sem disparar atualização)
  const replace = q.get("replace") === "true" && versionCode === prev.versionCode;
  if (versionCode <= prev.versionCode && !replace) {
    throw new Error(`versionCode ${versionCode} precisa ser maior que o publicado (${prev.versionCode})`);
  }
  const data = await req.arrayBuffer();
  if (data.byteLength < 10_000) throw new Error("APK vazio ou inválido");

  const force = q.get("force") !== "false";
  const info = {
    versionCode,
    versionName: q.get("versionName") || String(versionCode),
    notes: q.get("notes") ?? "",
    size: data.byteLength,
    minVersionCode: force ? versionCode : prev.minVersionCode ?? 0,
    publishedAt: Date.now(),
  };
  await env.APK.put("app.apk", data);
  await env.APK.put("version", JSON.stringify(info));
  return info;
}

// ---------- Datas (fuso fixo, padrão Brasília UTC−3) ----------

const tzOffsetMs = (env) => Number(env.TZ_OFFSET_MIN ?? -180) * 60_000;

function dayStart(dateStr, env) {
  const [y, mo, d] = dateStr.split("-").map(Number);
  return Date.UTC(y, mo - 1, d) - tzOffsetMs(env);
}

const localDate = (ms, env) => new Date(ms + tzOffsetMs(env)).toISOString().slice(0, 10);

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}
