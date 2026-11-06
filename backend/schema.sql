-- Leituras do contador acumulado do medidor
CREATE TABLE IF NOT EXISTS readings (
  ts      INTEGER PRIMARY KEY, -- epoch ms do report do medidor
  energy  INTEGER NOT NULL,    -- fwd_acc_kwh + rev_acc_kwh (centésimos de kWh)
  power   INTEGER,             -- |p_tot| em décimos de W
  voltage INTEGER              -- v em décimos de V
);

-- Consumo diário anotado manualmente (antes do medidor ser integrado).
-- Usado nos dias sem leituras; no dia em que as leituras começam, soma com o medido depois.
CREATE TABLE IF NOT EXISTS manual_daily (
  date      TEXT PRIMARY KEY, -- AAAA-MM-DD (horário de Brasília)
  kwh       REAL NOT NULL,
  estimated INTEGER NOT NULL DEFAULT 0, -- 1 = valor estimado (exibe ⓘ)
  note      TEXT
);

-- Bandeira tarifária vigente em cada mês (verde, amarela, vermelha1, vermelha2)
CREATE TABLE IF NOT EXISTS bandeiras (
  month TEXT PRIMARY KEY, -- AAAA-MM
  cor   TEXT NOT NULL
);

-- Cache do token da Tuya
CREATE TABLE IF NOT EXISTS meta (
  key     TEXT PRIMARY KEY,
  value   TEXT NOT NULL,
  expires INTEGER NOT NULL
);
