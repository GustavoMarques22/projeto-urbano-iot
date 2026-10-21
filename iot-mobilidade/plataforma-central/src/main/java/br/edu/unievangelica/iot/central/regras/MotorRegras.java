package br.edu.unievangelica.iot.central.regras;

import br.edu.unievangelica.iot.central.catalogo.DispositivoInfo;
import br.edu.unievangelica.iot.central.comum.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Motor de regras (RF-PRO-02 e RF-PRO-05). As regras ficam no banco: o código não conhece "trânsito".
 *
 * Janela 0: usa o último valor da grandeza. Janela > 0: a condição precisa valer em TODAS as medições da janela
 * (mínimo de 2 amostras, cobrindo ao menos 80% da janela). Abre o alarme quando a condição passa a valer
 * e o resolve automaticamente quando deixa de valer. O horário do alarme é o da medição.
 */
@Component
public class MotorRegras {

    private static final Logger log = LoggerFactory.getLogger(MotorRegras.class);

    private final JdbcTemplate jdbc;
    private final WebhookClient webhook;
    private final ObjectMapper mapper;

    public MotorRegras(JdbcTemplate jdbc, WebhookClient webhook, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.webhook = webhook;
        this.mapper = mapper;
    }

    /** Traduz o operador para SQL, aceitando apenas os da lista (evita injeção). */
    public static String sqlOperador(String op) {
        if (op == null) {
            throw new ApiException(400, "Operador obrigatório");
        }
        switch (op) {
            case "<":
            case "<=":
            case ">":
            case ">=":
            case "=":
                return op;
            case "!=":
                return "<>";
            default:
                throw new ApiException(400, "Operador inválido: " + op + " (use <, <=, >, >=, = ou !=)");
        }
    }

    public void avaliar(DispositivoInfo d, Instant ts, Set<String> recebidas) {
        List<Regra> regras = jdbc.query(
                "SELECT id, nome, grandeza, operador, limite, grandeza2, operador2, limite2, janela_s, severidade, "
                        + "webhook_url FROM regra WHERE inquilino = ? AND dominio_slug = ? AND ativa",
                (rs, i) -> new Regra(rs.getInt("id"), rs.getString("nome"), rs.getString("grandeza"),
                        rs.getString("operador"), rs.getDouble("limite"), rs.getString("grandeza2"),
                        rs.getString("operador2"), (Double) rs.getObject("limite2"), rs.getInt("janela_s"),
                        rs.getString("severidade"), rs.getString("webhook_url")),
                d.inquilino(), d.dominio());

        for (Regra r : regras) {
            boolean envolve = recebidas.contains(r.grandeza())
                    || (r.grandeza2() != null && recebidas.contains(r.grandeza2()));
            if (!envolve) {
                continue;
            }
            boolean atendida = condicao(d, r.grandeza(), r.operador(), r.limite(), r.janelaS(), ts);
            if (atendida && r.grandeza2() != null && r.operador2() != null && r.limite2() != null) {
                atendida = condicao(d, r.grandeza2(), r.operador2(), r.limite2(), r.janelaS(), ts);
            }
            String chave = "regra:" + r.id() + ":" + d.slug();
            if (atendida) {
                abrir(r, d, ts, chave);
            } else {
                resolver(chave, ts);
            }
        }
    }

    private boolean condicao(DispositivoInfo d, String grandeza, String operador, double limite, int janelaS,
                             Instant ts) {
        String op = sqlOperador(operador);
        if (janelaS <= 0) {
            List<Boolean> lista = jdbc.query(
                    "SELECT (valor " + op + " ?) AS ok FROM ultimo_estado WHERE dispositivo = ? AND grandeza = ?",
                    (rs, i) -> rs.getBoolean("ok"), limite, d.slug(), grandeza);
            return !lista.isEmpty() && lista.get(0);
        }
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT count(*) AS n, min(ts) AS primeiro, "
                        + "coalesce(sum(CASE WHEN valor " + op + " ? THEN 0 ELSE 1 END), 0) AS violacoes "
                        + "FROM telemetria WHERE dispositivo = ? AND grandeza = ? AND NOT suspeita "
                        + "AND ts > ? AND ts <= ?",
                limite, d.slug(), grandeza, Timestamp.from(ts.minusSeconds(janelaS)), Timestamp.from(ts));
        long n = ((Number) r.get("n")).longValue();
        long violacoes = ((Number) r.get("violacoes")).longValue();
        Timestamp primeiro = (Timestamp) r.get("primeiro");
        if (n < 2 || violacoes > 0 || primeiro == null) {
            return false;
        }
        return !primeiro.toInstant().isAfter(ts.minusSeconds(Math.round(janelaS * 0.8)));
    }

    private void abrir(Regra r, DispositivoInfo d, Instant ts, String chave) {
        String mensagem = "Regra '" + r.nome() + "' atendida em " + d.slug();
        int novo = jdbc.update(
                "INSERT INTO alarme (inquilino, chave, regra_nome, dispositivo, area_slug, severidade, estado, "
                        + "mensagem, aberto_em) VALUES (?, ?, ?, ?, ?, ?, 'ATIVO', ?, ?) "
                        + "ON CONFLICT (chave) WHERE estado <> 'RESOLVIDO' DO NOTHING",
                d.inquilino(), chave, r.nome(), d.slug(), d.area(), r.severidade(), mensagem, Timestamp.from(ts));
        if (novo > 0) {
            log.warn("ALARME ABERTO [{}] {} - {} ({})", r.severidade(), r.nome(), d.slug(), d.area());
            try {
                Map<String, Object> corpo = new LinkedHashMap<>();
                corpo.put("evento", "alarme_aberto");
                corpo.put("regra", r.nome());
                corpo.put("severidade", r.severidade());
                corpo.put("dispositivo", d.slug());
                corpo.put("area", d.area());
                corpo.put("aberto_em", ts.toString());
                webhook.disparar(r.webhookUrl(), mapper.writeValueAsString(corpo));
            } catch (Exception e) {
                log.warn("Não foi possível montar o webhook: {}", e.getMessage());
            }
        }
    }

    private void resolver(String chave, Instant ts) {
        int n = jdbc.update("UPDATE alarme SET estado = 'RESOLVIDO', resolvido_em = ? "
                + "WHERE chave = ? AND estado <> 'RESOLVIDO'", Timestamp.from(ts), chave);
        if (n > 0) {
            log.info("Alarme resolvido automaticamente: {}", chave);
        }
    }
}
