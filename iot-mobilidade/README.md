# Plataforma IoT Integrada · Mobilidade (fluxo de tráfego)

Projeto da disciplina de Internet das Coisas (UniEVANGÉLICA). Implementa o MVP descrito no *Documento Inicial*:
dispositivos simulados → ingestão (MQTT/HTTP/lotes) → dados → regras e alarmes → painel com mapa, com um **nó de borda**
que guarda os dados quando o link cai e sincroniza depois.

## Estrutura

```
iot-mobilidade/
  plataforma-central/   Spring Boot 3 (Java 21): ingestão, catálogo, regras, alarmes, API REST e painel (Thymeleaf)
  no-borda/             Spring Boot enxuto: ingestão local (HTTP + MQTT), fila em SQLite e sincronização em lotes
  simuladores/          java/ (HTTP) · python/ (MQTT) · node/ (MQTT) · carga/ (teste de carga)
  adaptadores/python/   adaptador de protocolo (integração de nível 2)
  contratos/            JSON Schema da mensagem canônica e AsyncAPI (cópias também servidas em /contratos/)
  mosquitto/            configuração do broker MQTT
  docker-compose.yml    PostgreSQL+TimescaleDB, dois brokers Mosquitto e, opcionalmente, central e borda
```

## Pré-requisitos

JDK 21 · Maven 3.9+ · Docker (com Compose) · Python 3.9+ · Node.js 18+. Internet no navegador (o painel carrega
Leaflet, Chart.js e os mapas do OpenStreetMap por CDN).

## Como rodar

**Opção A (recomendada para desenvolver e demonstrar): infraestrutura no Docker, aplicações com Maven.**

```bash
docker compose up -d postgres mosquitto mosquitto-borda

# terminal 2 - plataforma central (http://localhost:8080)
cd plataforma-central
mvn spring-boot:run -Dspring-boot.run.profiles=texto      # "texto" = logs legíveis; sem o perfil, os logs saem em JSON

# terminal 3 - nó de borda (http://localhost:8081)
cd no-borda
mvn spring-boot:run
```

**Opção B: tudo em contêineres** (a primeira vez demora, pois o Maven baixa as dependências): `docker compose up --build`.

O PostgreSQL do compose usa a porta **5433** no seu computador (a 5432 costuma estar ocupada); a central já aponta para ela.

Na primeira execução a central cria as tabelas e carrega o cenário de mobilidade (`schema.sql` e `data.sql`):
inquilino `semob`, 5 cruzamentos, 8 dispositivos, perfis, 2 regras e o nó `borda-periferia-norte`.

| O quê | Onde |
|---|---|
| Painel | http://localhost:8080 (login abaixo) |
| Documentação da API (Swagger) | http://localhost:8080/swagger-ui.html |
| Contratos públicos | http://localhost:8080/contratos/mensagem-canonica-v1.json · `/contratos/asyncapi.yaml` |
| Métricas (Prometheus) | http://localhost:8080/actuator/prometheus |
| Estado do nó de borda | http://localhost:8081/api/v1/borda/status |

Usuários de demonstração: `admin / admin123` (tudo), `operador / operador123` (consulta e reconhece alarmes),
`leitor / leitor123` (só consulta). Todos pertencem ao inquilino `semob`. **Troque as senhas fora do ambiente acadêmico.**

## Painel de demonstração

Depois do login (página própria, com a marca da instituição), o painel mostra, sem precisar rolar em tela cheia (1920×1080):

- **Indicadores:** cruzamentos por situação, alarmes abertos (críticos e altos), dispositivos online, mensagens por minuto (com mini-gráfico),
  dados reenviados pela borda e mensagens recusadas.
- **Mapa:** cada cruzamento é um marcador com a **velocidade média**, colorido pelo nível de fluxo; o congestionado **pulsa**; o selo **B** indica
  que o cruzamento é atendido por nó de borda. Clique em um cruzamento para ver o histórico.
- **Alarmes** com severidade, tempo e botão Reconhecer, mais **avisos** na tela quando um alarme abre ou é resolvido.
- **Nó de borda:** estado visto pela central **e a fila local ao vivo**, lida direto do nó, com os botões **Derrubar link** e **Restaurar link**
  (o painel fala com o nó em `http://<mesmo host>:8081`; para outro endereço, defina `window.EDGE_URL` antes de carregar `painel.js`).
- **Atividade recente:** linha do tempo de alarmes, quedas de link e dispositivos que ficaram online/offline.
- **Gráfico** de fluxo e velocidade com a linha do limite de congestionamento, em leituras (60 min) ou média por hora (24 h).
- **Dispositivos** com tipo, área e situação, e filtro de texto.

## Simuladores

```bash
# Java (contadores do Centro, via HTTP para a central) - roda sem dependências
cd simuladores/java && java Simulador.java --intervalo 5

# Node.js (semáforos do Centro, via MQTT para a central)
cd simuladores/node && npm install && node simulador.js

# Python (contadores da Periferia Norte, via MQTT para o BROKER DO NÓ DE BORDA, porta 1884)
cd simuladores/python && pip install -r requirements.txt && python simulador.py
```

Cada um aceita `--help`/argumentos para mudar o intervalo e provocar situações (`--congestionar`, `--falha`).

## Roteiro de demonstração

**UC-01 · Congestionamento.** A regra vem com janela de 10 min; para a demonstração, reduza-a para 30 s:

```bash
curl -u SEU_USUARIO:SUA_SENHA http://localhost:8080/api/v1/regras                       # confira o id da regra "Congestionamento" (1)
curl -u SEU_USUARIO:SUA_SENHA -X POST "http://localhost:8080/api/v1/regras/1/janela?segundos=30"
cd simuladores/java && java Simulador.java --intervalo 3 --congestionar cruz-brasil-rua10 --congestionar-apos 15 --congestionar-por 120
```

Em poucos segundos o cruzamento fica **vermelho** no mapa; depois de ~40 s (a janela precisa ser atendida) o alarme aparece
na lista e a central registra no log o `WEBHOOK RECEBIDO (sistema de rotas simulado)`. Reconheça o alarme pelo botão. Quando o congestionamento termina,
o alarme é **resolvido sozinho**. Para o semáforo: `node simulador.js --falha semaforo-brasil-rua10-01 --falha-apos 10`.

**UC-02 · Cruzamento sem conexão (borda).** Com o simulador Python rodando:

```bash
# pelo painel: botão "Derrubar link" no cartão do nó de borda (ou pelos comandos abaixo)
curl -X POST "http://localhost:8081/api/v1/borda/link?ligado=false"     # derruba o link com a central
curl http://localhost:8081/api/v1/borda/status                          # filaPendente cresce a cada leitura
# no painel: o nó vira "SEM SINCRONIZAR" em ~1 min e os contadores norte ficam OFFLINE em ~3 min
curl -X POST "http://localhost:8081/api/v1/borda/link?ligado=true"      # o link volta
```

A fila é enviada em lotes; a central grava cada medição **no horário em que ocorreu** e descarta duplicatas.
Selecione "Cruzamento Norte 1" no mapa para ver o gráfico preenchido. Para provar "sem perda e sem duplicação":
compare `SELECT count(*) FROM telemetria WHERE dispositivo = 'contador-norte-1-01'` com o total de leituras enviadas.
Dados com mais de 10 min de atraso entram marcados como `atrasada` e não disparam alarme.

**UC-03 · Integrar um formato próprio (nível 2).**

```bash
python adaptadores/python/adaptador_fornecedor.py --demo 5               # o "Fornecedor X" envia um JSON próprio e o adaptador traduz
mosquitto_pub -p 1883 -t semob/centro/mobilidade/contador-brasil-rua10-01/telemetria -m '{"versao":"1.0"}'
curl -u SEU_USUARIO:SUA_SENHA http://localhost:8080/api/v1/mensagens-invalidas  # a mensagem inválida está na DLQ, com o motivo
curl -H "Content-Type: application/json" -d '{"versao":"1.0"}' http://localhost:8080/api/v1/ingestao/validar   # validador do integrador
```

**UC-04 · Domínio novo só por configuração (estacionamento).**

```bash
curl -u SEU_USUARIO:SUA_SENHA -H "Content-Type: application/json" -d '{"slug":"estacionamento","nome":"Estacionamento"}' http://localhost:8080/api/v1/catalogo/dominios
curl -u SEU_USUARIO:SUA_SENHA -H "Content-Type: application/json" -d '{"dominio":"estacionamento","slug":"sensor-vaga","nome":"Sensor de vaga","intervaloS":120,"grandezas":[{"grandeza":"vagas_livres","unidade":"{vaga}","min":0,"max":200}]}' http://localhost:8080/api/v1/catalogo/perfis
curl -u SEU_USUARIO:SUA_SENHA -H "Content-Type: application/json" -d '{"slug":"vagas-praca-01","dominio":"estacionamento","site":"centro","area":"cruz-central-av2","perfil":"sensor-vaga","fabricante":"Simulado","firmware":"1.0"}' http://localhost:8080/api/v1/dispositivos
# a resposta traz o token do dispositivo; use-o abaixo
curl -H "Content-Type: application/json" -H "X-Device-Token: COLE_O_TOKEN" -d "{\"versao\":\"1.0\",\"id_mensagem\":\"vaga-$(date +%s)\",\"dispositivo\":\"vagas-praca-01\",\"tipo\":\"telemetria\",\"timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"medidas\":[{\"grandeza\":\"vagas_livres\",\"valor\":12}]}" http://localhost:8080/api/v1/ingestao
curl -u SEU_USUARIO:SUA_SENHA -H "Content-Type: application/json" -d '{"nome":"Estacionamento lotado","dominio":"estacionamento","grandeza":"vagas_livres","operador":"=","limite":0,"janelaS":900,"severidade":"MEDIA"}' http://localhost:8080/api/v1/regras
```

Nenhuma linha de código foi alterada: o domínio novo já tem histórico, regras, alarmes e API.

**Teste de carga (RNF-01).** `python simuladores/carga/carga.py --total 20000 --lote 1000 --paralelo 4` imprime as mensagens por segundo.
A meta do documento é 1.000 msg/s; se não atingir, o gargalo provável é uma transação por mensagem (veja "Limitações").

## Principais endpoints

`POST /api/v1/ingestao` (header `X-Device-Token`) · `POST /api/v1/ingestao/lote` (headers `X-Node-Id`, `X-Node-Token`) ·
`POST /api/v1/ingestao/validar` · `GET /api/v1/dispositivos` · `GET /api/v1/dispositivos/{slug}/telemetria?grandeza=&de=&ate=&ordem=&page=&size=` ·
`GET /api/v1/areas/estado` · `GET /api/v1/areas/{slug}/serie|agregado` · `GET /api/v1/alarmes?estado=ABERTOS` ·
`POST /api/v1/alarmes/{id}/reconhecer` · `GET|POST /api/v1/regras` · `POST /api/v1/catalogo/{dominios|sites|areas|perfis}` ·
`POST /api/v1/dispositivos` · `POST /api/v1/dispositivos/{slug}/revogar|ativar` · `GET /api/v1/nos-borda` · `GET /api/v1/mensagens-invalidas`.
Detalhes e esquemas no Swagger.

## Rastreabilidade com o documento

Legenda: **OK** = implementado; **Parcial** = implementado de forma simplificada (explicação ao lado); **Fase 2/3** = não implementado.

| Requisito | Situação |
|---|---|
| RF-CEN-01, 02, 03 (catálogo, perfis, hierarquia) | OK |
| RF-CEN-04 (geometrias) | Parcial: cada área tem ponto (lat/lon) exibido no mapa; consultas espaciais ficam para a Fase 2 |
| RF-ING-01 (MQTT) | Parcial: o assinante e a convenção de tópicos funcionam; o Mosquitto de demonstração não usa TLS nem autenticação |
| RF-ING-02, 03, 04, 06, 08 (HTTP, lote, dado atrasado, schema, duplicata) | OK |
| RF-EDG-01, 02 (borda: fila e sincronização) | OK (veja "O que não foi verificado") |
| RF-EDG-06 (estado do nó) | Parcial: última sincronização, situação e fila pendente; sem versão/espaço em disco |
| RF-DIS-01, 02, 06 (cadastro, credenciais, online/offline) | OK; a revogação vale na central (a borda não conhece os dispositivos e a central recusa no sync) |
| RF-INT-01, 02, 03 (contrato público, protocolos abertos, adaptador plugável) | OK |
| RF-PRO-01, 02, 04, 05 (enriquecer, regras, agregações, alarmes) | OK |
| RF-PRO-03 (ações) | Parcial: gerar alarme e chamar webhook; "publicar em tópico" ficou para a Fase 2 |
| RF-DAD-01, 02, 05 (séries, último estado, LGPD) | OK |
| RF-APP-01, 03, 04, 06 (API, webhooks, painel, responsivo) | OK |
| RF-APP-02 (tempo real) | Parcial: o painel consulta a API a cada 2 s (sem WebSocket) |
| RF-APP-07 (paginação) | Parcial: dispositivos, alarmes, telemetria e DLQ paginam; listas curtas (domínios, perfis, regras, nós) não |
| RF-SEG-01, 02, 03 (login, papéis, inquilino) | OK com Spring Security (login por formulário/Basic; sem OpenID Connect nem 2º fator) |
| RF-SEG-04 (tópicos por dispositivo) | Parcial: o dispositivo do tópico precisa coincidir com o da mensagem e estar cadastrado e ativo; sem ACL no broker |
| RF-SEG-05 (borda) | Parcial: o nó se autentica com token próprio; a fila local não é cifrada |
| RF-OBS-01, 02 (métricas, logs) | OK na central (`/actuator/prometheus`; logs JSON com `correlationId` = `id_mensagem`); a borda usa log comum |
| Demais "Importante" e "Desejável" (gêmeo, comandos, NGSI-LD, OTA, CoAP, regras de borda...) | Fase 2/3 |

Escolhas de implementação: monólito modular na central; barramento interno substituído por chamadas diretas entre módulos;
valores categóricos do semáforo codificados como número (`fase_atual`: 0 vermelho, 1 amarelo, 2 verde; `falha`: 0/1);
o identificador do dispositivo é único em toda a plataforma (a mensagem canônica não traz o inquilino).

## O que foi verificado e o que não foi

Este código foi escrito num ambiente **sem acesso ao Maven Central**, então o projeto **nunca foi compilado nem executado com o Spring Boot**.
O que foi exercitado:

- `schema.sql` e `data.sql` rodaram duas vezes num PostgreSQL 16 real (idempotentes).
- As classes reais de ingestão, validação, regras, alarmes, consultas, lote e catálogo foram executadas contra esse PostgreSQL
  (55 verificações: duplicata, dado de 48 h atrás, alarme abrir/não duplicar/resolver, equipamento sem dados, paginação, domínio novo,
  indicadores do painel...).
  O Spring usado nessa verificação foi a versão 4.3 do Ubuntu, com stubs para as bibliotecas que faltavam.
- Os simuladores Python e Node.js e o adaptador rodaram contra dois brokers Mosquitto; o simulador Java enviou HTTP a um servidor de teste.
  Todas as mensagens geradas validam no JSON Schema; os casos inválidos (sem medidas, versão errada, campo de placa...) são recusados.
- As instruções SQL da fila do nó de borda rodaram num SQLite real (prioridade, ordem pelo horário e duplicata).
- O painel e a página de login foram renderizados num navegador (Chromium headless) com dados simulados: marcadores, alarmes, avisos, nó de borda
  com queda de link, tela de 1920×1080 e celular, sem erros de JavaScript. Depois, o grupo rodou o sistema completo e validou os casos de uso.

**Não verificado (verifiquem no primeiro build):** a inicialização do Spring Boot (configuração de segurança com Spring Security 6,
`logback-spring.xml`, versões das dependências), o painel no navegador, o nó de borda em execução, a hipertabela do TimescaleDB e o Docker.
Se algo falhar ao subir, copiem a mensagem de erro: a causa provável é de configuração, não de lógica.

## Limitações conhecidas

- Cada mensagem é uma transação (≈6 comandos SQL). A meta de 1.000 msg/s pode exigir inserção em lote.
- A janela da regra "por 10 minutos" exige que **todas** as amostras da janela satisfaçam a condição (mínimo de 2 amostras
  cobrindo 80% da janela).
- A fila da borda não é cifrada, e o Mosquitto de demonstração é anônimo e sem TLS.
- A DLQ não separa por inquilino, por isso só o `admin` a consulta.

## Solução de problemas

- **Logs JSON ilegíveis ou erro do encoder:** use `-Dspring-boot.run.profiles=texto`.
- **Central não conecta no banco:** confira `docker compose ps` e as propriedades `spring.datasource.*` em `application.properties`.
- **Sem dados no painel:** os cruzamentos ficam cinza ("sem dados") depois de 5 min sem leituras; inicie um simulador.
- **MQTT não conecta:** a central e a borda tentam reconectar a cada 5 s e a ingestão HTTP continua funcionando.
- **Porta ocupada:** 5432 (PostgreSQL), 1883/1884 (MQTT), 8080 (central), 8081 (borda).
