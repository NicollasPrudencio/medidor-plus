// Worker do Cloudflare: coleta leituras dos medidores Tuya (cron) e serve a API do app Medidor+.
//
// Multi-casa: cada "casa" (home) tem um medidor e uma forma de acesso à Tuya:
//  - central: o usuário vinculou o app Smart Life ao projeto Tuya do administrador (TUYA_CLIENT_ID/SECRET);
//  - own: projeto Tuya do próprio usuário (Access ID/Secret guardados criptografados com ENC_KEY).
// O app se autentica com o token da sua casa. Cadastrar casa exige código de convite (gerado pelo admin);
// entrar numa casa existente exige código de compartilhamento (gerado por quem já usa).
//
// Robustez: o medidor expõe um contador ACUMULADO de energia. Se uma coleta falhar, nenhuma energia
// é perdida — a próxima leitura cobre o intervalo. Consumo(período) = contador(fim) − contador(início).

const DAY = 86_400_000;
const enc = new TextEncoder();

const DATA_CENTERS = {
  us: { label: "Western America (Brasil, EUA)", url: "https://openapi.tuyaus.com" },
  ueaz: { label: "Eastern America", url: "https://openapi-ueaz.tuyaus.com" },
  eu: { label: "Central Europe", url: "https://openapi.tuyaeu.com" },
  weaz: { label: "Western Europe", url: "https://openapi-weaz.tuyaeu.com" },
  in: { label: "India", url: "https://openapi.tuyain.com" },
  cn: { label: "China", url: "https://openapi.tuyacn.com" },
};
const CENTRAL_BASE = DATA_CENTERS.us.url;
const MAX_HOMES_PER_RUN = 20; // limite de subrequisições do plano gratuito do Workers

export default {
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(collectAll(env));
  },

  async fetch(req, env) {
    const url = new URL(req.url);
    const q = url.searchParams;
    const path = url.pathname;
    const bearer = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");

    // Link de convite para instalar o app: /baixar/<DOWNLOAD_KEY> (página) e .../app.apk (arquivo)
    const dl = path.match(/^\/baixar\/([^/]+)(\/app\.apk)?\/?$/);
    if (dl) {
      if (!env.DOWNLOAD_KEY || dl[1] !== env.DOWNLOAD_KEY) {
        return new Response("Link inválido ou expirado.", { status: 404 });
      }
      return dl[2] ? downloadApk(env, true) : downloadPage(env);
    }

    // Publicação de nova versão do app (usado pelo android/publicar.ps1)
    if (path === "/api/admin/apk") {
      if (!env.ADMIN_TOKEN || bearer !== env.ADMIN_TOKEN) return json({ error: "não autorizado" }, 401);
      if (req.method !== "PUT") return json({ error: "use PUT" }, 405);
      try {
        return json(await publishApk(env, req, q));
      } catch (e) {
        return json({ error: errMsg(e) }, 400);
      }
    }

    // Rotas públicas do cadastro (protegidas pelos códigos)
    try {
      if (path === "/api/datacenters") return json(DATA_CENTERS);
      if (path === "/api/codes/check" && req.method === "POST") return json(await checkCodeKind(env, q.get("code")));
      if (path === "/api/join" && req.method === "POST") return json(await joinHome(env, q.get("code")));
      if (path === "/api/register/devices" && req.method === "POST") return json(await registerDevices(env, await req.json()));
      if (path === "/api/register" && req.method === "POST") return json(await registerHome(env, await req.json()));
    } catch (e) {
      return json({ error: errMsg(e) }, 400);
    }

    const home = await authHome(env, bearer);
    if (!home) return json({ error: "não autorizado" }, 401);
    // env da requisição com a casa autenticada (bindings continuam acessíveis pelo protótipo)
    const henv = Object.create(env);
    henv.HOME = home;
    const admin = () => {
      if (!home.tokenAdmin) throw new Error("apenas o administrador");
    };

    try {
      switch (path) {
        case "/api/version":
          return json(await appVersion(env));
        case "/api/apk":
          return await downloadApk(env);
        case "/api/home":
          return json(await homeInfo(henv));
        case "/api/home/share":
          return json(await createCode(env, "share", home.id, 7, 20));
        case "/api/home/plan":
          return json(await setPlan(henv, q.get("expires")));
        case "/api/home/name":
          return json(await setName(henv, q.get("name")));
        case "/api/home/connection":
          if (req.method !== "PUT") return json({ error: "use PUT" }, 405);
          return json(await switchToOwn(henv, await req.json()));
        case "/api/admin/invites":
          admin();
          return json(await createCode(env, "invite", null, 30, 1));
        case "/api/admin/homes":
          admin();
          return json(await listHomes(env));
        case "/api/now":
          return json(await now(henv));
        case "/api/daily":
          return json(await daily(henv, q.get("from"), q.get("to")));
        case "/api/period":
          return json(await period(henv, Number(q.get("start")), Number(q.get("end"))));
        case "/api/series":
          return json(await seriesApi(henv, Number(q.get("start")), Number(q.get("end")), q.get("step")));
        case "/api/power":
          return json(await powerApi(henv, Number(q.get("start")), Number(q.get("end"))));
        case "/api/sessions":
          return json(await sessionsApi(henv, Number(q.get("start")), Number(q.get("end"))));
        case "/api/bandeiras":
          if (req.method === "PUT") return json(await setBandeira(env, q.get("month"), q.get("cor")));
          return json(await getBandeiras(env));
        case "/api/collect":
          return json(await collect(henv));
        default:
          return json({ error: "rota não encontrada" }, 404);
      }
    } catch (e) {
      if (isExpired(e)) {
        await setStatus(env, home.id, "expired", errMsg(e));
        const msg = home.mode === "own"
          ? "O plano gratuito da sua conta Tuya expirou. Renove em platform.tuya.com — veja Ajuda → Renovar o plano da Tuya."
          : "O serviço da Tuya está temporariamente indisponível. O administrador do Medidor+ já foi avisado.";
        return json({ error: msg, code: "tuya_expired" }, 503);
      }
      return json({ error: errMsg(e) }, 500);
    }
  },
};

const errMsg = (e) => String(e?.message ?? e);

// ---------- Coleta ----------

async function collect(env, h = env.HOME) {
  const p = await readDevice(env, h);
  const reading = toReading(p, h.codes);
  await insertReading(env, h.id, reading);
  return reading;
}

// Cron: coleta as casas que estão há mais tempo sem coleta (até MAX_HOMES_PER_RUN por execução).
async function collectAll(env) {
  const { results } = await env.DB.prepare(
    "SELECT id FROM homes ORDER BY COALESCE(last_collect, 0) LIMIT ?"
  )
    .bind(MAX_HOMES_PER_RUN)
    .all();
  for (const { id } of results) {
    const h = await loadHome(env, id);
    try {
      await collect(env, h);
      await setStatus(env, id, "ok", null);
    } catch (e) {
      await setStatus(env, id, isExpired(e) ? "expired" : "error", errMsg(e));
    }
  }
}

async function setStatus(env, id, status, error) {
  const t = Date.now();
  await env.DB.prepare(
    `UPDATE homes SET status = ?1, last_error = ?2, last_collect = ?3,
       last_ok = CASE WHEN ?1 = 'ok' THEN ?3 ELSE last_ok END WHERE id = ?4`
  )
    .bind(status, error, t, id)
    .run();
}

/** Converte as propriedades do medidor numa leitura, usando os códigos detectados para o modelo. */
function toReading(p, codes) {
  const pts = codes.energy.map((e) => p[e.code]).filter(Boolean);
  if (!pts.length) throw new Error("o medidor não retornou o contador de energia");
  const val = (spec) => (spec && p[spec.code] ? p[spec.code].value / 10 ** spec.scale : null);
  const kwh = codes.energy.reduce((s, e) => s + (val(e) ?? 0), 0); // soma direta+reversa = imune à garra invertida
  const w = val(codes.power);
  const v = val(codes.voltage);
  return {
    ts: Math.max(...pts.map((x) => x.time)), // momento em que o medidor reportou o valor
    energy: Math.round(kwh * 100), // centésimos de kWh
    power: w == null ? 0 : Math.round(Math.abs(w) * 10), // décimos de W
    voltage: v == null ? null : Math.round(v * 10), // décimos de V
    current: (codes.current ?? []).reduce((s, c) => s + (val(c) ?? 0) / (c.milli ? 1000 : 1), 0), // A
  };
}

async function insertReading(env, homeId, r) {
  // (home_id, ts) é PRIMARY KEY: leituras repetidas (medidor sem novo report) são ignoradas.
  await env.DB.prepare(
    "INSERT OR IGNORE INTO readings (home_id, ts, energy, power, voltage) VALUES (?, ?, ?, ?, ?)"
  )
    .bind(homeId, r.ts, r.energy, r.power, r.voltage)
    .run();
}

// ---------- API Tuya ----------

class TuyaError extends Error {
  constructor(code, msg) {
    super(`Tuya ${code}: ${msg}`);
    this.code = code;
  }
}

// Plano gratuito (IoT Core) vencido / serviço não assinado
// (28841107 = data center desativado e outros 28841xxx NÃO são vencimento)
const isExpired = (e) =>
  e instanceof TuyaError && (String(e.code) === "28841002" || /expired|has expired|trial.*end/i.test(e.message));

/** Credenciais Tuya da casa. */
async function credsOf(env, h) {
  if (h.mode === "central") return { id: env.TUYA_CLIENT_ID, secret: env.TUYA_CLIENT_SECRET, base: CENTRAL_BASE };
  return { id: h.client_id, secret: await decrypt(env, h.secret_enc), base: h.base_url };
}

async function readDevice(env, h = env.HOME) {
  const c = await credsOf(env, h);
  return toMap(await tuyaAuth(env, c, `/v2.0/cloud/thing/${h.device_id}/shadow/properties`));
}

const toMap = (result) => Object.fromEntries(result.properties.map((p) => [p.code, p]));

/** Chamada autenticada; se o token falhar, renova e tenta mais uma vez (exceto plano vencido). */
async function tuyaAuth(env, c, path) {
  try {
    return await tuya(c, path, await getToken(env, c));
  } catch (e) {
    if (isExpired(e)) throw e;
    return await tuya(c, path, await getToken(env, c, true));
  }
}

async function getToken(env, c, force = false) {
  const key = `token:${c.id}`;
  if (!force) {
    const row = await env.DB.prepare("SELECT value FROM meta WHERE key = ? AND expires > ?")
      .bind(key, Date.now() + 60_000)
      .first();
    if (row) return row.value;
  }
  const r = await tuya(c, "/v1.0/token?grant_type=1");
  await env.DB.prepare("INSERT OR REPLACE INTO meta (key, value, expires) VALUES (?, ?, ?)")
    .bind(key, r.access_token, Date.now() + r.expire_time * 1000)
    .run();
  return r.access_token;
}

async function tuya(c, path, token = "") {
  const t = Date.now().toString();
  const stringToSign = `GET\n${await sha256Hex("")}\n\n${path}`;
  const sign = await hmacHex(c.secret, c.id + token + t + stringToSign);
  const headers = { client_id: c.id, sign, t, sign_method: "HMAC-SHA256" };
  if (token) headers.access_token = token;

  const res = await fetch(c.base + path, { headers });
  const body = await res.json();
  if (!body.success) throw new TuyaError(body.code, body.msg);
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

// ---------- Criptografia das credenciais (AES-GCM, chave ENC_KEY em base64) ----------

const b64 = (bytes) => btoa(String.fromCharCode(...bytes));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function aesKey(env) {
  if (!env.ENC_KEY) throw new Error("ENC_KEY não configurada no servidor");
  return crypto.subtle.importKey("raw", unb64(env.ENC_KEY), "AES-GCM", false, ["encrypt", "decrypt"]);
}

async function encrypt(env, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await aesKey(env), enc.encode(text)));
  const all = new Uint8Array(iv.length + ct.length);
  all.set(iv);
  all.set(ct, iv.length);
  return b64(all);
}

async function decrypt(env, data) {
  const all = unb64(data);
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: all.slice(0, 12) }, await aesKey(env), all.slice(12));
  return new TextDecoder().decode(pt);
}

// ---------- Cálculo de consumo ----------

// Leituras que cobrem [start, end], incluindo a última antes e a primeira depois (para interpolar).
async function loadRows(env, start, end) {
  const { results } = await env.DB.prepare(
    `SELECT ts, energy FROM readings
      WHERE home_id = ?3
        AND ts >= COALESCE((SELECT MAX(ts) FROM readings WHERE home_id = ?3 AND ts <= ?1), 0)
        AND ts <= COALESCE((SELECT MIN(ts) FROM readings WHERE home_id = ?3 AND ts >= ?2), ?2)
      ORDER BY ts`
  )
    .bind(start, end, env.HOME.id)
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

// ---------- Consumo diário manual (histórico anotado antes do medidor ser integrado) ----------

async function loadManual(env, start, end) {
  const { results } = await env.DB.prepare(
    "SELECT date, kwh, estimated FROM manual_daily WHERE home_id = ? AND date BETWEEN ? AND ?"
  )
    .bind(env.HOME.id, localDate(start, env), localDate(end - 1, env))
    .all();
  return Object.fromEntries(results.map((r) => [r.date, r]));
}

// Consumo do dia local [ds, de): leituras automáticas + valor manual do período antes da 1ª leitura.
function dayConsumption(s, ds, de, manual, env) {
  if (de <= ds) return null;
  const c = consumption(s, ds, de);
  const m = manual[localDate(ds, env)];
  if (!m) return c;
  const first = s.ts.length ? s.ts[0] : Infinity;
  if (first <= ds) return c; // dia inteiro coberto pelas leituras: o medido prevalece
  const partial = first < de; // leituras começam no meio do dia: soma as duas partes
  return {
    kwh: Math.round((m.kwh + (partial ? c?.kwh ?? 0 : 0)) * 100) / 100,
    estimated: !!m.estimated || (partial && isEstimated(s.ts, de)),
    manual: true,
  };
}

const dayFloor = (t, env) => dayStart(localDate(t, env), env);

// Soma por dia local entre start e end (pedaços de dia incompletos usam só as leituras).
function rangeConsumption(s, start, end, manual, env) {
  let kwh = 0;
  let any = false;
  let estimated = false;
  let man = false;
  for (let d = dayFloor(start, env); d < end; d += DAY) {
    const a = Math.max(d, start);
    const b = Math.min(d + DAY, end);
    if (b <= a) continue;
    const c = a === d ? dayConsumption(s, a, b, manual, env) : consumption(s, a, b);
    if (!c) continue;
    kwh += c.kwh;
    any = true;
    estimated ||= c.estimated;
    man ||= !!c.manual;
  }
  return any ? { kwh: Math.round(kwh * 100) / 100, estimated, manual: man } : null;
}

// ---------- Endpoints ----------

async function now(env) {
  const p = await readDevice(env);
  const r = toReading(p, env.HOME.codes);
  await insertReading(env, env.HOME.id, r); // aproveita a consulta para gravar mais uma leitura
  await setStatus(env, env.HOME.id, "ok", null);

  const t = Date.now();
  const today = dayFloor(t, env);
  const s = series(await loadRows(env, today - DAY, t));
  const manual = await loadManual(env, today - DAY, t);
  const td = dayConsumption(s, today, t, manual, env);
  const yd = dayConsumption(s, today - DAY, today, manual, env);
  return {
    powerW: r.power / 10,
    voltageV: (r.voltage ?? 0) / 10,
    currentA: Math.round(r.current * 1000) / 1000,
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
  const manual = await loadManual(env, start, end);
  const t = Date.now();
  const days = [];
  for (let d = start; d < end; d += DAY) {
    const c = dayConsumption(s, d, Math.min(d + DAY, t), manual, env);
    days.push({
      date: localDate(d, env),
      kwh: c?.kwh ?? null,
      estimated: c?.estimated ?? false,
      manual: c?.manual ?? false,
    });
  }
  return days;
}

async function period(env, start, end) {
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    throw new Error("use start e end em milissegundos (epoch)");
  }
  const s = series(await loadRows(env, start, end));
  const manual = await loadManual(env, start, end);
  const c = rangeConsumption(s, start, Math.min(end, Date.now()), manual, env);
  return { start, end, kwh: c?.kwh ?? null, estimated: c?.estimated ?? false, manual: c?.manual ?? false };
}

// Consumo agrupado: step = hour | day | week | month (meses respeitam o calendário local).
async function seriesApi(env, start, end, step) {
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    throw new Error("use start e end em milissegundos (epoch)");
  }
  const s = series(await loadRows(env, start, end));
  const manual = step === "hour" ? {} : await loadManual(env, start, end);
  const t = Date.now();
  const out = [];
  for (let b = start; b < end && out.length < 400; ) {
    const next = nextBoundary(b, step, env);
    const stop = Math.min(next, end, t);
    // por hora só existem leituras automáticas; dia/semana/mês incluem o histórico manual
    const c = stop <= b ? null : step === "hour" ? consumption(s, b, stop) : rangeConsumption(s, b, stop, manual, env);
    out.push({ start: b, kwh: c?.kwh ?? null, estimated: c?.estimated ?? false, manual: c?.manual ?? false });
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
    "SELECT ts, power FROM readings WHERE home_id = ? AND ts BETWEEN ? AND ? ORDER BY ts"
  )
    .bind(env.HOME.id, start, end)
    .all();
  return results.map((r) => ({ ts: r.ts, w: (r.power ?? 0) / 10 }));
}

// ---------- Recargas (sessões de potência alta) ----------

const SESSION_MIN_W = 300; // potência média mínima de um intervalo entre leituras para contar como recarga
const SESSION_HIGH_W = 1000; // potência instantânea que caracteriza recarga em andamento
const SESSION_GAP = 20 * 60_000; // interrupções menores que isso não separam a recarga
const WH_MS = 3.6e9; // kWh × WH_MS / W = duração em ms

async function sessionsApi(env, start, end) {
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end - start > 400 * DAY) {
    throw new Error("use start e end em ms (máx. 400 dias)");
  }
  const { results: rows } = await env.DB.prepare(
    `SELECT ts, energy, power FROM readings
      WHERE home_id = ?3
        AND ts >= COALESCE((SELECT MAX(ts) FROM readings WHERE home_id = ?3 AND ts <= ?1), 0)
        AND ts <= COALESCE((SELECT MIN(ts) FROM readings WHERE home_id = ?3 AND ts >= ?2), ?2)
      ORDER BY ts`
  )
    .bind(start, end, env.HOME.id)
    .all();

  const sessions = [];
  let cur = null;
  for (let i = 1; i < rows.length; i++) {
    const a = rows[i - 1];
    const b = rows[i];
    const dt = b.ts - a.ts;
    if (dt <= 0) continue;
    const de = b.energy >= a.energy ? b.energy - a.energy : b.energy; // contador zerado
    const kwh = de / 100;
    const avgW = (kwh * WH_MS) / dt;
    const pa = (a.power ?? 0) / 10;
    const pb = (b.power ?? 0) / 10;
    if (kwh <= 0 || (avgW < SESSION_MIN_W && pa < SESSION_HIGH_W && pb < SESSION_HIGH_W)) continue;

    // Ajusta início/fim dentro do intervalo usando a potência observada
    let s0 = a.ts;
    let s1 = b.ts;
    let inferred = false;
    if (pa < SESSION_HIGH_W && pb >= SESSION_HIGH_W) s0 = Math.max(a.ts, b.ts - (kwh * WH_MS) / pb);
    else if (pa >= SESSION_HIGH_W && pb < SESSION_HIGH_W) s1 = Math.min(b.ts, a.ts + (kwh * WH_MS) / pa);
    else if (pa < SESSION_HIGH_W && pb < SESSION_HIGH_W) inferred = true; // recarga inteira entre duas leituras
    // só é estimativa se houve energia relevante dentro de um intervalo longo sem leituras
    const est = dt > 30 * 60_000 && kwh >= 0.5 && (inferred || s0 !== a.ts || s1 !== b.ts);

    if (cur && s0 - cur.end <= SESSION_GAP) {
      cur.end = s1;
      cur.kwh += kwh;
      cur.peakW = Math.max(cur.peakW, pa, pb);
      cur.estimated ||= est;
    } else {
      if (cur) sessions.push(cur);
      cur = { start: s0, end: s1, kwh, peakW: Math.max(pa, pb), estimated: est };
    }
  }
  if (cur) sessions.push(cur);

  const last = rows.at(-1);
  const out = sessions
    .filter((s) => s.kwh >= 0.1 && s.end > start && s.start < end)
    .map((s) => ({
      start: Math.round(s.start),
      end: Math.round(s.end),
      kwh: Math.round(s.kwh * 100) / 100,
      avgW: Math.round((s.kwh * WH_MS) / Math.max(s.end - s.start, 60_000)),
      peakW: Math.round(s.peakW),
      estimated: s.estimated,
      ongoing: !!last && s.end === last.ts && (last.power ?? 0) / 10 >= SESSION_HIGH_W,
      manual: false,
    }));

  // Recargas do histórico anotado (sem horário), nos dias ainda não cobertos pelas leituras
  const firstTs = rows.length ? rows[0].ts : Infinity;
  const { results: man } = await env.DB.prepare(
    "SELECT date, kwh, note FROM manual_daily WHERE home_id = ? AND estimated = 0 AND date BETWEEN ? AND ?"
  )
    .bind(env.HOME.id, localDate(start, env), localDate(end - 1, env))
    .all();
  for (const m of man) {
    const ds = dayStart(m.date, env);
    if (ds >= firstTs) continue; // dia inteiro com leituras: recargas já detectadas
    // "26,50 + 7,02" → duas recargas; senão uma com o total do dia
    const parts = /^[\d\s,.+]+$/.test(m.note ?? "")
      ? m.note.split("+").map((x) => Number(x.trim().replace(",", "."))).filter((x) => x > 0)
      : [m.kwh];
    for (const p of parts) {
      out.push({ start: ds, end: null, kwh: p, avgW: null, peakW: null, estimated: false, ongoing: false, manual: true });
    }
  }
  return out.sort((x, y) => x.start - y.start);
}

// ---------- Bandeira tarifária por mês ----------

const BANDEIRAS = ["verde", "amarela", "vermelha1", "vermelha2"];

async function getBandeiras(env) {
  const { results } = await env.DB.prepare("SELECT month, cor FROM bandeiras").all();
  return Object.fromEntries(results.map((r) => [r.month, r.cor]));
}

async function setBandeira(env, month, cor) {
  if (!/^\d{4}-\d{2}$/.test(month ?? "")) throw new Error("use month=AAAA-MM");
  if (!cor) {
    await env.DB.prepare("DELETE FROM bandeiras WHERE month = ?").bind(month).run();
  } else {
    if (!BANDEIRAS.includes(cor)) throw new Error(`cor inválida (${BANDEIRAS.join(", ")})`);
    await env.DB.prepare("INSERT OR REPLACE INTO bandeiras (month, cor) VALUES (?, ?)").bind(month, cor).run();
  }
  return getBandeiras(env);
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
         <li>Toque em <b>Instalar</b> e abra o app.</li>
         <li>No app, use o <b>código</b> que você recebeu junto com este link (de acesso à casa ou de convite).</li>
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

// ---------- Casas (multi-usuário) ----------

async function authHome(env, token) {
  if (!token) return null;
  let id = null;
  let admin = false;
  let owner = false;
  if (env.APP_TOKEN && token === env.APP_TOKEN) {
    id = "casa1"; // instalações da primeira versão (aparelho do administrador)
    admin = true;
  } else {
    const row = await env.DB.prepare("SELECT home_id, is_admin, is_owner FROM home_tokens WHERE token = ?").bind(token).first();
    id = row?.home_id;
    admin = !!row?.is_admin;
    owner = !!row?.is_owner;
  }
  const h = id ? await loadHome(env, id) : null;
  if (h) {
    h.tokenAdmin = admin; // administrador é o aparelho, não a casa
    h.tokenOwner = owner; // responsável: quem conectou o projeto Tuya da casa (renova o plano)
    h.token = token;
  }
  return h;
}

async function loadHome(env, id) {
  const h = await env.DB.prepare("SELECT * FROM homes WHERE id = ?").bind(id).first();
  if (!h) return null;
  h.codes = JSON.parse(h.codes);
  return h;
}

const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sem 0/O e 1/I para não confundir
function randomCode(n) {
  const bytes = crypto.getRandomValues(new Uint8Array(n));
  return [...bytes].map((b) => ALPHABET[b % ALPHABET.length]).join("");
}
const randomToken = () => hex(crypto.getRandomValues(new Uint8Array(24)));

/** Código de convite (cadastrar casa) ou de compartilhamento (entrar numa casa). */
async function createCode(env, kind, homeId, days, uses) {
  const code = randomCode(8);
  const expires = Date.now() + days * DAY;
  await env.DB.prepare(
    "INSERT INTO codes (code, kind, home_id, expires, uses_left, created_at) VALUES (?, ?, ?, ?, ?, ?)"
  )
    .bind(code, kind, homeId, expires, uses, Date.now())
    .run();
  return { code, expires };
}

async function checkCode(env, code, kind) {
  const c = String(code ?? "").trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
  const row = await env.DB.prepare(
    "SELECT * FROM codes WHERE code = ? AND kind = ? AND expires > ? AND uses_left > 0"
  )
    .bind(c, kind, Date.now())
    .first();
  if (!row) throw new Error(kind === "invite" ? "Código de convite inválido ou expirado." : "Código de acesso inválido ou expirado.");
  return row;
}

const useCode = (env, code) =>
  env.DB.prepare("UPDATE codes SET uses_left = uses_left - 1 WHERE code = ?").bind(code).run();

async function newToken(env, homeId, admin = false, owner = false) {
  const token = randomToken();
  await env.DB.prepare("INSERT INTO home_tokens (token, home_id, is_admin, is_owner, created_at) VALUES (?, ?, ?, ?, ?)")
    .bind(token, homeId, admin ? 1 : 0, owner ? 1 : 0, Date.now())
    .run();
  return token;
}

/** Entrar numa casa existente com o código de compartilhamento. */
async function joinHome(env, code) {
  const row = await checkCode(env, code, "share");
  await useCode(env, row.code);
  const token = await newToken(env, row.home_id, !!row.admin);
  const h = await loadHome(env, row.home_id);
  h.tokenAdmin = !!row.admin;
  return { token, home: await homeInfo(Object.assign(Object.create(env), { HOME: h })) };
}

/** Credenciais informadas no cadastro (modo own) ou as do projeto central. */
function regCreds(env, body) {
  if (body.mode === "central") return { id: env.TUYA_CLIENT_ID, secret: env.TUYA_CLIENT_SECRET, base: CENTRAL_BASE };
  const dc = DATA_CENTERS[body.dataCenter];
  if (!dc) throw new Error("Escolha o data center do seu projeto Tuya.");
  const id = String(body.clientId ?? "").trim();
  const secret = String(body.secret ?? "").trim();
  if (!id || !secret) throw new Error("Informe o Access ID e o Access Secret do projeto Tuya.");
  return { id, secret, base: dc.url };
}

/** Modo own: testa as credenciais e lista os aparelhos do projeto do usuário. */
async function registerDevices(env, body) {
  await checkCode(env, body.invite, "invite");
  const c = regCreds(env, { ...body, mode: "own" });
  let token;
  try {
    token = await tuya(c, "/v1.0/token?grant_type=1");
  } catch (e) {
    throw new Error(`A Tuya recusou o Access ID/Secret (${errMsg(e)}). Confira os valores e o data center.`);
  }
  const r = await tuya(c, "/v2.0/cloud/thing/device?page_size=20", token.access_token);
  const list = Array.isArray(r) ? r : r?.list ?? r?.devices ?? [];
  return list.map((d) => ({
    id: d.id,
    name: d.name ?? d.customName ?? d.product_name ?? d.id,
    product: d.product_name ?? d.productName ?? "",
    online: d.is_online ?? d.isOnline ?? d.online ?? null,
  }));
}

// Códigos conhecidos de medidores Tuya (o primeiro encontrado vence)
const FWD_CODES = ["fwd_acc_kwh", "forward_energy_total", "total_forward_energy", "forward_energy", "energy_forward_a"];
const REV_CODES = ["rev_acc_kwh", "reverse_energy_total", "total_reverse_energy", "reverse_energy", "energy_reverse_a"];
const POWER_CODES = ["p_tot", "total_power", "cur_power", "power", "active_power", "power_a"];
const VOLT_CODES = ["v", "cur_voltage", "voltage", "voltage_a"];
const CURRENT_GROUPS = [["a_i", "b_i"], ["cur_current"], ["current"], ["current_a"]];

/** Descobre quais propriedades do modelo são energia acumulada, potência, tensão e corrente. */
function detectCodes(props, solar) {
  const by = Object.fromEntries(props.map((p) => [p.code, p]));
  const spec = (code) => {
    const p = by[code];
    if (!p) return null;
    const ts = p.typeSpec ?? {};
    return { code, scale: Number(ts.scale ?? 0), unit: String(ts.unit ?? "") };
  };
  const first = (list) => list.map(spec).find(Boolean) ?? null;
  const fwd = first(FWD_CODES);
  // Com geração solar, a energia "reversa" é a injetada na rede: não pode somar como consumo.
  const rev = solar ? null : first(REV_CODES);
  const energy = [fwd, rev].filter(Boolean).map(({ code, scale }) => ({ code, scale }));
  if (!energy.length) return null;
  let current = [];
  for (const group of CURRENT_GROUPS) {
    const specs = group.map(spec).filter(Boolean);
    if (specs.length) {
      current = specs.map(({ code, scale, unit }) => ({ code, scale, milli: /^ma$/i.test(unit) }));
      break;
    }
  }
  const strip = (s) => (s ? { code: s.code, scale: s.scale } : null);
  return { energy, power: strip(first(POWER_CODES)), voltage: strip(first(VOLT_CODES)), current };
}

async function modelProps(env, c, deviceId) {
  const r = await tuyaAuth(env, c, `/v2.0/cloud/thing/${deviceId}/model`);
  const model = typeof r.model === "string" ? JSON.parse(r.model) : r.model;
  return (model?.services ?? []).flatMap((s) => s.properties ?? []);
}

/** Cria a casa: valida convite, aparelho e compatibilidade; devolve o token do app. */
async function registerHome(env, body) {
  const invite = await checkCode(env, body.invite, "invite");
  // Casa nova sempre com o projeto Tuya do próprio dono: cada casa renova o próprio plano
  if (body.mode !== "own") throw new Error("Cadastre o medidor com o seu próprio projeto Tuya (atualize o app).");
  const mode = "own";
  const c = regCreds(env, { ...body, mode });
  const deviceId = String(body.deviceId ?? "").trim();
  if (!/^[a-z0-9]{10,32}$/i.test(deviceId)) throw new Error("ID do aparelho inválido.");
  const name = String(body.name ?? "").trim().slice(0, 40) || "Minha casa";

  const exists = await env.DB.prepare("SELECT id FROM homes WHERE device_id = ?").bind(deviceId).first();
  if (exists) throw new Error("Este medidor já está cadastrado. Peça o código de acesso a quem já usa.");

  let props;
  try {
    props = await modelProps(env, c, deviceId);
  } catch (e) {
    throw new Error(
      mode === "central"
        ? `Medidor não encontrado (${errMsg(e)}). Confira o ID e se o QR de vínculo foi escaneado no Smart Life.`
        : `Medidor não encontrado no seu projeto (${errMsg(e)}).`
    );
  }
  const codes = detectCodes(props, !!body.solar);
  if (!codes) throw new Error("Este aparelho não informa energia acumulada (kWh) — não é compatível com o Medidor+.");

  // Testa a leitura antes de salvar
  const p = toMap(await tuyaAuth(env, c, `/v2.0/cloud/thing/${deviceId}/shadow/properties`));
  const reading = toReading(p, codes);

  const plan = /^\d{4}-\d{2}-\d{2}$/.test(body.planExpires ?? "") ? body.planExpires : null;
  const id = randomCode(10).toLowerCase();
  await env.DB.prepare(
    `INSERT INTO homes (id, name, mode, client_id, secret_enc, base_url, device_id, codes, plan_expires,
       is_admin, status, last_ok, last_collect, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ok', ?, ?, ?)`
  )
    .bind(
      id, name, mode,
      mode === "own" ? c.id : null,
      mode === "own" ? await encrypt(env, c.secret) : null,
      c.base, deviceId, JSON.stringify(codes), mode === "own" ? plan : null,
      Date.now(), Date.now(), Date.now()
    )
    .run();
  await useCode(env, invite.code);
  await insertReading(env, id, reading);
  const token = await newToken(env, id, false, true);
  const created = await loadHome(env, id);
  created.tokenOwner = true;
  return { token, home: await homeInfo(Object.assign(Object.create(env), { HOME: created })) };
}

async function centralPlan(env) {
  return (await env.DB.prepare("SELECT value FROM meta WHERE key = 'central_plan_expires'").first())?.value ?? null;
}

async function homeInfo(env) {
  const h = env.HOME;
  return {
    id: h.id,
    name: h.name,
    mode: h.mode,
    deviceId: h.device_id,
    // casa central: o plano é o do administrador (só o admin precisa renovar)
    planExpires: h.mode === "own" ? h.plan_expires : await centralPlan(env),
    status: h.status,
    lastOk: h.last_ok,
    lastError: h.last_error,
    isAdmin: !!h.tokenAdmin,
    isOwner: !!h.tokenOwner,
  };
}

/** Data de vencimento do plano da Tuya: da própria casa (own) ou do projeto central (admin). */
async function setPlan(env, expires) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(expires ?? "")) throw new Error("use expires=AAAA-MM-DD");
  const h = env.HOME;
  if (h.mode === "own") {
    if (!h.tokenOwner) throw new Error("O plano desta casa é renovado pelo responsável (quem conectou o projeto Tuya).");
    await env.DB.prepare("UPDATE homes SET plan_expires = ? WHERE id = ?").bind(expires, h.id).run();
  } else if (h.tokenAdmin) {
    await env.DB.prepare("INSERT OR REPLACE INTO meta (key, value, expires) VALUES ('central_plan_expires', ?, 0)")
      .bind(expires)
      .run();
  } else {
    throw new Error("O plano desta casa é renovado pelo administrador.");
  }
  // Após renovar, tenta coletar para atualizar o status
  try {
    await collect(env);
    await setStatus(env, h.id, "ok", null);
  } catch (e) {
    await setStatus(env, h.id, isExpired(e) ? "expired" : "error", errMsg(e));
  }
  env.HOME = Object.assign(await loadHome(env, h.id), { tokenAdmin: h.tokenAdmin, tokenOwner: h.tokenOwner, token: h.token });
  return homeInfo(env);
}

/** Diz se um código é de acesso (share) ou convite (invite), sem consumi-lo. */
async function checkCodeKind(env, code) {
  const c = String(code ?? "").trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
  const row = await env.DB.prepare("SELECT kind FROM codes WHERE code = ? AND expires > ? AND uses_left > 0")
    .bind(c, Date.now())
    .first();
  if (!row) throw new Error("Código inválido ou expirado. Confira as letras e números.");
  return { kind: row.kind };
}

/**
 * Passa a casa para o projeto Tuya do próprio usuário (mantém o histórico).
 * Só funciona se o projeto informado tiver acesso a este medidor — prova de que é do dono.
 */
async function switchToOwn(env, body) {
  const h = env.HOME;
  const c = regCreds(env, { ...body, mode: "own" });
  try {
    await tuya(c, "/v1.0/token?grant_type=1");
  } catch (e) {
    throw new Error(`A Tuya recusou o Access ID/Secret (${errMsg(e)}). Confira os valores e o data center.`);
  }
  let p;
  try {
    p = toMap(await tuyaAuth(env, c, `/v2.0/cloud/thing/${h.device_id}/shadow/properties`));
  } catch (e) {
    throw new Error(
      `O medidor desta casa não aparece no seu projeto (${errMsg(e)}). Confira o passo "Vincule seu app Smart Life".`
    );
  }
  toReading(p, h.codes); // garante que a leitura funciona com o projeto novo
  const plan = /^\d{4}-\d{2}-\d{2}$/.test(body.planExpires ?? "") ? body.planExpires : null;
  await env.DB.prepare(
    `UPDATE homes SET mode = 'own', client_id = ?, secret_enc = ?, base_url = ?, plan_expires = ?,
       status = 'ok', last_error = NULL, last_ok = ? WHERE id = ?`
  )
    .bind(c.id, await encrypt(env, c.secret), c.base, plan, Date.now(), h.id)
    .run();
  // quem conectou o projeto passa a ser o responsável pela renovação
  if (h.token && h.token !== env.APP_TOKEN) {
    await env.DB.prepare("UPDATE home_tokens SET is_owner = CASE WHEN token = ? THEN 1 ELSE 0 END WHERE home_id = ?")
      .bind(h.token, h.id)
      .run();
  }
  env.HOME = Object.assign(await loadHome(env, h.id), { tokenAdmin: h.tokenAdmin, tokenOwner: true, token: h.token });
  return homeInfo(env);
}

async function setName(env, name) {
  const n = String(name ?? "").trim().slice(0, 40);
  if (!n) throw new Error("Informe um nome.");
  await env.DB.prepare("UPDATE homes SET name = ? WHERE id = ?").bind(n, env.HOME.id).run();
  env.HOME.name = n;
  return homeInfo(env);
}

async function listHomes(env) {
  const { results } = await env.DB.prepare(
    "SELECT id, name, mode, status, last_ok, last_error, plan_expires FROM homes ORDER BY created_at"
  ).all();
  return { centralPlanExpires: await centralPlan(env), homes: results };
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
