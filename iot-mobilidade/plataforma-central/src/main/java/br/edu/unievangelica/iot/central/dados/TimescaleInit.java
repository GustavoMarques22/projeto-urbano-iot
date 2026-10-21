package br.edu.unievangelica.iot.central.dados;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Se o PostgreSQL tiver a extensão TimescaleDB, transforma a tabela de telemetria em hipertabela.
 * Sem a extensão, a plataforma continua funcionando com uma tabela comum.
 */
@Component
public class TimescaleInit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimescaleInit.class);

    private final JdbcTemplate jdbc;

    public TimescaleInit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
            jdbc.queryForList("SELECT create_hypertable('telemetria', 'ts', if_not_exists => TRUE, migrate_data => TRUE)");
            log.info("TimescaleDB ativo: telemetria é uma hipertabela");
        } catch (Exception e) {
            log.warn("TimescaleDB indisponível ({}). Usando tabela comum do PostgreSQL.", e.getMessage());
        }
    }
}
