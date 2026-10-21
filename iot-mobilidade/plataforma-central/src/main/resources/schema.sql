-- Plataforma IoT Integrada - esquema do banco (idempotente)

CREATE TABLE IF NOT EXISTS inquilino (
  slug VARCHAR(60) PRIMARY KEY,
  nome VARCHAR(120) NOT NULL
);

CREATE TABLE IF NOT EXISTS dominio (
  inquilino VARCHAR(60) NOT NULL,
  slug VARCHAR(60) NOT NULL,
  nome VARCHAR(120) NOT NULL,
  PRIMARY KEY (inquilino, slug)
);

CREATE TABLE IF NOT EXISTS site (
  inquilino VARCHAR(60) NOT NULL,
  slug VARCHAR(60) NOT NULL,
  nome VARCHAR(120) NOT NULL,
  PRIMARY KEY (inquilino, slug)
);

CREATE TABLE IF NOT EXISTS area (
  inquilino VARCHAR(60) NOT NULL,
  slug VARCHAR(60) NOT NULL,
  site_slug VARCHAR(60) NOT NULL,
  nome VARCHAR(120) NOT NULL,
  lat DOUBLE PRECISION,
  lon DOUBLE PRECISION,
  PRIMARY KEY (inquilino, slug)
);

CREATE TABLE IF NOT EXISTS perfil (
  id SERIAL PRIMARY KEY,
  inquilino VARCHAR(60) NOT NULL,
  slug VARCHAR(60) NOT NULL,
  dominio_slug VARCHAR(60) NOT NULL,
  nome VARCHAR(120) NOT NULL,
  intervalo_s INTEGER NOT NULL,
  UNIQUE (inquilino, slug)
);

CREATE TABLE IF NOT EXISTS perfil_grandeza (
  perfil_id INTEGER NOT NULL REFERENCES perfil (id) ON DELETE CASCADE,
  grandeza VARCHAR(60) NOT NULL,
  unidade VARCHAR(30),
  valor_min DOUBLE PRECISION,
  valor_max DOUBLE PRECISION,
  PRIMARY KEY (perfil_id, grandeza)
);

CREATE TABLE IF NOT EXISTS dispositivo (
  slug VARCHAR(60) PRIMARY KEY,
  inquilino VARCHAR(60) NOT NULL,
  dominio_slug VARCHAR(60) NOT NULL,
  site_slug VARCHAR(60) NOT NULL,
  area_slug VARCHAR(60) NOT NULL,
  perfil_slug VARCHAR(60) NOT NULL,
  fabricante VARCHAR(80),
  firmware VARCHAR(40),
  token VARCHAR(80) NOT NULL,
  ativo BOOLEAN NOT NULL DEFAULT TRUE,
  ultimo_visto TIMESTAMPTZ,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Deduplicação (Idempotent Receiver): cada id_mensagem é processado uma única vez
CREATE TABLE IF NOT EXISTS mensagem_processada (
  id_mensagem VARCHAR(64) PRIMARY KEY,
  recebida_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Série temporal (vira hipertabela do TimescaleDB quando a extensão existe)
CREATE TABLE IF NOT EXISTS telemetria (
  ts TIMESTAMPTZ NOT NULL,
  inquilino VARCHAR(60) NOT NULL,
  dispositivo VARCHAR(60) NOT NULL,
  site_slug VARCHAR(60),
  area_slug VARCHAR(60),
  dominio_slug VARCHAR(60),
  grandeza VARCHAR(60) NOT NULL,
  valor DOUBLE PRECISION NOT NULL,
  unidade VARCHAR(30),
  suspeita BOOLEAN NOT NULL DEFAULT FALSE,
  atrasada BOOLEAN NOT NULL DEFAULT FALSE,
  id_mensagem VARCHAR(64),
  via VARCHAR(60)
);
CREATE INDEX IF NOT EXISTS idx_telemetria_disp ON telemetria (dispositivo, grandeza, ts DESC);
CREATE INDEX IF NOT EXISTS idx_telemetria_area ON telemetria (inquilino, area_slug, grandeza, ts DESC);

CREATE TABLE IF NOT EXISTS ultimo_estado (
  inquilino VARCHAR(60) NOT NULL,
  dispositivo VARCHAR(60) NOT NULL,
  grandeza VARCHAR(60) NOT NULL,
  ts TIMESTAMPTZ NOT NULL,
  valor DOUBLE PRECISION NOT NULL,
  unidade VARCHAR(30),
  PRIMARY KEY (dispositivo, grandeza)
);

CREATE TABLE IF NOT EXISTS regra (
  id SERIAL PRIMARY KEY,
  inquilino VARCHAR(60) NOT NULL,
  dominio_slug VARCHAR(60) NOT NULL,
  nome VARCHAR(120) NOT NULL,
  grandeza VARCHAR(60) NOT NULL,
  operador VARCHAR(2) NOT NULL,
  limite DOUBLE PRECISION NOT NULL,
  grandeza2 VARCHAR(60),
  operador2 VARCHAR(2),
  limite2 DOUBLE PRECISION,
  janela_s INTEGER NOT NULL DEFAULT 0,
  severidade VARCHAR(10) NOT NULL DEFAULT 'MEDIA',
  webhook_url VARCHAR(300),
  ativa BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (inquilino, nome)
);

CREATE TABLE IF NOT EXISTS alarme (
  id BIGSERIAL PRIMARY KEY,
  inquilino VARCHAR(60) NOT NULL,
  chave VARCHAR(160) NOT NULL,
  regra_nome VARCHAR(120) NOT NULL,
  dispositivo VARCHAR(60),
  area_slug VARCHAR(60),
  severidade VARCHAR(10) NOT NULL,
  estado VARCHAR(12) NOT NULL,
  mensagem TEXT,
  aberto_em TIMESTAMPTZ NOT NULL,
  reconhecido_em TIMESTAMPTZ,
  reconhecido_por VARCHAR(60),
  resolvido_em TIMESTAMPTZ
);
-- Só pode existir um alarme aberto por chave (regra + dispositivo)
CREATE UNIQUE INDEX IF NOT EXISTS ux_alarme_aberto ON alarme (chave) WHERE estado <> 'RESOLVIDO';

-- Fila de mensagens inválidas (Dead Letter Channel)
CREATE TABLE IF NOT EXISTS mensagem_invalida (
  id BIGSERIAL PRIMARY KEY,
  recebida_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  origem VARCHAR(20),
  motivo TEXT,
  payload TEXT
);

CREATE TABLE IF NOT EXISTS no_borda (
  id VARCHAR(60) PRIMARY KEY,
  inquilino VARCHAR(60) NOT NULL,
  nome VARCHAR(120),
  token VARCHAR(80) NOT NULL,
  ultima_sincronizacao TIMESTAMPTZ,
  fila_pendente INTEGER NOT NULL DEFAULT 0,
  versao VARCHAR(30)
);
