package br.edu.unievangelica.iot.central.comum;

/** Erro de negócio com código HTTP. */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String mensagem) {
        super(mensagem);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
