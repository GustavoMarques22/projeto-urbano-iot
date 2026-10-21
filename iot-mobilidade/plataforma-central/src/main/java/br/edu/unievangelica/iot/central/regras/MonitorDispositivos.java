package br.edu.unievangelica.iot.central.regras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Regra "equipamento sem dados" (RF-DIS-06): usa o intervalo esperado do perfil.
 * Sem mensagens por 3 vezes o intervalo, abre o alarme; quando volta a enviar, resolve.
 */
@Component
public class MonitorDispositivos {

    private static final Logger log = LoggerFactory.getLogger(MonitorDispositivos.class);

    private final JdbcTemplate jdbc;

    public MonitorDispositivos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void verificar() {
        try {
            int abertos = jdbc.update(
                    "INSERT INTO alarme (inquilino, chave, regra_nome, dispositivo, area_slug, severidade, estado, "
                            + "mensagem, aberto_em) "
                            + "SELECT d.inquilino, 'sem_dados:' || d.slug, 'Equipamento sem dados', d.slug, d.area_slug, "
                            + "'MEDIA', 'ATIVO', 'Nenhuma mensagem em 3 vezes o intervalo esperado', now() "
                            + "FROM dispositivo d JOIN perfil p ON p.inquilino = d.inquilino AND p.slug = d.perfil_slug "
                            + "WHERE d.ativo AND d.ultimo_visto IS NOT NULL "
                            + "AND d.ultimo_visto < now() - p.intervalo_s * 3 * interval '1 second' "
                            + "ON CONFLICT (chave) WHERE estado <> 'RESOLVIDO' DO NOTHING");
            if (abertos > 0) {
                log.warn("{} equipamento(s) sem dados: alarme aberto", abertos);
            }
            jdbc.update(
                    "UPDATE alarme a SET estado = 'RESOLVIDO', resolvido_em = now() "
                            + "FROM dispositivo d JOIN perfil p ON p.inquilino = d.inquilino AND p.slug = d.perfil_slug "
                            + "WHERE a.chave = 'sem_dados:' || d.slug AND a.estado <> 'RESOLVIDO' "
                            + "AND d.ultimo_visto >= now() - p.intervalo_s * 3 * interval '1 second'");
        } catch (Exception e) {
            log.error("Falha ao verificar equipamentos sem dados: {}", e.getMessage());
        }
    }
}
