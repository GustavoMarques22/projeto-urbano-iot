package br.edu.unievangelica.iot.central.regras;

/** Regra condição -> ação, cadastrada no catálogo (grandeza2 é opcional). */
public record Regra(int id, String nome, String grandeza, String operador, double limite,
                    String grandeza2, String operador2, Double limite2,
                    int janelaS, String severidade, String webhookUrl) {
}
