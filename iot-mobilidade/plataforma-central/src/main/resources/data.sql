-- Carga inicial do cenário Mobilidade (configuração do catálogo, idempotente)

INSERT INTO inquilino (slug, nome) VALUES ('semob', 'Secretaria de Mobilidade') ON CONFLICT DO NOTHING;
INSERT INTO dominio (inquilino, slug, nome) VALUES ('semob', 'mobilidade', 'Mobilidade') ON CONFLICT DO NOTHING;

INSERT INTO site (inquilino, slug, nome) VALUES
  ('semob', 'centro', 'Centro'),
  ('semob', 'periferia-norte', 'Periferia Norte')
ON CONFLICT DO NOTHING;

INSERT INTO area (inquilino, slug, site_slug, nome, lat, lon) VALUES
  ('semob', 'cruz-brasil-rua10', 'centro', 'Av. Brasil x Rua 10', -16.3281, -48.9528),
  ('semob', 'cruz-brasil-rua15', 'centro', 'Av. Brasil x Rua 15', -16.3262, -48.9531),
  ('semob', 'cruz-central-av2', 'centro', 'Praça Central x Av. 2', -16.3300, -48.9490),
  ('semob', 'cruz-norte-1', 'periferia-norte', 'Cruzamento Norte 1', -16.3050, -48.9480),
  ('semob', 'cruz-norte-2', 'periferia-norte', 'Cruzamento Norte 2', -16.3020, -48.9440)
ON CONFLICT DO NOTHING;

INSERT INTO perfil (inquilino, slug, dominio_slug, nome, intervalo_s) VALUES
  ('semob', 'contador-veiculos', 'mobilidade', 'Contador de veículos', 60),
  ('semob', 'semaforo', 'mobilidade', 'Semáforo', 60)
ON CONFLICT DO NOTHING;

INSERT INTO perfil_grandeza (perfil_id, grandeza, unidade, valor_min, valor_max)
  SELECT id, 'fluxo_veiculos', '{veic}/min', 0, 150 FROM perfil WHERE inquilino = 'semob' AND slug = 'contador-veiculos'
ON CONFLICT DO NOTHING;
INSERT INTO perfil_grandeza (perfil_id, grandeza, unidade, valor_min, valor_max)
  SELECT id, 'velocidade_media', 'km/h', 0, 150 FROM perfil WHERE inquilino = 'semob' AND slug = 'contador-veiculos'
ON CONFLICT DO NOTHING;
INSERT INTO perfil_grandeza (perfil_id, grandeza, unidade, valor_min, valor_max)
  SELECT id, 'ocupacao_via', '%', 0, 100 FROM perfil WHERE inquilino = 'semob' AND slug = 'contador-veiculos'
ON CONFLICT DO NOTHING;

-- Semáforo: valores categóricos codificados como número (fase_atual: 0=vermelho, 1=amarelo, 2=verde, e falha: 0 ou 1)
INSERT INTO perfil_grandeza (perfil_id, grandeza, unidade, valor_min, valor_max)
  SELECT id, 'fase_atual', '1', 0, 2 FROM perfil WHERE inquilino = 'semob' AND slug = 'semaforo'
ON CONFLICT DO NOTHING;
INSERT INTO perfil_grandeza (perfil_id, grandeza, unidade, valor_min, valor_max)
  SELECT id, 'tempo_ciclo', 's', 20, 240 FROM perfil WHERE inquilino = 'semob' AND slug = 'semaforo'
ON CONFLICT DO NOTHING;
INSERT INTO perfil_grandeza (perfil_id, grandeza, unidade, valor_min, valor_max)
  SELECT id, 'falha', '1', 0, 1 FROM perfil WHERE inquilino = 'semob' AND slug = 'semaforo'
ON CONFLICT DO NOTHING;

-- Dispositivos de demonstração (todos com o mesmo token, apenas para fins acadêmicos)
INSERT INTO dispositivo (slug, inquilino, dominio_slug, site_slug, area_slug, perfil_slug, fabricante, firmware, token) VALUES
  ('contador-brasil-rua10-01', 'semob', 'mobilidade', 'centro', 'cruz-brasil-rua10', 'contador-veiculos', 'Simulado', '1.0', 'demo-token'),
  ('contador-brasil-rua15-01', 'semob', 'mobilidade', 'centro', 'cruz-brasil-rua15', 'contador-veiculos', 'Fornecedor X', '1.0', 'demo-token'),
  ('contador-central-av2-01', 'semob', 'mobilidade', 'centro', 'cruz-central-av2', 'contador-veiculos', 'Simulado', '1.0', 'demo-token'),
  ('contador-norte-1-01', 'semob', 'mobilidade', 'periferia-norte', 'cruz-norte-1', 'contador-veiculos', 'Simulado', '1.0', 'demo-token'),
  ('contador-norte-2-01', 'semob', 'mobilidade', 'periferia-norte', 'cruz-norte-2', 'contador-veiculos', 'Simulado', '1.0', 'demo-token'),
  ('semaforo-brasil-rua10-01', 'semob', 'mobilidade', 'centro', 'cruz-brasil-rua10', 'semaforo', 'Simulado', '1.0', 'demo-token'),
  ('semaforo-brasil-rua15-01', 'semob', 'mobilidade', 'centro', 'cruz-brasil-rua15', 'semaforo', 'Simulado', '1.0', 'demo-token'),
  ('semaforo-central-av2-01', 'semob', 'mobilidade', 'centro', 'cruz-central-av2', 'semaforo', 'Simulado', '1.0', 'demo-token')
ON CONFLICT DO NOTHING;

-- Regras iniciais (janela 600 s = 10 min, como no documento; ajustáveis pela API)
INSERT INTO regra (inquilino, dominio_slug, nome, grandeza, operador, limite, grandeza2, operador2, limite2, janela_s, severidade, webhook_url)
VALUES ('semob', 'mobilidade', 'Congestionamento', 'velocidade_media', '<', 15, 'ocupacao_via', '>', 70, 600, 'ALTA', 'http://localhost:8080/webhook-demo')
ON CONFLICT DO NOTHING;
INSERT INTO regra (inquilino, dominio_slug, nome, grandeza, operador, limite, janela_s, severidade, webhook_url)
VALUES ('semob', 'mobilidade', 'Semáforo em falha', 'falha', '>=', 1, 0, 'CRITICA', 'http://localhost:8080/webhook-demo')
ON CONFLICT DO NOTHING;

-- Nó de borda da Periferia Norte
INSERT INTO no_borda (id, inquilino, nome, token) VALUES
  ('borda-periferia-norte', 'semob', 'Nó de borda Periferia Norte', 'borda-demo-token')
ON CONFLICT DO NOTHING;
