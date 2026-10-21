package br.edu.unievangelica.iot.central.dados;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Faz o papel do "sistema de rotas" externo nas demonstrações: apenas registra o webhook recebido. */
@RestController
public class WebhookDemoController {

    private static final Logger log = LoggerFactory.getLogger(WebhookDemoController.class);

    @PostMapping("/webhook-demo")
    public Map<String, String> receber(@RequestBody String corpo) {
        log.info("WEBHOOK RECEBIDO (sistema de rotas simulado): {}", corpo);
        return Map.of("status", "recebido");
    }
}
