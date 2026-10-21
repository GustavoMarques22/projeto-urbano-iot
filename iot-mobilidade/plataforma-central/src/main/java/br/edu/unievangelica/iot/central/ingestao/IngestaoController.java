package br.edu.unievangelica.iot.central.ingestao;

import br.edu.unievangelica.iot.central.comum.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Ingestão por HTTP (RF-ING-02) e em lotes, usada pelos nós de borda (RF-ING-03). */
@RestController
public class IngestaoController {

    private static final int LOTE_MAXIMO = 5000;

    private final IngestaoService servico;
    private final JdbcTemplate jdbc;

    public IngestaoController(IngestaoService servico, JdbcTemplate jdbc) {
        this.servico = servico;
        this.jdbc = jdbc;
    }

    /** Requisição válida retorna 202; inválida retorna 400 com a lista de erros. */
    @PostMapping("/api/v1/ingestao")
    public ResponseEntity<Map<String, Object>> ingerir(@RequestBody JsonNode msg,
            @RequestHeader(value = "X-Device-Token", required = false) String token) {
        ResultadoMensagem r = servico.processar(msg, Contexto.http(token));
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("id_mensagem", r.id_mensagem());
        corpo.put("status", r.status());
        if (r.motivo() != null) {
            if (r.status().equals("ACEITA")) {
                corpo.put("observacao", r.motivo());
            } else {
                corpo.put("erros", java.util.Arrays.asList(r.motivo().split("; ")));
            }
        }
        int http = switch (r.status()) {
            case "ACEITA", "DUPLICADA" -> 202;
            case "NAO_AUTORIZADA" -> 401;
            default -> 400;
        };
        return ResponseEntity.status(http).body(corpo);
    }

    /** Sandbox: valida a mensagem sem gravar nada. */
    @PostMapping("/api/v1/ingestao/validar")
    public Map<String, Object> validar(@RequestBody JsonNode msg) {
        List<String> erros = servico.apenasValidar(msg);
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("valida", erros.isEmpty());
        corpo.put("erros", erros);
        return corpo;
    }

    /** Resultado por mensagem: um lote com 3 inválidas grava as demais e informa as 3 recusadas. */
    @PostMapping("/api/v1/ingestao/lote")
    public Map<String, Object> lote(@RequestBody JsonNode mensagens,
            @RequestHeader(value = "X-Node-Id", required = false) String noId,
            @RequestHeader(value = "X-Node-Token", required = false) String noToken) {
        autenticarNo(noId, noToken);
        if (mensagens == null || !mensagens.isArray()) {
            throw new ApiException(400, "O corpo deve ser um array de mensagens");
        }
        if (mensagens.size() > LOTE_MAXIMO) {
            throw new ApiException(400, "Lote acima do máximo de " + LOTE_MAXIMO + " mensagens");
        }
        List<ResultadoMensagem> resultados = new ArrayList<>();
        int aceitas = 0;
        int duplicadas = 0;
        int recusadas = 0;
        for (JsonNode msg : mensagens) {
            ResultadoMensagem r;
            try {
                r = servico.processar(msg, Contexto.borda());
            } catch (RuntimeException e) {
                String id = msg.hasNonNull("id_mensagem") ? msg.get("id_mensagem").asText() : null;
                r = new ResultadoMensagem(id, "ERRO", "Falha interna: " + e.getMessage());
            }
            switch (r.status()) {
                case "ACEITA" -> aceitas++;
                case "DUPLICADA" -> duplicadas++;
                default -> recusadas++;
            }
            resultados.add(r);
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("total", resultados.size());
        corpo.put("aceitas", aceitas);
        corpo.put("duplicadas", duplicadas);
        corpo.put("recusadas", recusadas);
        corpo.put("resultados", resultados);
        return corpo;
    }

    private void autenticarNo(String noId, String noToken) {
        if (noId == null || noToken == null) {
            throw new ApiException(401, "Informe X-Node-Id e X-Node-Token");
        }
        List<Map<String, Object>> nos = jdbc.queryForList("SELECT 1 FROM no_borda WHERE id = ? AND token = ?",
                noId, noToken);
        if (nos.isEmpty()) {
            throw new ApiException(401, "Nó de borda não autenticado");
        }
    }
}
