package br.edu.unievangelica.iot.central.ingestao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Assina os tópicos canônicos {inquilino}/{site}/{dominio}/{dispositivo}/{telemetria|evento|estado}
 * (RF-ING-01). Se o broker estiver fora do ar, tenta reconectar a cada 5 segundos;
 * a ingestão HTTP continua funcionando.
 */
@Component
public class MqttAssinante {

    private static final Logger log = LoggerFactory.getLogger(MqttAssinante.class);
    private static final String[] TOPICOS = {"+/+/+/+/telemetria", "+/+/+/+/evento", "+/+/+/+/estado"};

    private final IngestaoService ingestao;
    private final ObjectMapper mapper;
    private final boolean habilitado;
    private final String url;
    private MqttClient client;
    private final MqttConnectOptions opcoes = new MqttConnectOptions();

    public MqttAssinante(IngestaoService ingestao, ObjectMapper mapper,
                         @Value("${plataforma.mqtt.habilitado:true}") boolean habilitado,
                         @Value("${plataforma.mqtt.url:tcp://localhost:1883}") String url) {
        this.ingestao = ingestao;
        this.mapper = mapper;
        this.habilitado = habilitado;
        this.url = url;
    }

    @PostConstruct
    public void iniciar() {
        if (!habilitado) {
            log.info("MQTT desabilitado por configuração");
            return;
        }
        try {
            client = new MqttClient(url, "plataforma-central-" + UUID.randomUUID().toString().substring(0, 8),
                    new MemoryPersistence());
            opcoes.setCleanSession(true);
            opcoes.setConnectionTimeout(5);
            opcoes.setKeepAliveInterval(30);
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverUri) {
                    log.info("Conectado ao broker MQTT {}", serverUri);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("Conexão MQTT perdida: {}", cause == null ? "?" : cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    tratar(topic, message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
        } catch (MqttException e) {
            log.error("Não foi possível criar o cliente MQTT: {}", e.getMessage());
            return;
        }
        conectar();
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void garantirConexao() {
        if (habilitado && client != null && !client.isConnected()) {
            conectar();
        }
    }

    private synchronized void conectar() {
        try {
            client.connect(opcoes);
            client.subscribe(TOPICOS, new int[] {1, 1, 1});
            log.info("Assinando {}", String.join(", ", TOPICOS));
        } catch (MqttException e) {
            log.warn("Broker MQTT indisponível em {} ({}). Nova tentativa em 5 s.", url, e.getMessage());
        }
    }

    private void tratar(String topic, MqttMessage message) {
        String texto = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            String[] partes = topic.split("/");
            if (partes.length != 5) {
                log.warn("Tópico fora da convenção: {}", topic);
                return;
            }
            JsonNode msg;
            try {
                msg = mapper.readTree(texto);
            } catch (Exception e) {
                ingestao.registrarInvalida("mqtt", "JSON inválido no tópico " + topic + ": " + e.getMessage(), texto);
                return;
            }
            ResultadoMensagem r = ingestao.processar(msg, Contexto.mqtt(partes[0], partes[3]));
            log.debug("MQTT {} -> {}", topic, r.status());
        } catch (Exception e) {
            // Nunca deixar a exceção escapar: o Paho desconectaria o cliente.
            log.error("Falha ao processar mensagem do tópico {}: {}", topic, e.getMessage());
        }
    }

    @PreDestroy
    public void encerrar() {
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            }
        } catch (MqttException e) {
            log.warn("Erro ao encerrar o cliente MQTT: {}", e.getMessage());
        }
    }
}
