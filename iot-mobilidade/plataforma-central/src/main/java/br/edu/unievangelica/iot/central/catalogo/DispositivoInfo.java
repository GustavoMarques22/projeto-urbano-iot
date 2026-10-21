package br.edu.unievangelica.iot.central.catalogo;

/** Dados de cadastro de um dispositivo usados no pipeline de ingestão. */
public record DispositivoInfo(String inquilino, String slug, String dominio, String site, String area,
                              int perfilId, int intervaloS, String token, boolean ativo) {
}
