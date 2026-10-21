package br.edu.unievangelica.iot.central.dados;

import br.edu.unievangelica.iot.central.comum.ApiException;
import br.edu.unievangelica.iot.central.comum.Pagina;
import br.edu.unievangelica.iot.central.comum.Paginacao;
import br.edu.unievangelica.iot.central.seguranca.Tenant;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consultas de dados: último estado, séries, agregações, histórico e mensagens inválidas. */
@RestController
public class ConsultaController {

    private final JdbcTemplate jdbc;
    private final double fluidoMin;
    private final double congestionadoMax;
    private final long validadeS;

    public ConsultaController(JdbcTemplate jdbc,
                              @Value("${plataforma.nivel.fluido-min:30}") double fluidoMin,
                              @Value("${plataforma.nivel.congestionado-max:15}") double congestionadoMax,
                              @Value("${plataforma.painel.validade-s:300}") long validadeS) {
        this.jdbc = jdbc;
        this.fluidoMin = fluidoMin;
        this.congestionadoMax = congestionadoMax;
        this.validadeS = validadeS;
    }

    /** Alimenta o mapa: último estado de cada cruzamento e o nível de fluxo (faixas configuráveis). */
    @GetMapping("/api/v1/areas/estado")
    public List<Map<String, Object>> areasEstado(Authentication auth) {
        String inq = Tenant.de(auth);
        List<Map<String, Object>> areas = jdbc.queryForList(
                "SELECT slug, nome, site_slug, lat, lon FROM area WHERE inquilino = ? ORDER BY nome", inq);
        List<Map<String, Object>> estados = jdbc.queryForList(
                "SELECT d.area_slug, u.grandeza, u.valor, u.ts FROM ultimo_estado u "
                        + "JOIN dispositivo d ON d.slug = u.dispositivo WHERE u.inquilino = ?", inq);
        Map<String, Long> alarmes = new HashMap<>();
        for (Map<String, Object> a : jdbc.queryForList("SELECT area_slug, count(*) AS n FROM alarme "
                + "WHERE inquilino = ? AND estado <> 'RESOLVIDO' GROUP BY area_slug", inq)) {
            alarmes.put((String) a.get("area_slug"), ((Number) a.get("n")).longValue());
        }

        // áreas cujas mensagens chegaram recentemente por um nó de borda (campo via)
        Set<String> viaBorda = new HashSet<>();
        for (Map<String, Object> v : jdbc.queryForList("SELECT DISTINCT area_slug FROM telemetria "
                + "WHERE inquilino = ? AND via IS NOT NULL AND ts > now() - interval '6 hours'", inq)) {
            viaBorda.add((String) v.get("area_slug"));
        }

        List<Map<String, Object>> saida = new ArrayList<>();
        for (Map<String, Object> area : areas) {
            String slug = (String) area.get("slug");
            Double fluxo = null;
            Double velocidade = null;
            Double ocupacao = null;
            Instant ultimo = null;
            Instant tsVelocidade = null;
            for (Map<String, Object> e : estados) {
                if (!slug.equals(e.get("area_slug"))) {
                    continue;
                }
                double v = ((Number) e.get("valor")).doubleValue();
                Instant ts = ((Timestamp) e.get("ts")).toInstant();
                if (ultimo == null || ts.isAfter(ultimo)) {
                    ultimo = ts;
                }
                switch ((String) e.get("grandeza")) {
                    case "fluxo_veiculos" -> fluxo = v;
                    case "velocidade_media" -> { velocidade = v; tsVelocidade = ts; }
                    case "ocupacao_via" -> ocupacao = v;
                    default -> { }
                }
            }
            String nivel = "SEM_DADOS";
            boolean recente = tsVelocidade != null && tsVelocidade.isAfter(Instant.now().minusSeconds(validadeS));
            if (velocidade != null && recente) {
                nivel = velocidade >= fluidoMin ? "FLUIDO" : (velocidade >= congestionadoMax ? "MODERADO" : "CONGESTIONADO");
            }
            Map<String, Object> item = new LinkedHashMap<>(area);
            item.put("fluxo", fluxo);
            item.put("velocidade", velocidade);
            item.put("ocupacao", ocupacao);
            item.put("ts", ultimo == null ? null : ultimo.toString());
            item.put("alarmes", alarmes.getOrDefault(slug, 0L));
            item.put("nivel", nivel);
            item.put("via_borda", viaBorda.contains(slug));
            saida.add(item);
        }
        return saida;
    }

    /** Série bruta de uma grandeza numa área, para o gráfico. */
    @GetMapping("/api/v1/areas/{slug}/serie")
    public List<Map<String, Object>> serie(Authentication auth, @PathVariable("slug") String slug,
            @RequestParam(value = "grandeza", defaultValue = "velocidade_media") String grandeza,
            @RequestParam(value = "minutos", defaultValue = "60") int minutos) {
        int m = Math.min(Math.max(minutos, 1), 10080);
        return jdbc.queryForList(
                "SELECT ts, valor FROM telemetria WHERE inquilino = ? AND area_slug = ? AND grandeza = ? "
                        + "AND NOT suspeita AND ts > now() - ? * interval '1 minute' ORDER BY ts",
                Tenant.de(auth), slug, grandeza, m);
    }

    /** Média, mínimo e máximo por hora ou por dia (RF-PRO-04). */
    @GetMapping("/api/v1/areas/{slug}/agregado")
    public List<Map<String, Object>> agregado(Authentication auth, @PathVariable("slug") String slug,
            @RequestParam(value = "grandeza", defaultValue = "velocidade_media") String grandeza,
            @RequestParam(value = "janela", defaultValue = "hora") String janela,
            @RequestParam(value = "horas", defaultValue = "24") int horas) {
        String trunc = "dia".equalsIgnoreCase(janela) ? "day" : "hour";
        int h = Math.min(Math.max(horas, 1), 24 * 90);
        return jdbc.queryForList(
                "SELECT date_trunc('" + trunc + "', ts) AS periodo, avg(valor) AS media, min(valor) AS minimo, "
                        + "max(valor) AS maximo, count(*) AS amostras FROM telemetria "
                        + "WHERE inquilino = ? AND area_slug = ? AND grandeza = ? AND NOT suspeita "
                        + "AND ts > now() - ? * interval '1 hour' GROUP BY 1 ORDER BY 1",
                Tenant.de(auth), slug, grandeza, h);
    }

    /** Histórico de um dispositivo por grandeza e intervalo, com paginação, filtros e ordenação. */
    @GetMapping("/api/v1/dispositivos/{slug}/telemetria")
    public Pagina<Map<String, Object>> telemetria(Authentication auth, @PathVariable("slug") String slug,
            @RequestParam(value = "grandeza", required = false) String grandeza,
            @RequestParam(value = "de", required = false) String de,
            @RequestParam(value = "ate", required = false) String ate,
            @RequestParam(value = "ordem", defaultValue = "desc") String ordem,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        String inq = Tenant.de(auth);
        Instant fim = ate == null ? Instant.now() : instante(ate, "ate");
        Instant inicio = de == null ? fim.minusSeconds(24 * 3600) : instante(de, "de");
        int p = Paginacao.page(page);
        int s = Paginacao.size(size);

        StringBuilder where = new StringBuilder(" WHERE inquilino = ? AND dispositivo = ? AND ts >= ? AND ts <= ?");
        List<Object> args = new ArrayList<>();
        args.add(inq);
        args.add(slug);
        args.add(Timestamp.from(inicio));
        args.add(Timestamp.from(fim));
        if (grandeza != null && !grandeza.isBlank()) {
            where.append(" AND grandeza = ?");
            args.add(grandeza);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM telemetria" + where, Long.class, args.toArray());
        args.add(s);
        args.add(p * s);
        List<Map<String, Object>> itens = jdbc.queryForList(
                "SELECT ts, grandeza, valor, unidade, suspeita, atrasada, via FROM telemetria" + where
                        + " ORDER BY ts " + Paginacao.ordem(ordem) + " LIMIT ? OFFSET ?", args.toArray());
        return new Pagina<>(itens, p, s, total == null ? 0 : total);
    }

    /** Fila de mensagens inválidas (DLQ). */
    @GetMapping("/api/v1/mensagens-invalidas")
    public Pagina<Map<String, Object>> invalidas(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int p = Paginacao.page(page);
        int s = Paginacao.size(size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM mensagem_invalida", Long.class);
        List<Map<String, Object>> itens = jdbc.queryForList(
                "SELECT id, recebida_em, origem, motivo, payload FROM mensagem_invalida ORDER BY id DESC LIMIT ? OFFSET ?",
                s, p * s);
        return new Pagina<>(itens, p, s, total == null ? 0 : total);
    }

    private static Instant instante(String texto, String campo) {
        try {
            return Instant.parse(texto);
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "Parâmetro '" + campo + "' inválido: use ISO 8601, por exemplo 2026-10-20T10:00:00Z");
        }
    }
}
