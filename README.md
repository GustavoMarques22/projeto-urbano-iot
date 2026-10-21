# Projeto Urbano IoT — Mobilidade Inteligente

Plataforma IoT desenvolvida para a disciplina de **Internet das Coisas da UniEVANGÉLICA**, com foco no monitoramento de mobilidade urbana, fluxo de tráfego e detecção de situações críticas em cruzamentos.

O projeto implementa um MVP que integra dispositivos simulados, ingestão de dados via HTTP e MQTT, processamento de regras, geração de alarmes e visualização das informações em um painel com mapa interativo.

Um dos principais diferenciais é o **nó de borda (Edge Computing)**, que armazena temporariamente os dados quando há perda de conexão com a plataforma central e realiza a sincronização em lotes após o restabelecimento do link.

## Principais funcionalidades

- Monitoramento de cruzamentos, semáforos e dispositivos IoT.
- Ingestão e validação de dados por HTTP, MQTT e lotes.
- Painel com mapa interativo, indicadores, gráficos e histórico de telemetria.
- Detecção de congestionamentos, falhas e geração de alarmes.
- Armazenamento local e sincronização de dados pelo nó de borda.
- Integração de dispositivos e novos domínios por configuração.
- API REST documentada com Swagger e métricas para monitoramento.

## Tecnologias utilizadas

**Backend:** Java 21, Spring Boot 3 e Maven.

**Dados e mensageria:** PostgreSQL, TimescaleDB, SQLite e Mosquitto MQTT.

**Infraestrutura:** Docker Compose e Prometheus.

**Simuladores e integração:** Java, Python e Node.js.

**Interface:** Thymeleaf, JavaScript, Leaflet e Chart.js.

## Como executar

Pré-requisitos: JDK 21, Maven 3.9+, Docker com Compose, Python 3.9+ e Node.js 18+.

```bash
# Iniciar a infraestrutura
docker compose up -d postgres mosquitto mosquitto-borda
```

Em terminais separados, inicie a plataforma central e o nó de borda:

```bash
cd plataforma-central
mvn spring-boot:run -Dspring-boot.run.profiles=texto
```

```bash
cd no-borda
mvn spring-boot:run
```

Acesse o painel em `http://localhost:8080`.

## Painel de Mobilidade:

<img width="1905" height="994" alt="image" src="https://github.com/user-attachments/assets/18fbc3b4-ec3b-456b-a2e0-36543c204bdf" />


A documentação completa, incluindo simuladores, endpoints, cenários de demonstração e instruções de configuração, está disponível neste repositório.

## Contexto acadêmico

Projeto desenvolvido como atividade prática da disciplina de Internet das Coisas, com o objetivo de demonstrar a integração entre dispositivos conectados, mensageria, processamento de dados e computação de borda aplicada à mobilidade urbana.
