-- Leituras do contador acumulado do medidor
CREATE TABLE IF NOT EXISTS readings (
  ts      INTEGER PRIMARY KEY, -- epoch ms do report do medidor
  energy  INTEGER NOT NULL,    -- fwd_acc_kwh + rev_acc_kwh (centésimos de kWh)
  power   INTEGER,             -- |p_tot| em décimos de W
  voltage INTEGER              -- v em décimos de V
);

-- Cache do token da Tuya
CREATE TABLE IF NOT EXISTS meta (
  key     TEXT PRIMARY KEY,
  value   TEXT NOT NULL,
  expires INTEGER NOT NULL
);
