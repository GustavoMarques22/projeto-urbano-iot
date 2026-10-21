package br.edu.unievangelica.iot.central.regras;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Webhook de saída com novas tentativas e espera crescente (RF-APP-03). */
@Component
public class WebhookClient {

    private static final Logger log = LoggerFactory.getLogger(WebhookClient.class);
    private static final int TENTATIVAS = 4;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public void disparar(String url, String json) {
        if (url == null || url.isBlank()) {
            return;
        }
        executor.submit(() -> enviar(url, json));
    }

    private void enviar(String url, String json) {
        long espera = 1000;
        for (int tentativa = 1; tentativa <= TENTATIVAS; tentativa++) {
            try {
                HttpRequest rq = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<Void> rs = http.send(rq, HttpResponse.BodyHandlers.discarding());
                if (rs.statusCode() < 300) {
                    log.info("Webhook entregue em {} (tentativa {})", url, tentativa);
                    return;
                }
                log.warn("Webhook {} respondeu {} (tentativa {}/{})", url, rs.statusCode(), tentativa, TENTATIVAS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Webhook {} falhou: {} (tentativa {}/{})", url, e.getMessage(), tentativa, TENTATIVAS);
            }
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            espera *= 2;
        }
        log.error("Webhook {} não entregue após {} tentativas", url, TENTATIVAS);
    }

    @PreDestroy
    public void encerrar() {
        executor.shutdown();
    }
}
