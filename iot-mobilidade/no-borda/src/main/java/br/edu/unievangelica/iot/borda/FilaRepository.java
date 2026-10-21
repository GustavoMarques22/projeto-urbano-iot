package br.edu.unievangelica.iot.borda;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Fila persistente do nó de borda (store-and-forward). Nada é apagado antes da confirmação da central. */
@Repository
public class FilaRepository {

    public record Item(String idMensagem, String payload) {
    }

    private final JdbcTemplate jdbc;

    public FilaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void criarTabela() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS fila ("
                + "id_mensagem TEXT PRIMARY KEY, "
                + "ts_ms INTEGER NOT NULL, "
                + "prioridade INTEGER NOT NULL DEFAULT 0, "
                + "payload TEXT NOT NULL, "
                + "criada_em TEXT NOT NULL, "
                + "enviada INTEGER NOT NULL DEFAULT 0, "
                + "resultado TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_fila_pendente ON fila (enviada, prioridade, ts_ms)");
    }

    /** Retorna false se o id_mensagem já estava na fila (duplicata local). */
    public boolean enfileirar(String idMensagem, long tsMs, int prioridade, String payload) {
        int n = jdbc.update("INSERT OR IGNORE INTO fila (id_mensagem, ts_ms, prioridade, payload, criada_em) "
                + "VALUES (?, ?, ?, ?, ?)", idMensagem, tsMs, prioridade, payload, Instant.now().toString());
        return n > 0;
    }

    /** Alarmes/eventos (prioridade maior) saem antes da telemetria rotineira; depois, pelo horário da medição. */
    public List<Item> proximoLote(int max) {
        return jdbc.query("SELECT id_mensagem, payload FROM fila WHERE enviada = 0 "
                        + "ORDER BY prioridade DESC, ts_ms ASC LIMIT ?",
                (rs, i) -> new Item(rs.getString("id_mensagem"), rs.getString("payload")), max);
    }

    public void marcarEnviada(String idMensagem, String resultado) {
        jdbc.update("UPDATE fila SET enviada = 1, resultado = ? WHERE id_mensagem = ?", resultado, idMensagem);
    }

    public int pendentes() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM fila WHERE enviada = 0", Integer.class);
        return n == null ? 0 : n;
    }

    public int enviadas() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM fila WHERE enviada = 1", Integer.class);
        return n == null ? 0 : n;
    }

    /** Limpeza: remove o que já foi confirmado pela central há mais de um dia. */
    public void limparAntigas() {
        jdbc.update("DELETE FROM fila WHERE enviada = 1 AND criada_em < ?", Instant.now().minusSeconds(86400).toString());
    }
}
