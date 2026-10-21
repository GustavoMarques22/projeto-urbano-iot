package br.edu.unievangelica.iot.central.ingestao;

/** status: ACEITA, DUPLICADA, RECUSADA ou NAO_AUTORIZADA. */
public record ResultadoMensagem(String id_mensagem, String status, String motivo) {
}
