package br.edu.unievangelica.iot.central.catalogo;

import br.edu.unievangelica.iot.central.comum.ApiException;
import br.edu.unievangelica.iot.central.comum.Pagina;
import br.edu.unievangelica.iot.central.comum.Paginacao;
import br.edu.unievangelica.iot.central.comum.Slugs;
import br.edu.unievangelica.iot.central.seguranca.Tenant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Catálogo configurável (RF-CEN-01 a 03) e gestão de dispositivos (RF-DIS-01, 02, 06). */
@RestController
public class CatalogoController {

    public record DominioReq(String slug, String nome) {
    }

    public record SiteReq(String slug, String nome) {
    }

    public record AreaReq(String site, String slug, String nome, Double lat, Double lon) {
    }

    public record GrandezaReq(String grandeza, String unidade, Double min, Double max) {
    }

    public record PerfilReq(String dominio, String slug, String nome, Integer intervaloS,
                            List<GrandezaReq> grandezas) {
    }

    public record DispositivoReq(String slug, String dominio, String site, String area, String perfil,
                                 String fabricante, String firmware) {
    }

    private final CatalogoRepository repo;

    public CatalogoController(CatalogoRepository repo) {
        this.repo = repo;
    }

    @PostMapping("/api/v1/catalogo/dominios")
    public ResponseEntity<Map<String, Object>> criarDominio(Authentication auth, @RequestBody DominioReq r) {
        String inq = Tenant.de(auth);
        repo.criarDominio(inq, Slugs.validar(r.slug(), "slug"), nome(r.nome()));
        return ResponseEntity.status(201).body(Map.of("slug", r.slug(), "nome", r.nome()));
    }

    @GetMapping("/api/v1/catalogo/dominios")
    public List<Map<String, Object>> dominios(Authentication auth) {
        return repo.dominios(Tenant.de(auth));
    }

    @PostMapping("/api/v1/catalogo/sites")
    public ResponseEntity<Map<String, Object>> criarSite(Authentication auth, @RequestBody SiteReq r) {
        repo.criarSite(Tenant.de(auth), Slugs.validar(r.slug(), "slug"), nome(r.nome()));
        return ResponseEntity.status(201).body(Map.of("slug", r.slug(), "nome", r.nome()));
    }

    @PostMapping("/api/v1/catalogo/areas")
    public ResponseEntity<Map<String, Object>> criarArea(Authentication auth, @RequestBody AreaReq r) {
        repo.criarArea(Tenant.de(auth), Slugs.validar(r.site(), "site"), Slugs.validar(r.slug(), "slug"),
                nome(r.nome()), r.lat(), r.lon());
        return ResponseEntity.status(201).body(Map.of("slug", r.slug(), "nome", r.nome()));
    }

    @PostMapping("/api/v1/catalogo/perfis")
    public ResponseEntity<Map<String, Object>> criarPerfil(Authentication auth, @RequestBody PerfilReq r) {
        Slugs.validar(r.slug(), "slug");
        Slugs.validar(r.dominio(), "dominio");
        if (r.intervaloS() == null) {
            throw new ApiException(400, "Campo 'intervaloS' é obrigatório");
        }
        int id = repo.criarPerfil(Tenant.de(auth), r.dominio(), r.slug(), nome(r.nome()), r.intervaloS(),
                r.grandezas());
        return ResponseEntity.status(201).body(Map.of("id", id, "slug", r.slug()));
    }

    @GetMapping("/api/v1/catalogo/perfis")
    public List<Map<String, Object>> perfis(Authentication auth) {
        return repo.perfis(Tenant.de(auth));
    }

    @PostMapping("/api/v1/dispositivos")
    public ResponseEntity<Map<String, Object>> criarDispositivo(Authentication auth, @RequestBody DispositivoReq r) {
        Slugs.validar(r.slug(), "slug");
        Slugs.validar(r.dominio(), "dominio");
        Slugs.validar(r.site(), "site");
        Slugs.validar(r.area(), "area");
        Slugs.validar(r.perfil(), "perfil");
        String token = UUID.randomUUID().toString().replace("-", "");
        repo.criarDispositivo(Tenant.de(auth), r, token);
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("slug", r.slug());
        corpo.put("token", token);
        corpo.put("aviso", "Guarde o token: ele não é exibido novamente.");
        return ResponseEntity.status(201).body(corpo);
    }

    @GetMapping("/api/v1/dispositivos")
    public Pagina<Map<String, Object>> listar(Authentication auth,
            @RequestParam(value = "area", required = false) String area,
            @RequestParam(value = "situacao", required = false) String situacao,
            @RequestParam(value = "ordem", defaultValue = "asc") String ordem,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        String inq = Tenant.de(auth);
        int p = Paginacao.page(page);
        int s = Paginacao.size(size);
        List<Map<String, Object>> itens = repo.dispositivos(inq, area, situacao, Paginacao.ordem(ordem), s, p * s);
        return new Pagina<>(itens, p, s, repo.totalDispositivos(inq, area, situacao));
    }

    /** Revoga a credencial: o dispositivo passa a ser recusado na central e nos nós de borda (RF-DIS-02). */
    @PostMapping("/api/v1/dispositivos/{slug}/revogar")
    public Map<String, Object> revogar(Authentication auth, @PathVariable("slug") String slug) {
        return alterarAtivo(auth, slug, false);
    }

    @PostMapping("/api/v1/dispositivos/{slug}/ativar")
    public Map<String, Object> ativar(Authentication auth, @PathVariable("slug") String slug) {
        return alterarAtivo(auth, slug, true);
    }

    private Map<String, Object> alterarAtivo(Authentication auth, String slug, boolean ativo) {
        if (!repo.definirAtivo(Tenant.de(auth), slug, ativo)) {
            throw new ApiException(404, "Dispositivo não encontrado: " + slug);
        }
        return Map.of("slug", slug, "ativo", ativo);
    }

    private static String nome(String n) {
        if (n == null || n.isBlank()) {
            throw new ApiException(400, "Campo 'nome' é obrigatório");
        }
        return n;
    }
}
