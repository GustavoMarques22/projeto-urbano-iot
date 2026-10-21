package br.edu.unievangelica.iot.central.dados;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PainelController {

    @GetMapping("/")
    public String raiz() {
        return "redirect:/painel";
    }

    @GetMapping("/painel")
    public String painel(Model model, Authentication auth) {
        model.addAttribute("usuario", auth.getName());
        return "painel";
    }
}
