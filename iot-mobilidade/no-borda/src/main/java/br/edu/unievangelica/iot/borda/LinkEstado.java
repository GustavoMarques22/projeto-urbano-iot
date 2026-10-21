package br.edu.unievangelica.iot.borda;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Estado do enlace com a central. O link pode ser "desligado" por API para simular a queda (demonstração). */
@Component
public class LinkEstado {

    private final AtomicBoolean ligado = new AtomicBoolean(true);
    private volatile Instant ultimaSincronizacao;

    public boolean ligado() {
        return ligado.get();
    }

    public void definir(boolean valor) {
        ligado.set(valor);
    }

    public Instant ultimaSincronizacao() {
        return ultimaSincronizacao;
    }

    public void sincronizouAgora() {
        ultimaSincronizacao = Instant.now();
    }
}
