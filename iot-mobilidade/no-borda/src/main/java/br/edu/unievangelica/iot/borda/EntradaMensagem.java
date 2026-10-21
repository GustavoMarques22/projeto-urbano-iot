package br.edu.unievangelica.iot.borda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Aceita uma mensagem canônica na borda: confere o mínimo, marca a rota (via) e guarda na fila. */
@Component
public class EntradaMensagem {

    private final FilaRepository fila;
    private final String bordaId;

    public EntradaMensagem(FilaRepository fila, @Value("${borda.id}") String bordaId) {
        this.fila = fila;
        this.bordaId = bordaId;
    }

    /** Retorna "ENFILEIRADA" ou "DUPLICADA"; lança IllegalArgumentException com os erros se for inválida. */
    public String aceitar(JsonNode msg) {
        List<String> erros = new ArrayList<>();
        if (msg == null || !msg.isObject()) {
            throw new IllegalArgumentException("A mensagem deve ser um objeto JSON");
        }
        if (!msg.hasNonNull("id_mensagem")) {
            erros.add("id_mensagem é obrigatório");
        }
        if (!msg.hasNonNull("dispositivo")) {
            erros.add("dispositivo é obrigatório");
        }
        long tsMs = 0;
        if (!msg.hasNonNull("timestamp")) {
            erros.add("timestamp é obrigatório");
        } else {
            try {
                tsMs = OffsetDateTime.parse(msg.get("timestamp").asText()).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                erros.add("timestamp inválido (use ISO 8601)");
            }
        }
        if (!erros.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", erros));
        }
        ObjectNode copia = ((ObjectNode) msg).deepCopy();
        if (!copia.hasNonNull("via")) {
            copia.put("via", bordaId); // rastreabilidade: por qual nó a mensagem passou
        }
        int prioridade = "evento".equals(copia.path("tipo").asText()) ? 1 : 0;
        boolean nova = fila.enfileirar(copia.get("id_mensagem").asText(), tsMs, prioridade, copia.toString());
        return nova ? "ENFILEIRADA" : "DUPLICADA";
    }
}
