package br.edu.unievangelica.iot.central.catalogo;

import br.edu.unievangelica.iot.central.comum.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogoRepository {

    private final JdbcTemplate jdbc;

    public CatalogoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- leitura usada pela ingestão ----------

    public Optional<DispositivoInfo> dispositivo(String slug) {
        List<DispositivoInfo> lista = jdbc.query(
                "SELECT d.inquilino, d.slug, d.dominio_slug, d.site_slug, d.area_slug, d.token, d.ativo, "
                        + "p.id AS perfil_id, p.intervalo_s "
                        + "FROM dispositivo d JOIN perfil p ON p.inquilino = d.inquilino AND p.slug = d.perfil_slug "
                        + "WHERE d.slug = ?",
                (rs, i) -> new DispositivoInfo(rs.getString("inquilino"), rs.getString("slug"),
                        rs.getString("dominio_slug"), rs.getString("site_slug"), rs.getString("area_slug"),
                        rs.getInt("perfil_id"), rs.getInt("intervalo_s"), rs.getString("token"),
                        rs.getBoolean("ativo")),
                slug);
        return lista.stream().findFirst();
    }

    public Map<String, FaixaGrandeza> faixas(int perfilId) {
        Map<String, FaixaGrandeza> mapa = new HashMap<>();
        jdbc.query("SELECT grandeza, unidade, valor_min, valor_max FROM perfil_grandeza WHERE perfil_id = ?",
                rs -> {
                    mapa.put(rs.getString("grandeza"), new FaixaGrandeza(rs.getString("unidade"),
                            (Double) rs.getObject("valor_min"), (Double) rs.getObject("valor_max")));
                }, perfilId);
        return mapa;
    }

    public boolean existe(String sql, Object... args) {
        return !jdbc.queryForList(sql, args).isEmpty();
    }

    // ---------- cadastro (catálogo configurável) ----------

    public void criarDominio(String inquilino, String slug, String nome) {
        try {
            jdbc.update("INSERT INTO dominio (inquilino, slug, nome) VALUES (?, ?, ?)", inquilino, slug, nome);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "Domínio já existe: " + slug);
        }
    }

    public void criarSite(String inquilino, String slug, String nome) {
        try {
            jdbc.update("INSERT INTO site (inquilino, slug, nome) VALUES (?, ?, ?)", inquilino, slug, nome);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "Local já existe: " + slug);
        }
    }

    public void criarArea(String inquilino, String site, String slug, String nome, Double lat, Double lon) {
        if (!existe("SELECT 1 FROM site WHERE inquilino = ? AND slug = ?", inquilino, site)) {
            throw new ApiException(400, "Local não encontrado: " + site);
        }
        try {
            jdbc.update("INSERT INTO area (inquilino, slug, site_slug, nome, lat, lon) VALUES (?, ?, ?, ?, ?, ?)",
                    inquilino, slug, site, nome, lat, lon);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "Área já existe: " + slug);
        }
    }

    public int criarPerfil(String inquilino, String dominio, String slug, String nome, int intervaloS,
                           List<CatalogoController.GrandezaReq> grandezas) {
        if (!existe("SELECT 1 FROM dominio WHERE inquilino = ? AND slug = ?", inquilino, dominio)) {
            throw new ApiException(400, "Domínio não encontrado: " + dominio);
        }
        if (intervaloS < 1) {
            throw new ApiException(400, "intervaloS deve ser maior que zero");
        }
        Integer id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO perfil (inquilino, slug, dominio_slug, nome, intervalo_s) VALUES (?, ?, ?, ?, ?) RETURNING id",
                    Integer.class, inquilino, slug, dominio, nome, intervaloS);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "Perfil já existe: " + slug);
        }
        if (grandezas != null) {
            for (CatalogoController.GrandezaReq g : grandezas) {
                jdbc.update("INSERT INTO perfil_grandeza (perfil_id, grandeza, unidade, valor_min, valor_max) "
                        + "VALUES (?, ?, ?, ?, ?)", id, g.grandeza(), g.unidade(), g.min(), g.max());
            }
        }
        return id;
    }

    public void criarDispositivo(String inquilino, CatalogoController.DispositivoReq r, String token) {
        if (!existe("SELECT 1 FROM dominio WHERE inquilino = ? AND slug = ?", inquilino, r.dominio())) {
            throw new ApiException(400, "Domínio não encontrado: " + r.dominio());
        }
        if (!existe("SELECT 1 FROM site WHERE inquilino = ? AND slug = ?", inquilino, r.site())) {
            throw new ApiException(400, "Local não encontrado: " + r.site());
        }
        if (!existe("SELECT 1 FROM area WHERE inquilino = ? AND slug = ?", inquilino, r.area())) {
            throw new ApiException(400, "Área não encontrada: " + r.area());
        }
        if (!existe("SELECT 1 FROM perfil WHERE inquilino = ? AND slug = ?", inquilino, r.perfil())) {
            throw new ApiException(400, "Perfil não encontrado: " + r.perfil());
        }
        try {
            jdbc.update("INSERT INTO dispositivo (slug, inquilino, dominio_slug, site_slug, area_slug, perfil_slug, "
                            + "fabricante, firmware, token) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    r.slug(), inquilino, r.dominio(), r.site(), r.area(), r.perfil(), r.fabricante(), r.firmware(),
                    token);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "Dispositivo já existe: " + r.slug());
        }
    }

    public boolean definirAtivo(String inquilino, String slug, boolean ativo) {
        return jdbc.update("UPDATE dispositivo SET ativo = ? WHERE slug = ? AND inquilino = ?", ativo, slug,
                inquilino) > 0;
    }

    // ---------- listagens ----------

    public List<Map<String, Object>> dominios(String inquilino) {
        return jdbc.queryForList("SELECT slug, nome FROM dominio WHERE inquilino = ? ORDER BY nome", inquilino);
    }

    public List<Map<String, Object>> perfis(String inquilino) {
        return jdbc.queryForList("SELECT p.slug, p.nome, p.dominio_slug, p.intervalo_s, "
                + "(SELECT string_agg(g.grandeza || ' (' || coalesce(g.unidade, '') || ')', ', ' ORDER BY g.grandeza) "
                + " FROM perfil_grandeza g WHERE g.perfil_id = p.id) AS grandezas "
                + "FROM perfil p WHERE p.inquilino = ? ORDER BY p.nome", inquilino);
    }

    public long totalDispositivos(String inquilino, String area, String situacao) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM dispositivo d JOIN perfil p "
                + "ON p.inquilino = d.inquilino AND p.slug = d.perfil_slug WHERE d.inquilino = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(inquilino);
        filtrosDispositivo(sql, args, area, situacao);
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> dispositivos(String inquilino, String area, String situacao, String ordem,
                                                  int limite, int deslocamento) {
        StringBuilder sql = new StringBuilder(
                "SELECT d.slug, d.dominio_slug, d.site_slug, d.area_slug, d.perfil_slug, d.fabricante, d.ativo, "
                        + "d.ultimo_visto, p.intervalo_s, " + CASE_SITUACAO + " AS situacao "
                        + "FROM dispositivo d JOIN perfil p ON p.inquilino = d.inquilino AND p.slug = d.perfil_slug "
                        + "WHERE d.inquilino = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(inquilino);
        filtrosDispositivo(sql, args, area, situacao);
        sql.append(" ORDER BY d.slug ").append(ordem).append(" LIMIT ? OFFSET ?");
        args.add(limite);
        args.add(deslocamento);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private static final String CASE_SITUACAO =
            "CASE WHEN d.ultimo_visto IS NOT NULL AND d.ultimo_visto >= now() - p.intervalo_s * 3 * interval '1 second' "
                    + "THEN 'ONLINE' ELSE 'OFFLINE' END";

    private void filtrosDispositivo(StringBuilder sql, List<Object> args, String area, String situacao) {
        if (area != null && !area.isBlank()) {
            sql.append(" AND d.area_slug = ?");
            args.add(area);
        }
        if (situacao != null && !situacao.isBlank()) {
            sql.append(" AND ").append(CASE_SITUACAO).append(" = ?");
            args.add(situacao.toUpperCase());
        }
    }
}
