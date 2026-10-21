# Plataforma IoT Integrada · Mobilidade (fluxo de tráfego)

Projeto da disciplina de Internet das Coisas (UniEVANGÉLICA). Implementa o MVP descrito no *Documento de Requisitos*:
dispositivos simulados → ingestão (MQTT, HTTP e lotes) → dados → regras e alarmes → painel com mapa, com um **nó de borda**
que guarda os dados quando o link com a central cai e sincroniza depois.

## Sumário

1. [Estrutura](#estrutura)
2. [Pré-requisitos e portas](#pré-requisitos-e-portas)
3. [Preparo de primeira vez](#preparo-de-primeira-vez)
4. [Roteiro de teste, do zero](#roteiro-de-teste-do-zero)
5. [Controle da demonstração](#controle-da-demonstração)
6. [Painel](#painel)
7. [Roteiro manual por ato](#roteiro-manual-por-ato)
8. [Simuladores](#simuladores)
9. [Endpoints](#endpoints)
10. [Configuração](#configuração)
11. [Rastreabilidade com o documento](#rastreabilidade-com-o-documento)
12. [Resultados e verificações](#resultados-e-verificações)
13. [Limitações conhecidas](#limitações-conhecidas)
14. [Solução de problemas](#solução-de-problemas)

## Estrutura

```
iot-mobilidade/
  plataforma-central/   Spring Boot 3 (Java 21): ingestão, catálogo, regras, alarmes, API REST e painel (Thymeleaf)
  no-borda/             Spring Boot enxuto: ingestão local (HTTP + MQTT), fila em SQLite e sincronização em lotes
  simuladores/          java/ (HTTP) · python/ (MQTT) · node/ (MQTT) · carga/ (teste de carga)
  adaptadores/python/   adaptador de protocolo (integração de nível 2)
  demo/                 página de controle da demonstração (controle.py e controle.html)
  contratos/            JSON Schema da mensagem canônica e AsyncAPI (também servidos em /contratos/)
  mosquitto/            configuração do broker MQTT
  docker-compose.yml    PostgreSQL + TimescaleDB e dois brokers Mosquitto (e, opcionalmente, central e borda)
```

## Pré-requisitos e portas

JDK 21 · Maven 3.9+ · Docker com Compose · Python 3.9+ · Node.js 18+ · navegador com internet
(o painel carrega Leaflet, Chart.js e os mapas do OpenStreetMap por CDN).

| Porta | Serviço |
|---|---|
| 5433 | PostgreSQL + TimescaleDB (no Docker; a 5432 costuma estar ocupada) |
| 1883 | Mosquitto da central |
| 1884 | Mosquitto do nó de borda |
| 8080 | Central: painel, API e Swagger |
| 8081 | Nó de borda |
| 8090 | Controle da demonstração |

Usuários de demonstração (todos do inquilino `semob`): `admin / admin123` (tudo), `operador / operador123`
(consulta e reconhece alarmes) e `leitor / leitor123` (só consulta). **Troque as senhas fora do ambiente acadêmico.**

## Preparo de primeira vez

Só na primeira vez em cada computador:

```bash
cd simuladores/python
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cd ../..
```

As dependências do simulador Node.js são instaladas sozinhas pelo controle. A primeira execução do Maven baixa
as dependências e leva alguns minutos.

## Roteiro de teste, do zero

Todos os comandos rodam na pasta `iot-mobilidade`. Use **4 terminais**: 1 para comandos, 2 para a central, 3 para a borda e 4 para o controle.

**1. Infraestrutura** (terminal 1)

```bash
docker compose up -d postgres mosquitto mosquitto-borda
docker compose ps
```

Esperado: três serviços `Up`, com o `postgres` em `(healthy)`.

**2. Zerar a fila do nó de borda** (com o nó parado)

```bash
rm -f no-borda/data/borda.db*
```

Necessário quando o nó já rodou antes: o contador `enviadas` do nó acumula todas as rodadas, e a prova final só conta a rodada atual.
Na primeira vez, o comando não faz nada.

**3. Central** (terminal 2)

```bash
cd plataforma-central
mvn spring-boot:run -Dspring-boot.run.profiles=texto
```

Esperado: `Started CentralApplication`. O perfil `texto` deixa os logs legíveis; sem ele, saem em JSON.

**4. Nó de borda** (terminal 3)

```bash
cd no-borda
mvn spring-boot:run
```

Esperado: `Started BordaApplication`.

**5. Controle da demonstração** (terminal 4)

```bash
python3 demo/controle.py --fator 4      # ensaio, com as esperas 4 vezes mais curtas
python3 demo/controle.py                # tempos reais, para apresentar
```

Abra **duas janelas lado a lado** (não abas: o navegador reduz a atualização de abas ocultas):

- Controle: http://localhost:8090
- Painel: http://localhost:8080 (login `admin` / `admin123`)

**6. Preparar** (no controle)

1. **Verificar serviços**: os chips de central e borda ficam verdes.
2. **Zerar dados de demonstração**: apaga leituras e alarmes e mantém o catálogo e as regras.
3. **Limpar o domínio de teste**: remove o domínio Estacionamento de uma rodada anterior.

**7. Rodar**

Clique em **▶ Executar o roteiro completo** e acompanhe o painel. Tempos aproximados (com `--fator 1`; com `--fator 4`, divida por 4):

| Momento | O que aparece no painel |
|---|---|
| 0:01 | Cruzamentos ficam verdes; indicadores sobem; três simuladores no controle |
| 0:25 | Alarme **CRÍTICA** de semáforo em falha (resolve sozinho em ~1:25) |
| 1:05 | Rua 10 vermelha e pulsando; alarme **ALTA** de congestionamento; aviso na tela (resolve sozinho em ~2:05) |
| 1:41 a 2:51 | Link da borda desligado: a **fila local sobe ao vivo** e o nó fica "Sem sincronizar" |
| 2:51 | Link restaurado: a fila vai a zero; o indicador "Reenviadas pela borda" sobe |
| 3:11 | Rua 15 sai do cinza (adaptador de formato próprio) |
| 3:25 | Mensagem inválida: "Mensagens recusadas" vai a 1 |
| 3:28 a 3:45 | Estacionamento: alarme "Estacionamento lotado" abre e resolve |
| 3:57 | Linha **PROVA** no registro do controle |
| 3:59 | Teste de carga, por último para não sujar os gráficos |

O registro do controle deve terminar com:

- `PROVA: enviadas pelo nó = N = gravadas pela central = N -> sem perda e sem duplicação`
- `20000 aceitas, 0 recusadas ... => mais de 1000 mensagens/s`
- `=== ROTEIRO CONCLUÍDO ===`

**8. Encerrar**: Ctrl+C no terminal 4, depois no 3 e no 2. Para deixar o ambiente limpo para a próxima rodada:
botões **Zerar dados** e **Limpar o domínio de teste** no controle e `rm -f no-borda/data/borda.db*` (com o nó parado).

## Controle da demonstração

Página com um botão para cada ato e um botão que executa o roteiro completo sozinho. Usa só a biblioteca padrão do Python
e fica separada do sistema: não altera nada dele. Os logs dos simuladores ficam em `demo/logs/` (ignorada pelo Git).

```bash
python3 demo/controle.py [--central URL] [--borda URL] [--usuario U] [--senha S] [--porta 8090] [--intervalo 3] [--fator 1]
```

| Botão | O que faz |
|---|---|
| Verificar serviços | Confere central, nó de borda e simuladores em execução |
| Zerar dados de demonstração | `TRUNCATE` das leituras, alarmes e mensagens (via Docker); mantém catálogo e regras |
| Iniciar / Parar os simuladores | Sobe ou para os simuladores Java (HTTP), Node.js (MQTT) e Python (MQTT, via borda) |
| Provocar congestionamento | Rua 10 congestionada por 100 s |
| Falha no semáforo | Falha no semáforo da Rua 10 por 60 s |
| Derrubar / Restaurar o link | Desliga e religa o enlace do nó de borda com a central |
| Prova: enviadas = gravadas | Compara o contador do nó com as mensagens gravadas pela central |
| Rodar o adaptador | Fornecedor de formato próprio, traduzido para o canônico (Rua 15) |
| Enviar mensagem inválida | Vai para a fila de mensagens inválidas (DLQ), com o motivo |
| Criar domínio Estacionamento | Domínio, perfil, regra e dispositivo por API, sem alterar código |
| Vagas livres = 0 / = 5 | Abre e resolve o alarme "Estacionamento lotado" |
| Limpar o domínio de teste | Remove o domínio Estacionamento |
| Teste de carga | 20.000 mensagens em lotes de 1.000, 4 conexões paralelas |

## Painel

Depois do login, mostra, sem precisar rolar em 1920×1080:

- **Indicadores:** cruzamentos por situação, alarmes abertos, dispositivos online, mensagens por minuto, dados reenviados pela borda e mensagens recusadas.
- **Mapa:** cada cruzamento é um marcador com a velocidade média, colorido pelo nível de fluxo; o congestionado pulsa; o selo **B** indica atendimento por nó de borda. O botão **Ajustar mapa** reenquadra a vista.
- **Alarmes** com severidade, tempo e botão Reconhecer, mais avisos na tela quando um alarme abre ou é resolvido.
- **Nó de borda:** estado visto pela central e a **fila local ao vivo**, lida direto do nó, com os botões **Derrubar link** e **Restaurar link**
  (o painel fala com `http://<mesmo host>:8081`; para outro endereço, defina `window.EDGE_URL` antes de carregar `painel.js`).
- **Atividade recente:** linha do tempo de alarmes, quedas de link e dispositivos online/offline.
- **Gráfico** de fluxo e velocidade com a linha do limite de congestionamento (15 km/h), em leituras (60 min) ou média por hora (24 h).
- **Dispositivos** com tipo, área, situação e filtro de texto.

Outros endereços: Swagger em `/swagger-ui.html`, contratos em `/contratos/mensagem-canonica-v1.json` e `/contratos/asyncapi.yaml`,
métricas Prometheus em `/actuator/prometheus`.

## Roteiro manual por ato

Alternativa ao controle, para quem prefere executar cada ato à mão. Simuladores rodando e painel aberto.

**UC-01 · Congestionamento.** A regra vem com janela de 10 min; para a demonstração, reduza-a para 30 s:

```bash
curl -u admin:admin123 http://localhost:8080/api/v1/regras                       # id da regra "Congestionamento" (1)
curl -u admin:admin123 -X POST "http://localhost:8080/api/v1/regras/1/janela?segundos=30"
cd simuladores/java && java Simulador.java --intervalo 3 --congestionar cruz-brasil-rua10 --congestionar-apos 15 --congestionar-por 120
```

Em poucos segundos o cruzamento fica vermelho; depois de ~40 s o alarme aparece e o log da central registra
`WEBHOOK RECEBIDO (sistema de rotas simulado)`. Reconheça o alarme; ao fim do congestionamento ele é resolvido sozinho.
Semáforo: `cd simuladores/node && node simulador.js --falha semaforo-brasil-rua10-01 --falha-apos 10`.

**UC-02 · Cruzamento sem conexão (borda).** Com o simulador Python rodando:

```bash
curl -X POST "http://localhost:8081/api/v1/borda/link?ligado=false"     # ou o botão "Derrubar link" do painel
curl http://localhost:8081/api/v1/borda/status                          # filaPendente cresce a cada leitura
curl -X POST "http://localhost:8081/api/v1/borda/link?ligado=true"      # o link volta
```

A fila é enviada em lotes; a central grava cada medição **no horário em que ocorreu** e descarta duplicatas. Para provar "sem perda e sem duplicação",
pare o simulador Python, espere 10 s e compare:

```bash
curl http://localhost:8081/api/v1/borda/status
docker compose exec postgres psql -U iot -d iot -c "SELECT count(DISTINCT id_mensagem) FROM telemetria WHERE via = 'borda-periferia-norte'"
```

**UC-03 · Formato próprio (nível 2) e mensagens inválidas.**

```bash
source simuladores/python/.venv/bin/activate
python adaptadores/python/adaptador_fornecedor.py --demo 5
curl -H "Content-Type: application/json" -d '{"versao":"1.0"}' http://localhost:8080/api/v1/ingestao/validar
curl -u admin:admin123 http://localhost:8080/api/v1/mensagens-invalidas
```

**UC-04 · Domínio novo só por configuração (estacionamento).**

```bash
curl -u admin:admin123 -H "Content-Type: application/json" -d '{"slug":"estacionamento","nome":"Estacionamento"}' http://localhost:8080/api/v1/catalogo/dominios
curl -u admin:admin123 -H "Content-Type: application/json" -d '{"dominio":"estacionamento","slug":"sensor-vaga","nome":"Sensor de vaga","intervaloS":120,"grandezas":[{"grandeza":"vagas_livres","unidade":"{vaga}","min":0,"max":200}]}' http://localhost:8080/api/v1/catalogo/perfis
curl -u admin:admin123 -H "Content-Type: application/json" -d '{"nome":"Estacionamento lotado","dominio":"estacionamento","grandeza":"vagas_livres","operador":"=","limite":0,"janelaS":0,"severidade":"MEDIA"}' http://localhost:8080/api/v1/regras
curl -u admin:admin123 -H "Content-Type: application/json" -d '{"slug":"vagas-praca-01","dominio":"estacionamento","site":"centro","area":"cruz-central-av2","perfil":"sensor-vaga","fabricante":"Simulado","firmware":"1.0"}' http://localhost:8080/api/v1/dispositivos
# a resposta traz o token do dispositivo (mostrado uma única vez); use-o abaixo
curl -H "Content-Type: application/json" -H "X-Device-Token: COLE_O_TOKEN" -d "{\"versao\":\"1.0\",\"id_mensagem\":\"vaga-$(date +%s)\",\"dispositivo\":\"vagas-praca-01\",\"tipo\":\"telemetria\",\"timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"medidas\":[{\"grandeza\":\"vagas_livres\",\"valor\":0}]}" http://localhost:8080/api/v1/ingestao
```

Valor `0` abre o alarme "Estacionamento lotado"; repetindo com `5` ele é resolvido. Nenhuma linha de código foi alterada.

**Teste de carga (RNF-01)**, por último, pois suja os gráficos com valores constantes:

```bash
source simuladores/python/.venv/bin/activate
python simuladores/carga/carga.py --total 20000 --lote 1000 --paralelo 4
```

## Simuladores

```bash
# Java (contadores do Centro, via HTTP para a central) - roda sem dependências
cd simuladores/java && java Simulador.java --intervalo 5

# Node.js (semáforos do Centro, via MQTT para a central)
cd simuladores/node && npm install && node simulador.js

# Python (contadores da Periferia Norte, via MQTT para o BROKER DO NÓ DE BORDA, porta 1884)
cd simuladores/python && source .venv/bin/activate && python simulador.py
```

Cada um aceita argumentos para mudar o intervalo e provocar situações (`--congestionar`, `--falha`).

## Endpoints

`POST /api/v1/ingestao` (header `X-Device-Token`) · `POST /api/v1/ingestao/lote` (headers `X-Node-Id`, `X-Node-Token`) ·
`POST /api/v1/ingestao/validar` · `GET /api/v1/dispositivos` · `GET /api/v1/dispositivos/{slug}/telemetria?grandeza=&de=&ate=&ordem=&page=&size=` ·
`GET /api/v1/areas/estado` · `GET /api/v1/areas/{slug}/serie|agregado` · `GET /api/v1/alarmes?estado=ABERTOS` ·
`POST /api/v1/alarmes/{id}/reconhecer` · `GET|POST /api/v1/regras` · `POST /api/v1/catalogo/{dominios|sites|areas|perfis}` ·
`POST /api/v1/dispositivos` · `POST /api/v1/dispositivos/{slug}/revogar|ativar` · `GET /api/v1/nos-borda` ·
`GET /api/v1/painel/resumo` · `GET /api/v1/mensagens-invalidas` (só `admin`). Detalhes e esquemas no Swagger.

## Configuração

Em `plataforma-central/src/main/resources/application.properties`:

| Propriedade | Valor | Efeito |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/iot` | Banco (porta 5433 do Docker) |
| `plataforma.mqtt.url` | `tcp://localhost:1883` | Broker MQTT da central |
| `plataforma.limite-atraso-s` | `30` | Acima disso, a leitura é "dado atrasado": gravada pelo horário da medição, contada em "Reenviadas pela borda" e sem disparar alarme como se fosse atual |
| `plataforma.nivel.fluido-min` / `congestionado-max` | `30` / `15` | Faixas de velocidade (km/h) das cores do mapa |
| `plataforma.painel.validade-s` | `300` | Sem dado de velocidade por esse tempo, o cruzamento fica cinza |

Alterações exigem reiniciar a central. O nó de borda usa `no-borda/src/main/resources/application.properties` (`borda.central-url`, `borda.mqtt.url`, etc.).

## Rastreabilidade com o documento

**OK** = implementado; **Parcial** = simplificado (explicação ao lado); **Fase 2/3** = não implementado.

| Requisito | Situação |
|---|---|
| RF-CEN-01, 02, 03 (catálogo, perfis, hierarquia) | OK |
| RF-CEN-04 (geometrias) | Parcial: cada área tem um ponto (lat/lon) no mapa; consultas espaciais ficam para a Fase 2 |
| RF-ING-01 (MQTT) | Parcial: assinante e convenção de tópicos funcionam; o Mosquitto de demonstração não usa TLS nem autenticação |
| RF-ING-02, 03, 04, 06, 08 (HTTP, lote, dado atrasado, schema, duplicata) | OK |
| RF-EDG-01, 02 (borda: fila e sincronização) | OK |
| RF-EDG-06 (estado do nó) | Parcial: última sincronização, situação e fila pendente; sem versão e espaço em disco |
| RF-DIS-01, 02, 06 (cadastro, credenciais, online/offline) | OK; a revogação vale na central |
| RF-INT-01, 02, 03 (contrato público, protocolos abertos, adaptador plugável) | OK |
| RF-PRO-01, 02, 04, 05 (enriquecer, regras, agregações, alarmes) | OK |
| RF-PRO-03 (ações) | Parcial: alarme e webhook; "publicar em tópico" fica para a Fase 2 |
| RF-DAD-01, 02, 05 (séries, último estado, LGPD) | OK |
| RF-APP-01, 03, 04, 06 (API, webhooks, painel, responsivo) | OK |
| RF-APP-02 (tempo real) | Parcial: o painel consulta a API a cada 2 s, sem WebSocket |
| RF-APP-07 (paginação) | Parcial: dispositivos, alarmes, telemetria e DLQ paginam; listas curtas não |
| RF-SEG-01, 02, 03 (login, papéis, inquilino) | OK com Spring Security (formulário e Basic; sem OpenID Connect nem 2º fator) |
| RF-SEG-04 (tópicos por dispositivo) | Parcial: o dispositivo do tópico deve coincidir com o da mensagem e estar cadastrado e ativo; sem ACL no broker |
| RF-SEG-05 (borda) | Parcial: token próprio do nó; a fila local não é cifrada |
| RF-OBS-01, 02 (métricas, logs) | OK na central (`/actuator/prometheus`; logs JSON com `correlationId` = `id_mensagem`); a borda usa log comum |
| Demais "Importante" e "Desejável" (gêmeo, comandos, NGSI-LD, OTA, CoAP...) | Fase 2/3 |

Escolhas de implementação: monólito modular na central; chamadas diretas entre módulos no lugar do barramento interno; valores categóricos do semáforo
codificados como número (`fase_atual`: 0 vermelho, 1 amarelo, 2 verde; `falha`: 0 ou 1); identificador de dispositivo único em toda a plataforma.

## Resultados e verificações

Medidos pelo grupo na execução real do sistema:

- **UC-01 a UC-04** executados e comprovados: alarmes abrem e se resolvem sozinhos; queda e volta do link da borda; adaptador de formato próprio; domínio novo só por configuração.
- **Queda do link (~9 min):** o nó enviou 1.008 mensagens e a central gravou 1.008 mensagens distintas vindas dele: sem perda e sem duplicação. Numa segunda contagem, 464 linhas por 464 `id_mensagem` por contador (zero duplicatas).
- **Carga (RNF-01):** 1.653 mensagens/s isolado e 1.211 mensagens/s com três simuladores e o painel rodando (meta: 1.000), no endpoint de lote, com lotes de 1.000 mensagens, 4 conexões paralelas e máquina de desenvolvimento.
- **Integração em três linguagens:** Java (HTTP), Node.js e Python (MQTT).

Não medidos: a ingestão por MQTT sob carga e a latência p95 (RNF-02).

Durante o desenvolvimento, as classes de ingestão, validação, regras, alarmes, consultas, lote e catálogo também foram executadas contra um PostgreSQL real
(55 verificações), e o painel foi renderizado em navegador com dados simulados (1920×1080 e celular), sem erros de JavaScript.

## Limitações conhecidas

- Cada mensagem é uma transação (≈6 comandos SQL); em máquinas mais fracas, a meta de 1.000 msg/s pode exigir inserção em lote.
- A garantia do nó de borda vale para a queda do **link** com a central. Se o **processo** do nó parar, as leituras publicadas nesse intervalo se perdem (sessão MQTT limpa).
- A regra "por 10 minutos" exige que **todas** as amostras da janela satisfaçam a condição (mínimo de 2 amostras cobrindo 80% da janela).
- A fila da borda não é cifrada; o Mosquitto de demonstração é anônimo e sem TLS.
- A DLQ não separa por inquilino, por isso só o `admin` a consulta.
- O painel depende de CDN (Leaflet, Chart.js e mapas do OpenStreetMap): sem internet, o painel não carrega.

## Solução de problemas

| Sintoma | Causa provável e solução |
|---|---|
| `port is already allocated` ao subir o Docker | Porta ocupada. O compose já usa 5433 para o PostgreSQL; para 1883/1884, pare um Mosquitto local (`sudo systemctl stop mosquitto`) |
| Central não conecta no banco | Confira `docker compose ps` e `spring.datasource.*` em `application.properties` |
| Logs JSON ilegíveis ou erro do encoder | Suba a central com `-Dspring-boot.run.profiles=texto` |
| Painel mostra "Link: INACESSÍVEL" | O nó de borda não está no ar ou não foi reiniciado depois da atualização (falta a liberação de CORS). Reinicie-o e teste `curl http://localhost:8081/api/v1/borda/status` |
| Controle mostra "fora do ar" | Suba a central e o nó de borda e clique em **Verificar serviços** |
| Erros de Docker nos botões do controle | Rode o controle a partir da pasta `iot-mobilidade` |
| `PROVA` com números diferentes | A fila do nó não foi zerada (`rm -f no-borda/data/borda.db*` com o nó parado) ou o simulador Python ainda estava rodando |
| Simulador Python com erro | Falta o `paho-mqtt`: crie o ambiente virtual (ver "Preparo de primeira vez") |
| "Já existia" no domínio Estacionamento e sem token | Clique em **Limpar o domínio de teste** e crie de novo (o token só aparece na criação) |
| Alarme de congestionamento não abre | Confira se a janela da regra foi ajustada para 30 s; o alarme leva cerca de 45 s |
| Alarmes de "equipamento sem dados" abertos | Simuladores parados; o alarme se resolve sozinho (até 15 s) quando os dados voltam |
| Cruzamento cinza no mapa | Sem dados de velocidade há mais de 5 min; inicie os simuladores |
| Mapa afastado, com marcadores empilhados | Botão **Ajustar mapa** |
| Alarmes e eventos aparecem todos juntos | Painel em aba oculta: use janelas lado a lado |
| Pasta `demo/logs/` aparece no `git status` | Garanta `iot-mobilidade/demo/logs/` no `.gitignore` da raiz do repositório |
