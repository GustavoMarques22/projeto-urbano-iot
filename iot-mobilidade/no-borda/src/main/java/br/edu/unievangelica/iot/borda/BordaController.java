package br.edu.unievangelica.iot.borda;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ingestão local por HTTP (mesmo contrato da central) e estado do nó. */
@CrossOrigin(origins = "*")
@RestController
public class BordaController {

    private final EntradaMensagem entrada;
    private final FilaRepository fila;
    private final LinkEstado link;
    private final String id;

    public BordaController(EntradaMensagem entrada, FilaRepository fila, LinkEstado link,
                           @Value("${borda.id}") String id) {
        this.entrada = entrada;
        this.fila = fila;
        this.link = link;
        this.id = id;
    }

    @PostMapping("/api/v1/ingestao")
    public ResponseEntity<Map<String, Object>> ingerir(@RequestBody JsonNode msg) {
        try {
            String status = entrada.aceitar(msg);
            return ResponseEntity.accepted().body(Map.of("status", status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "RECUSADA", "erros", e.getMessage()));
        }
    }

    @GetMapping("/api/v1/borda/status")
    public Map<String, Object> status() {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("id", id);
        corpo.put("linkLigado", link.ligado());
        corpo.put("filaPendente", fila.pendentes());
        corpo.put("enviadas", fila.enviadas());
        corpo.put("ultimaSincronizacao", link.ultimaSincronizacao() == null ? null : link.ultimaSincronizacao().toString());
        return corpo;
    }

    /** Simula a queda (ligado=false) e a volta (ligado=true) do enlace com a central. */
    @PostMapping("/api/v1/borda/link")
    public Map<String, Object> link(@RequestParam("ligado") boolean ligado) {
        link.definir(ligado);
        return Map.of("linkLigado", link.ligado(), "filaPendente", fila.pendentes());
    }
}
