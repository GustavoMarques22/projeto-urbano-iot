package br.edu.unievangelica.iot.central.seguranca;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Página de login própria (o Spring Security processa o POST /login). */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
