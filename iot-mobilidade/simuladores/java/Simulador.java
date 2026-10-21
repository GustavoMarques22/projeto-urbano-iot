import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Simulador de contadores de veículos em Java, via HTTP (integração de nível 1 pela API REST).
 * Roda sem dependências, direto com o JDK:
 *
 *   java Simulador.java
 *   java Simulador.java --destino http://localhost:8080/api/v1/ingestao --intervalo 5
 *   java Simulador.java --congestionar cruz-brasil-rua10 --congestionar-apos 20 --congestionar-por 120
 */
public class Simulador {

    record Contador(String site, String area, String dispositivo) { }

    static final List<Contador> CONTADORES = List.of(
            new Contador("centro", "cruz-brasil-rua10", "contador-brasil-rua10-01"),
            new Contador("centro", "cruz-central-av2", "contador-central-av2-01"));

    static String arg(String[] args, String nome, String padrao) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--" + nome)) {
                return args[i + 1];
            }
        }
        return padrao;
    }

    public static void main(String[] args) throws Exception {
        String destino = arg(args, "destino", "http://localhost:8080/api/v1/ingestao");
        String token = arg(args, "token", "demo-token");
        double intervalo = Double.parseDouble(arg(args, "intervalo", "5"));
        String congestionar = arg(args, "congestionar", "");
        double congestionarApos = Double.parseDouble(arg(args, "congestionar-apos", "0"));
        double congestionarPor = Double.parseDouble(arg(args, "congestionar-por", "120"));

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Random rnd = new Random();
        long inicio = System.currentTimeMillis();
        int enviadas = 0;
        System.out.println("Simulador Java enviando para " + destino + " a cada " + intervalo + "s (Ctrl+C para parar)");

        while (true) {
            double decorrido = (System.currentTimeMillis() - inicio) / 1000.0;
            boolean janelaCongestionamento = decorrido >= congestionarApos && decorrido < congestionarApos + congestionarPor;
            for (Contador c : CONTADORES) {
                boolean cong = janelaCongestionamento && c.area().equals(congestionar);
                double vel = Math.max(1, (cong ? 8 : 38) + rnd.nextGaussian() * (cong ? 2.5 : 5));
                double fluxo = Math.max(0, (cong ? 20 : 42) + rnd.nextGaussian() * (cong ? 4 : 8));
                double ocup = Math.min(100, Math.max(0, (cong ? 86 : 32) + rnd.nextGaussian() * (cong ? 4 : 7)));
                String json = String.format(Locale.ROOT,
                        "{\"versao\":\"1.0\",\"id_mensagem\":\"%s\",\"dispositivo\":\"%s\",\"tipo\":\"telemetria\","
                                + "\"timestamp\":\"%s\",\"local\":{\"site\":\"%s\",\"area\":\"%s\"},\"medidas\":["
                                + "{\"grandeza\":\"fluxo_veiculos\",\"valor\":%.1f,\"unidade\":\"{veic}/min\"},"
                                + "{\"grandeza\":\"velocidade_media\",\"valor\":%.1f,\"unidade\":\"km/h\"},"
                                + "{\"grandeza\":\"ocupacao_via\",\"valor\":%.1f,\"unidade\":\"%%\"}]}",
                        UUID.randomUUID(), c.dispositivo(), Instant.now(), c.site(), c.area(), fluxo, vel, ocup);
                HttpRequest rq = HttpRequest.newBuilder(URI.create(destino))
                        .header("Content-Type", "application/json")
                        .header("X-Device-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                try {
                    HttpResponse<String> rs = http.send(rq, HttpResponse.BodyHandlers.ofString());
                    enviadas++;
                    if (rs.statusCode() >= 300) {
                        System.out.println("Resposta " + rs.statusCode() + ": " + rs.body());
                    }
                } catch (Exception e) {
                    System.out.println("Falha ao enviar: " + e.getMessage());
                }
            }
            if (enviadas % 10 == 0) {
                System.out.println(enviadas + " mensagens enviadas" + (janelaCongestionamento && !congestionar.isEmpty() ? " (congestionamento ativo em " + congestionar + ")" : ""));
            }
            Thread.sleep((long) (intervalo * 1000));
        }
    }
}
