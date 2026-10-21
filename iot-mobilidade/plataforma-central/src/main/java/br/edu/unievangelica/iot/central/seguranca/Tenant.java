package br.edu.unievangelica.iot.central.seguranca;

import br.edu.unievangelica.iot.central.comum.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Descobre o inquilino do usuário autenticado (RF-SEG-03: isolamento de dados). */
public final class Tenant {
    private Tenant() {
    }

    public static String de(Authentication auth) {
        if (auth != null) {
            for (GrantedAuthority g : auth.getAuthorities()) {
                if (g.getAuthority().startsWith("TENANT_")) {
                    return g.getAuthority().substring("TENANT_".length());
                }
            }
        }
        throw new ApiException(403, "Usuário sem inquilino associado");
    }
}
