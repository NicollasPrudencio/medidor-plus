-- Esquema do banco D1 do Medidor+ (multi-casa)

-- Casas: cada uma com um medidor e a forma de acesso à Tuya
CREATE TABLE IF NOT EXISTS homes (
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  mode         TEXT NOT NULL,           -- central (projeto do admin) | own (projeto próprio)
  client_id    TEXT,                    -- own: Access ID
  secret_enc   TEXT,                    -- own: Access Secret criptografado (AES-GCM, ENC_KEY)
  base_url     TEXT NOT NULL,           -- data center da Tuya
  device_id    TEXT NOT NULL UNIQUE,
  codes        TEXT NOT NULL,           -- JSON: códigos de energia/potência/tensão/corrente do modelo
  plan_expires TEXT,                    -- own: vencimento do plano gratuito (AAAA-MM-DD)
  is_admin     INTEGER NOT NULL DEFAULT 0,
  status       TEXT,                    -- ok | error | expired
  last_ok      INTEGER,
  last_error   TEXT,
  last_collect INTEGER,
  created_at   INTEGER NOT NULL
);

-- Tokens dos aparelhos (um por celular); is_admin = aparelho do administrador
CREATE TABLE IF NOT EXISTS home_tokens (
  token      TEXT PRIMARY KEY,
  home_id    TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  is_admin   INTEGER NOT NULL DEFAULT 0,
  is_owner   INTEGER NOT NULL DEFAULT 0  -- responsável pela casa: conectou o projeto Tuya e renova o plano
);

-- Códigos: invite (cadastrar casa) e share (entrar numa casa); admin=1 cria token de administrador
CREATE TABLE IF NOT EXISTS codes (
  code       TEXT PRIMARY KEY,
  kind       TEXT NOT NULL,
  home_id    TEXT,
  expires    INTEGER NOT NULL,
  uses_left  INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  admin      INTEGER NOT NULL DEFAULT 0
);

-- Leituras do contador acumulado de cada medidor
CREATE TABLE IF NOT EXISTS readings (
  home_id TEXT NOT NULL,
  ts      INTEGER NOT NULL,  -- epoch ms do report do medidor
  energy  INTEGER NOT NULL,  -- energia acumulada (centésimos de kWh)
  power   INTEGER,           -- |potência| em décimos de W
  voltage INTEGER,           -- tensão em décimos de V
  PRIMARY KEY (home_id, ts)
);

-- Consumo diário anotado manualmente (antes do medidor ser integrado)
CREATE TABLE IF NOT EXISTS manual_daily (
  home_id   TEXT NOT NULL,
  date      TEXT NOT NULL,             -- AAAA-MM-DD (horário de Brasília)
  kwh       REAL NOT NULL,
  estimated INTEGER NOT NULL DEFAULT 0, -- 1 = valor estimado (exibe ⓘ)
  note      TEXT,
  PRIMARY KEY (home_id, date)
);

-- Bandeira tarifária de cada mês (nacional, vale para todas as casas)
CREATE TABLE IF NOT EXISTS bandeiras (
  month TEXT PRIMARY KEY, -- AAAA-MM
  cor   TEXT NOT NULL      -- verde | amarela | vermelha1 | vermelha2
);

-- Cache de tokens da Tuya (token:<client_id>) e configurações (central_plan_expires)
CREATE TABLE IF NOT EXISTS meta (
  key     TEXT PRIMARY KEY,
  value   TEXT NOT NULL,
  expires INTEGER NOT NULL
);
