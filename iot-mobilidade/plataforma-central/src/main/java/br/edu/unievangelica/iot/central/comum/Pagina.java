package br.edu.unievangelica.iot.central.comum;

import java.util.List;

/** Resposta padrão de listagens (RF-APP-07): itens, página, tamanho e total. */
public record Pagina<T>(List<T> itens, int page, int size, long total) {
}
