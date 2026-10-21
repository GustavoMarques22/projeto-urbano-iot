package br.edu.unievangelica.iot.central.regras;

import br.edu.unievangelica.iot.central.comum.ApiException;
import br.edu.unievangelica.iot.central.comum.Slugs;
import br.edu.unievangelica.iot.central.seguranca.Tenant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cadastro de regras (RF-PRO-02) por API: o gestor cria regras sem alterar código. */
@RestController
public class RegraController {

    public record RegraReq(String nome, String dominio, String grandeza, String operador, Double limite,
                           String grandeza2, String operador2, Double limite2, Integer janelaS,
                           String severidade, String webhookUrl) {
    }

    private static final Set<String> SEVERIDADES = Set.of("BAIXA", "MEDIA", "ALTA", "CRITICA");

    private final JdbcTemplate jdbc;

    public RegraController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/regras")
    public List<Map<String, Object>> listar(Authentication auth) {
        return jdbc.queryForList("SELECT id, nome, dominio_slug, grandeza, operador, limite, grandeza2, operador2, "
                + "limite2, janela_s, severidade, webhook_url, ativa FROM regra WHERE inquilino = ? ORDER BY id",
                Tenant.de(auth));
    }

    @PostMapping("/api/v1/regras")
    public ResponseEntity<Map<String, Object>> criar(Authentication auth, @RequestBody RegraReq r) {
        String inq = Tenant.de(auth);
        if (r.nome() == null || r.nome().isBlank()) {
            throw new ApiException(400, "Campo 'nome' é obrigatório");
        }
        Slugs.validar(r.dominio(), "dominio");
        if (r.grandeza() == null || r.limite() == null) {
            throw new ApiException(400, "Campos 'grandeza' e 'limite' são obrigatórios");
        }
        MotorRegras.sqlOperador(r.operador());
        if (r.grandeza2() != null) {
            MotorRegras.sqlOperador(r.operador2());
            if (r.limite2() == null) {
                throw new ApiException(400, "Informe 'limite2' junto com 'grandeza2'");
            }
        }
        String severidade = r.severidade() == null ? "MEDIA" : r.severidade().toUpperCase();
        if (!SEVERIDADES.contains(severidade)) {
            throw new ApiException(400, "Severidade inválida: use BAIXA, MEDIA, ALTA ou CRITICA");
        }
        int janela = r.janelaS() == null ? 0 : Math.max(r.janelaS(), 0);
        if (jdbc.queryForList("SELECT 1 FROM dominio WHERE inquilino = ? AND slug = ?", inq, r.dominio()).isEmpty()) {
            throw new ApiException(400, "Domínio não encontrado: " + r.dominio());
        }
        try {
            Integer id = jdbc.queryForObject(
                    "INSERT INTO regra (inquilino, dominio_slug, nome, grandeza, operador, limite, grandeza2, operador2, "
                            + "limite2, janela_s, severidade, webhook_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "RETURNING id",
                    Integer.class, inq, r.dominio(), r.nome(), r.grandeza(), r.operador(), r.limite(), r.grandeza2(),
                    r.operador2(), r.limite2(), janela, severidade, r.webhookUrl());
            return ResponseEntity.status(201).body(Map.of("id", id, "nome", r.nome()));
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "Já existe uma regra com o nome: " + r.nome());
        }
    }

    @PostMapping("/api/v1/regras/{id}/ativa")
    public Map<String, Object> ativar(Authentication auth, @PathVariable("id") int id,
                                      @RequestParam("valor") boolean valor) {
        int n = jdbc.update("UPDATE regra SET ativa = ? WHERE id = ? AND inquilino = ?", valor, id, Tenant.de(auth));
        if (n == 0) {
            throw new ApiException(404, "Regra não encontrada: " + id);
        }
        return Map.of("id", id, "ativa", valor);
    }

    /** Ajuste rápido da janela (por exemplo, 30 s para a demonstração). */
    @PostMapping("/api/v1/regras/{id}/janela")
    public Map<String, Object> janela(Authentication auth, @PathVariable("id") int id,
                                      @RequestParam("segundos") int segundos) {
        int n = jdbc.update("UPDATE regra SET janela_s = ? WHERE id = ? AND inquilino = ?", Math.max(segundos, 0), id,
                Tenant.de(auth));
        if (n == 0) {
            throw new ApiException(404, "Regra não encontrada: " + id);
        }
        return Map.of("id", id, "janelaS", Math.max(segundos, 0));
    }
}
