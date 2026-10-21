package br.edu.unievangelica.iot.central.ingestao;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Valida a mensagem contra o JSON Schema versionado (RF-ING-06) e recusa dados pessoais (RF-DAD-05). */
@Component
public class MensagemValidator {

    private static final Set<String> PROIBIDOS = Set.of(
            "placa", "plate", "cpf", "imagem", "foto", "motorista", "nome_motorista");

    private final JsonSchema schema;

    public MensagemValidator() {
        this.schema = carregar();
    }

    private static JsonSchema carregar() {
        try (InputStream in = MensagemValidator.class.getResourceAsStream("/static/contratos/mensagem-canonica-v1.json")) {
            if (in == null) {
                throw new IllegalStateException("Contrato mensagem-canonica-v1.json não encontrado");
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Retorna a lista de erros; vazia significa mensagem válida. */
    public List<String> validar(JsonNode msg) {
        List<String> erros = new ArrayList<>();
        if (msg == null || !msg.isObject()) {
            erros.add("A mensagem deve ser um objeto JSON");
            return erros;
        }
        Iterator<String> campos = msg.fieldNames();
        while (campos.hasNext()) {
            String campo = campos.next();
            if (PROIBIDOS.contains(campo.toLowerCase(Locale.ROOT))) {
                erros.add("Campo com dado pessoal não aceito (LGPD): " + campo);
            }
        }
        JsonNode medidas = msg.get("medidas");
        if (medidas != null && medidas.isArray()) {
            for (JsonNode m : medidas) {
                if (m.hasNonNull("grandeza") && PROIBIDOS.contains(m.get("grandeza").asText().toLowerCase(Locale.ROOT))) {
                    erros.add("Grandeza com dado pessoal não aceita (LGPD): " + m.get("grandeza").asText());
                }
            }
        }
        if (!erros.isEmpty()) {
            return erros;
        }
        Set<ValidationMessage> falhas = schema.validate(msg);
        for (ValidationMessage vm : falhas) {
            erros.add(vm.getMessage());
        }
        if (erros.isEmpty()) {
            try {
                OffsetDateTime.parse(msg.get("timestamp").asText());
            } catch (DateTimeParseException e) {
                erros.add("timestamp inválido: use ISO 8601, por exemplo 2026-10-20T17:45:00Z");
            }
        }
        return erros;
    }
}
