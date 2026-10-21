package br.edu.unievangelica.iot.central.comum;

import java.util.regex.Pattern;

public final class Slugs {
    private static final Pattern PADRAO = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,59}$");

    private Slugs() {
    }

    public static String validar(String valor, String campo) {
        if (valor == null || !PADRAO.matcher(valor).matches()) {
            throw new ApiException(400, "Campo '" + campo
                    + "' inválido: use de 2 a 60 caracteres em minúsculas, números, '-' ou '_'");
        }
        return valor;
    }
}
