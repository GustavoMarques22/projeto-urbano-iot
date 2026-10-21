package br.edu.unievangelica.iot.central.catalogo;

/** Unidade e faixa válida de uma grandeza no perfil do dispositivo. */
public record FaixaGrandeza(String unidade, Double min, Double max) {
}
