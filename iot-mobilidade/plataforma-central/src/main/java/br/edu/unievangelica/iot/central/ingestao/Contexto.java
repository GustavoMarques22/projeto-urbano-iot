package br.edu.unievangelica.iot.central.ingestao;

/**
 * De onde a mensagem veio.
 * origem: "mqtt", "http" ou "borda"; os demais campos são opcionais e servem para validações extras.
 */
public record Contexto(String origem, String inquilinoTopico, String dispositivoTopico, String token) {

    public static Contexto http(String token) {
        return new Contexto("http", null, null, token);
    }

    public static Contexto borda() {
        return new Contexto("borda", null, null, null);
    }

    public static Contexto mqtt(String inquilino, String dispositivo) {
        return new Contexto("mqtt", inquilino, dispositivo, null);
    }
}
