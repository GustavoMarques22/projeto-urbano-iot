package br.edu.unievangelica.iot.borda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sincronização com a central (store-and-forward, RF-EDG-02): a cada ciclo envia a fila em lotes.
 * Uma mensagem só é marcada como enviada depois que a central responde por ela, e a central descarta
 * duplicatas pelo id_mensagem. Assim o resultado é: sem perdas e sem registros repetidos.
 */
@Component
public class SincronizadorCentral {

    private static final Logger log = LoggerFactory.getLogger(SincronizadorCentral.class);
    private static final Set<String> FINAIS = Set.of("ACEITA", "DUPLICADA", "RECUSADA", "NAO_AUTORIZADA");
    private static final int MAX_LOTES_POR_CICLO = 20;

    private final FilaRepository fila;
    private final LinkEstado link;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String centralUrl;
    private final String id;
    private final String token;
    private final String versao;
    private final int loteMax;

    public SincronizadorCentral(FilaRepository fila, LinkEstado link, ObjectMapper mapper,
                                @Value("${borda.central-url}") String centralUrl,
                                @Value("${borda.id}") String id,
                                @Value("${borda.token}") String token,
                                @Value("${borda.versao:1.0.0}") String versao,
                                @Value("${borda.lote-max:500}") int loteMax) {
        this.fila = fila;
        this.link = link;
        this.mapper = mapper;
        this.centralUrl = centralUrl;
        this.id = id;
        this.token = token;
        this.versao = versao;
        this.loteMax = loteMax;
    }

    @Scheduled(fixedDelayString = "${borda.sync-intervalo-ms:5000}", initialDelay = 3000)
    public void sincronizar() {
        int pendentes = fila.pendentes();
        if (!link.ligado()) {
            log.info("Link com a central DESLIGADO (simulado): {} mensagem(ns) guardadas na fila", pendentes);
            return;
        }
        try {
            int enviadas = 0;
            for (int i = 0; i < MAX_LOTES_POR_CICLO; i++) {
                List<FilaRepository.Item> lote = fila.proximoLote(loteMax);
                if (lote.isEmpty()) {
                    break;
                }
                enviadas += enviarLote(lote);
            }
            if (enviadas > 0) {
                log.info("Sincronizadas {} mensagem(ns) com a central; restam {} na fila", enviadas, fila.pendentes());
            }
            heartbeat();
            link.sincronizouAgora();
            fila.limparAntigas();
        } catch (Exception e) {
            log.warn("Central indisponível, mantendo a fila ({} pendentes): {}", fila.pendentes(), e.getMessage());
        }
    }

    private int enviarLote(List<FilaRepository.Item> lote) throws Exception {
        String corpo = "[" + lote.stream().map(FilaRepository.Item::payload).collect(Collectors.joining(",")) + "]";
        HttpRequest rq = HttpRequest.newBuilder(URI.create(centralUrl + "/api/v1/ingestao/lote"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-Node-Id", id)
                .header("X-Node-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        HttpResponse<String> rs = http.send(rq, HttpResponse.BodyHandlers.ofString());
        if (rs.statusCode() != 200) {
            throw new IllegalStateException("A central respondeu HTTP " + rs.statusCode() + ": " + rs.body());
        }
        int confirmadas = 0;
        JsonNode resultados = mapper.readTree(rs.body()).path("resultados");
        for (JsonNode r : resultados) {
            String status = r.path("status").asText();
            if (FINAIS.contains(status) && r.hasNonNull("id_mensagem")) {
                fila.marcarEnviada(r.get("id_mensagem").asText(), status);
                confirmadas++;
            }
        }
        if (confirmadas == 0) {
            throw new IllegalStateException("A central não confirmou nenhuma mensagem do lote");
        }
        return confirmadas;
    }

    private void heartbeat() throws Exception {
        String corpo = "{\"filaPendente\":" + fila.pendentes() + ",\"versao\":\"" + versao + "\"}";
        HttpRequest rq = HttpRequest.newBuilder(URI.create(centralUrl + "/api/v1/borda/" + id + "/heartbeat"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Node-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        HttpResponse<Void> rs = http.send(rq, HttpResponse.BodyHandlers.discarding());
        if (rs.statusCode() >= 300) {
            throw new IllegalStateException("Heartbeat recusado: HTTP " + rs.statusCode());
        }
    }
}
