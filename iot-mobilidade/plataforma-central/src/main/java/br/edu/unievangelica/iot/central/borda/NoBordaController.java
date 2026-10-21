package br.edu.unievangelica.iot.central.borda;

import br.edu.unievangelica.iot.central.comum.ApiException;
import br.edu.unievangelica.iot.central.seguranca.Tenant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Gestão remota dos nós de borda (RF-EDG-06): última sincronização e fila pendente. */
@RestController
public class NoBordaController {

    private final JdbcTemplate jdbc;

    public NoBordaController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** O nó informa o estado a cada ciclo de sincronização, autenticado com o token do nó. */
    @PostMapping("/api/v1/borda/{id}/heartbeat")
    public Map<String, Object> heartbeat(@PathVariable("id") String id,
            @RequestHeader(value = "X-Node-Token", required = false) String token,
            @RequestBody Map<String, Object> corpo) {
        if (token == null) {
            throw new ApiException(401, "Informe X-Node-Token");
        }
        int fila = corpo.get("filaPendente") instanceof Number n ? n.intValue() : 0;
        String versao = corpo.get("versao") == null ? null : corpo.get("versao").toString();
        int linhas = jdbc.update("UPDATE no_borda SET ultima_sincronizacao = now(), fila_pendente = ?, versao = ? "
                + "WHERE id = ? AND token = ?", fila, versao, id, token);
        if (linhas == 0) {
            throw new ApiException(401, "Nó de borda não autenticado");
        }
        return Map.of("status", "ok");
    }

    @GetMapping("/api/v1/nos-borda")
    public List<Map<String, Object>> listar(Authentication auth) {
        return jdbc.queryForList(
                "SELECT id, nome, ultima_sincronizacao, fila_pendente, versao, "
                        + "EXTRACT(EPOCH FROM (now() - ultima_sincronizacao))::bigint AS segundos_sem_sincronizar, "
                        + "CASE WHEN ultima_sincronizacao IS NOT NULL AND ultima_sincronizacao > now() - interval '60 seconds' "
                        + "THEN 'SINCRONIZADO' ELSE 'SEM_SINCRONIZAR' END AS situacao "
                        + "FROM no_borda WHERE inquilino = ? ORDER BY nome", Tenant.de(auth));
    }
}
