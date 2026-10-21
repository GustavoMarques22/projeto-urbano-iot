package br.edu.unievangelica.iot.central.ingestao;

import br.edu.unievangelica.iot.central.catalogo.CatalogoRepository;
import br.edu.unievangelica.iot.central.catalogo.DispositivoInfo;
import br.edu.unievangelica.iot.central.catalogo.FaixaGrandeza;
import br.edu.unievangelica.iot.central.regras.MotorRegras;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pipeline de ingestão (Pipes and Filters):
 * validar -> autenticar -> deduplicar -> ordenar pelo horário da medição -> enriquecer -> persistir -> regras.
 * Cada mensagem é processada em uma transação própria; mensagens recusadas vão para a DLQ.
 */
@Service
public class IngestaoService {

    private static final Logger log = LoggerFactory.getLogger(IngestaoService.class);

    private final JdbcTemplate jdbc;
    private final CatalogoRepository catalogo;
    private final MensagemValidator validator;
    private final MotorRegras motor;
    private final MeterRegistry metricas;
    private final long limiteAtrasoS;

    public IngestaoService(JdbcTemplate jdbc, CatalogoRepository catalogo, MensagemValidator validator,
                           MotorRegras motor, MeterRegistry metricas,
                           @Value("${plataforma.limite-atraso-s:600}") long limiteAtrasoS) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
        this.validator = validator;
        this.motor = motor;
        this.metricas = metricas;
        this.limiteAtrasoS = limiteAtrasoS;
    }

    @Transactional
    public ResultadoMensagem processar(JsonNode msg, Contexto ctx) {
        String id = (msg != null && msg.hasNonNull("id_mensagem")) ? msg.get("id_mensagem").asText() : null;
        MDC.put("correlationId", id == null ? "sem-id" : id);
        try {
            // 1. validar (esquema + LGPD)
            List<String> erros = validator.validar(msg);
            if (!erros.isEmpty()) {
                return recusar(ctx, id, String.join("; ", erros), msg, "RECUSADA");
            }
            String slug = msg.get("dispositivo").asText();

            // 2. autenticar / autorizar
            if (ctx.dispositivoTopico() != null && !ctx.dispositivoTopico().equals(slug)) {
                return recusar(ctx, id, "Dispositivo do tópico (" + ctx.dispositivoTopico()
                        + ") diferente do dispositivo da mensagem (" + slug + ")", msg, "RECUSADA");
            }
            Optional<DispositivoInfo> opt = catalogo.dispositivo(slug);
            if (opt.isEmpty()) {
                return recusar(ctx, id, "Dispositivo não cadastrado: " + slug, msg, "RECUSADA");
            }
            DispositivoInfo d = opt.get();
            if (ctx.inquilinoTopico() != null && !ctx.inquilinoTopico().equals(d.inquilino())) {
                return recusar(ctx, id, "Inquilino do tópico não corresponde ao dispositivo", msg, "RECUSADA");
            }
            if (!d.ativo()) {
                return recusar(ctx, id, "Credencial revogada: dispositivo inativo", msg, "NAO_AUTORIZADA");
            }
            if ("http".equals(ctx.origem()) && (ctx.token() == null || !ctx.token().equals(d.token()))) {
                return recusar(ctx, id, "Token do dispositivo ausente ou inválido", msg, "NAO_AUTORIZADA");
            }

            // 3. deduplicar (Idempotent Receiver)
            int novo = jdbc.update("INSERT INTO mensagem_processada (id_mensagem, inquilino) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    id, d.inquilino());
            if (novo == 0) {
                contar("duplicada");
                return new ResultadoMensagem(id, "DUPLICADA", null);
            }

            // 4. ordenar e enriquecer: vale o horário da medição, não o da chegada
            Instant ts = OffsetDateTime.parse(msg.get("timestamp").asText()).toInstant();
            boolean atrasada = ts.isBefore(Instant.now().minusSeconds(limiteAtrasoS));
            String via = msg.hasNonNull("via") ? msg.get("via").asText() : null;
            Map<String, FaixaGrandeza> faixas = catalogo.faixas(d.perfilId());

            // 5. persistir
            Set<String> recebidas = new HashSet<>();
            for (JsonNode m : msg.get("medidas")) {
                String grandeza = m.get("grandeza").asText();
                double valor = m.get("valor").asDouble();
                FaixaGrandeza f = faixas.get(grandeza);
                boolean suspeita = f == null
                        || (f.min() != null && valor < f.min())
                        || (f.max() != null && valor > f.max());
                String unidade = m.hasNonNull("unidade") ? m.get("unidade").asText() : (f != null ? f.unidade() : null);

                jdbc.update("INSERT INTO telemetria (ts, inquilino, dispositivo, site_slug, area_slug, dominio_slug, "
                                + "grandeza, valor, unidade, suspeita, atrasada, id_mensagem, via) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Timestamp.from(ts), d.inquilino(), d.slug(), d.site(), d.area(), d.dominio(),
                        grandeza, valor, unidade, suspeita, atrasada, id, via);

                if (!suspeita) {
                    jdbc.update("INSERT INTO ultimo_estado (inquilino, dispositivo, grandeza, ts, valor, unidade) "
                                    + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (dispositivo, grandeza) DO UPDATE SET "
                                    + "ts = EXCLUDED.ts, valor = EXCLUDED.valor, unidade = EXCLUDED.unidade "
                                    + "WHERE ultimo_estado.ts <= EXCLUDED.ts",
                            d.inquilino(), d.slug(), grandeza, Timestamp.from(ts), valor, unidade);
                    recebidas.add(grandeza);
                } else {
                    log.warn("Medida suspeita (fora da faixa ou fora do perfil): {}={} em {}", grandeza, valor, slug);
                }
            }

            // 6. estado do dispositivo e regras (dado atrasado não dispara alarme como se fosse atual)
            if (!atrasada) {
                jdbc.update("UPDATE dispositivo SET ultimo_visto = now() WHERE slug = ?", d.slug());
                motor.avaliar(d, ts, recebidas);
            }
            contar(atrasada ? "atrasada" : "aceita");
            return new ResultadoMensagem(id, "ACEITA", atrasada ? "dado atrasado, gravado pelo horário da medição" : null);
        } finally {
            MDC.remove("correlationId");
        }
    }

    /** Valida sem gravar nada (sandbox do integrador, RF-INT-06). */
    public List<String> apenasValidar(JsonNode msg) {
        return validator.validar(msg);
    }

    private ResultadoMensagem recusar(Contexto ctx, String id, String motivo, JsonNode msg, String status) {
        log.warn("Mensagem {} ({}): {}", status, ctx.origem(), motivo);
        registrarInvalida(ctx.origem(), motivo, msg == null ? null : msg.toString());
        contar(status.toLowerCase());
        return new ResultadoMensagem(id, status, motivo);
    }

    /** Dead Letter Channel: nenhuma mensagem some em silêncio. */
    public void registrarInvalida(String origem, String motivo, String payload) {
        String corpo = payload != null && payload.length() > 4000 ? payload.substring(0, 4000) : payload;
        jdbc.update("INSERT INTO mensagem_invalida (origem, motivo, payload) VALUES (?, ?, ?)", origem, motivo, corpo);
    }

    private void contar(String resultado) {
        metricas.counter("iot.mensagens", "resultado", resultado).increment();
    }
}
