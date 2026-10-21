package br.edu.unievangelica.iot.borda;

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

/** Ingestão local por MQTT: assina os tópicos canônicos no broker do nó e guarda tudo na fila. */
@Component
public class MqttBorda {

    private static final Logger log = LoggerFactory.getLogger(MqttBorda.class);
    private static final String[] TOPICOS = {"+/+/+/+/telemetria", "+/+/+/+/evento", "+/+/+/+/estado"};

    private final EntradaMensagem entrada;
    private final ObjectMapper mapper;
    private final boolean habilitado;
    private final String url;
    private MqttClient client;
    private final MqttConnectOptions opcoes = new MqttConnectOptions();

    public MqttBorda(EntradaMensagem entrada, ObjectMapper mapper,
                     @Value("${borda.mqtt.habilitado:true}") boolean habilitado,
                     @Value("${borda.mqtt.url:tcp://localhost:1884}") String url) {
        this.entrada = entrada;
        this.mapper = mapper;
        this.habilitado = habilitado;
        this.url = url;
    }

    @PostConstruct
    public void iniciar() {
        if (!habilitado) {
            return;
        }
        try {
            client = new MqttClient(url, "no-borda-" + UUID.randomUUID().toString().substring(0, 8), new MemoryPersistence());
            opcoes.setCleanSession(true);
            opcoes.setConnectionTimeout(5);
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverUri) {
                    log.info("Conectado ao broker local {}", serverUri);
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
            log.warn("Broker MQTT local indisponível em {} ({}). Nova tentativa em 5 s.", url, e.getMessage());
        }
    }

    private void tratar(String topic, MqttMessage message) {
        try {
            JsonNode msg = mapper.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
            String status = entrada.aceitar(msg);
            log.debug("MQTT {} -> {}", topic, status);
        } catch (Exception e) {
            log.warn("Mensagem MQTT descartada ({}): {}", topic, e.getMessage());
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
