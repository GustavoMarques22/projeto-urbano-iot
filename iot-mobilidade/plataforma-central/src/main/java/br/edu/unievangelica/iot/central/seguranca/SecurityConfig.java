package br.edu.unievangelica.iot.central.seguranca;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Segurança do MVP (RF-SEG-01 a RF-SEG-04):
 * - painel e API de gestão exigem login (formulário ou HTTP Basic), com papéis;
 * - a ingestão de dispositivos e nós de borda usa token próprio, validado no serviço;
 * - cada usuário pertence a um inquilino (authority TENANT_xxx) e só enxerga os dados dele.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filtro(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/webhook-demo"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/ingestao", "/api/v1/ingestao/**",
                        "/api/v1/borda/**", "/webhook-demo").permitAll()
                .requestMatchers("/contratos/**", "/actuator/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/v3/api-docs/**", "/error", "/css/**", "/js/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/mensagens-invalidas").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole("ADMIN", "OPERADOR", "LEITOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/alarmes/*/reconhecer").hasAnyRole("ADMIN", "OPERADOR")
                .requestMatchers("/api/v1/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(Customizer.withDefaults())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    /** Usuários de demonstração. Troque as senhas fora do ambiente acadêmico. */
    @Bean
    public UserDetailsService usuarios() {
        UserDetails admin = User.withUsername("admin").password("{noop}admin123")
                .authorities("ROLE_ADMIN", "TENANT_semob").build();
        UserDetails operador = User.withUsername("operador").password("{noop}operador123")
                .authorities("ROLE_OPERADOR", "TENANT_semob").build();
        UserDetails leitor = User.withUsername("leitor").password("{noop}leitor123")
                .authorities("ROLE_LEITOR", "TENANT_semob").build();
        return new InMemoryUserDetailsManager(admin, operador, leitor);
    }
}
