package br.edu.unievangelica.iot.central.regras;

import br.edu.unievangelica.iot.central.comum.ApiException;
import br.edu.unievangelica.iot.central.comum.Pagina;
import br.edu.unievangelica.iot.central.comum.Paginacao;
import br.edu.unievangelica.iot.central.seguranca.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ciclo de vida dos alarmes: ATIVO, RECONHECIDO e RESOLVIDO (RF-PRO-05). */
@RestController
public class AlarmeController {

    private final JdbcTemplate jdbc;

    public AlarmeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/alarmes")
    public Pagina<Map<String, Object>> listar(Authentication auth,
            @RequestParam(value = "estado", required = false) String estado,
            @RequestParam(value = "ordem", defaultValue = "desc") String ordem,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        String inq = Tenant.de(auth);
        int p = Paginacao.page(page);
        int s = Paginacao.size(size);
        StringBuilder where = new StringBuilder(" WHERE inquilino = ?");
        List<Object> args = new ArrayList<>();
        args.add(inq);
        if (estado != null && !estado.isBlank()) {
            String e = estado.toUpperCase();
            if ("ABERTOS".equals(e)) {
                where.append(" AND estado <> 'RESOLVIDO'");
            } else {
                where.append(" AND estado = ?");
                args.add(e);
            }
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM alarme" + where, Long.class, args.toArray());
        args.add(s);
        args.add(p * s);
        List<Map<String, Object>> itens = jdbc.queryForList(
                "SELECT id, regra_nome, dispositivo, area_slug, severidade, estado, mensagem, aberto_em, "
                        + "reconhecido_em, reconhecido_por, resolvido_em FROM alarme" + where
                        + " ORDER BY aberto_em " + Paginacao.ordem(ordem) + " LIMIT ? OFFSET ?", args.toArray());
        return new Pagina<>(itens, p, s, total == null ? 0 : total);
    }

    @PostMapping("/api/v1/alarmes/{id}/reconhecer")
    public Map<String, Object> reconhecer(Authentication auth, @PathVariable("id") long id) {
        int n = jdbc.update("UPDATE alarme SET estado = 'RECONHECIDO', reconhecido_em = now(), reconhecido_por = ? "
                + "WHERE id = ? AND inquilino = ? AND estado = 'ATIVO'", auth.getName(), id, Tenant.de(auth));
        if (n == 0) {
            throw new ApiException(404, "Alarme não encontrado ou já reconhecido/resolvido: " + id);
        }
        return Map.of("id", id, "estado", "RECONHECIDO");
    }
}
