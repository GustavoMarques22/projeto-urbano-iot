package br.edu.unievangelica.iot.central.dados;

import br.edu.unievangelica.iot.central.seguranca.Tenant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Indicadores do topo do painel: dispositivos, vazão de mensagens, dados reenviados pela borda e recusas. */
@RestController
public class ResumoController {

    private final JdbcTemplate jdbc;

    public ResumoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/painel/resumo")
    public Map<String, Object> resumo(Authentication auth) {
        String inq = Tenant.de(auth);

        Map<String, Object> disp = jdbc.queryForMap(
                "SELECT count(*) AS total, count(*) FILTER (WHERE d.ultimo_visto IS NOT NULL "
                        + "AND d.ultimo_visto >= now() - p.intervalo_s * 3 * interval '1 second') AS online "
                        + "FROM dispositivo d JOIN perfil p ON p.inquilino = d.inquilino AND p.slug = d.perfil_slug "
                        + "WHERE d.inquilino = ? AND d.ativo", inq);

        List<Map<String, Object>> serie = jdbc.queryForList(
                "SELECT date_trunc('minute', recebida_em) AS minuto, count(*) AS qtd FROM mensagem_processada "
                        + "WHERE inquilino = ? AND recebida_em > now() - interval '15 minutes' GROUP BY 1 ORDER BY 1", inq);

        Map<String, Object> mensagens = new LinkedHashMap<>();
        mensagens.put("ultimoMinuto", escalar("SELECT count(*) FROM mensagem_processada "
                + "WHERE inquilino = ? AND recebida_em > now() - interval '1 minute'", inq));
        mensagens.put("total24h", escalar("SELECT count(*) FROM mensagem_processada "
                + "WHERE inquilino = ? AND recebida_em > now() - interval '24 hours'", inq));
        mensagens.put("serie", serie);

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("dispositivos", disp);
        corpo.put("mensagens", mensagens);
        corpo.put("atrasadas7d", escalar("SELECT count(DISTINCT id_mensagem) FROM telemetria "
                + "WHERE inquilino = ? AND atrasada AND ts > now() - interval '7 days'", inq));
        corpo.put("recusadas24h", escalar("SELECT count(*) FROM mensagem_invalida "
                + "WHERE recebida_em > now() - interval '24 hours'"));
        return corpo;
    }

    private long escalar(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
