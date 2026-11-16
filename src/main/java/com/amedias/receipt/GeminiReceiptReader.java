package com.amedias.receipt;

import com.amedias.config.AppProperties;
import com.amedias.expense.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Lee un ticket con la API REST de Gemini. La clave y el modelo salen de variables de entorno. */
@Component
public class GeminiReceiptReader {

    private static final Logger log = LoggerFactory.getLogger(GeminiReceiptReader.class);
    private static final Set<String> CURRENCIES = Set.of("EUR", "USD", "GBP");
    private static final long MAX_LINE_CENTS = 100_000_000L;
    private static final int MAX_LINES = 100;

    private static final String PROMPT = """
            Eres un lector de tickets de compra. Analiza la imagen y devuelve SOLO JSON con:
            - merchant: nombre del comercio (cadena vacía si no se lee).
            - date: fecha de compra en formato YYYY-MM-DD (cadena vacía si no se ve con claridad).
            - currency: EUR, USD o GBP (cadena vacía si no se puede saber).
            - total: total pagado del ticket completo, con punto decimal (null si no se lee).
            - items: una entrada por línea de producto, con name y amount.
            Reglas: amount es el importe FINAL de la línea (cantidad x precio, con promociones y descuentos
            ya aplicados), no el precio unitario. Los descuentos independientes y cupones van como líneas con amount
            negativo. No añadas los impuestos incluidos como productos. No inventes líneas, precios ni fechas ilegibles.""";

    private record Raw(String merchant, String date, String currency, BigDecimal total, List<RawItem> items) {}

    private record RawItem(String name, BigDecimal amount) {}

    private final RestClient http;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public GeminiReceiptReader(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(100));
        this.http = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .requestFactory(factory)
                .build();
    }

    public ReceiptAnalysis read(byte[] image, String mimeType) {
        String apiKey = props.gemini().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La lectura IA no está configurada");
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(
                        Map.of("text", PROMPT),
                        Map.of("inlineData", Map.of("mimeType", mimeType,
                                "data", Base64.getEncoder().encodeToString(image)))))),
                "generationConfig", Map.of(
                        "temperature", 0,
                        "responseMimeType", "application/json",
                        "responseSchema", schema()));

        JsonNode response;
        try {
            response = http.post()
                    .uri("/models/{model}:generateContent", props.gemini().model())
                    .header("x-goog-api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            log.warn("Gemini respondió {} (modelo {})", status, props.gemini().model());
            if (status == 429) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Cuota de la IA agotada");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "El proveedor de IA devolvió un error");
        } catch (RestClientException e) {
            log.warn("Fallo de red con Gemini: {}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo contactar con el proveedor de IA");
        }

        String text = response == null ? "" : response.path("candidates").path(0).path("content")
                .path("parts").path(0).path("text").asText("");
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "La IA no pudo leer el ticket");
        }
        Raw raw;
        try {
            raw = mapper.readValue(text, Raw.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "La IA devolvió un formato no válido");
        }
        return toContract(raw);
    }

    /** Nunca confiamos en la salida del modelo: valida, convierte a céntimos y rechaza lo que falte. */
    private ReceiptAnalysis toContract(Raw raw) {
        List<String> warnings = new ArrayList<>();

        String merchant = raw.merchant() == null ? "" : raw.merchant().trim();
        if (merchant.isEmpty()) {
            throw unreadable("No se pudo leer el comercio");
        }
        if (merchant.length() > 120) {
            merchant = merchant.substring(0, 120);
        }

        LocalDate date;
        try {
            date = LocalDate.parse(raw.date() == null ? "" : raw.date().trim());
        } catch (Exception e) {
            throw unreadable("No se pudo leer la fecha");
        }

        String currency = raw.currency() == null ? "" : raw.currency().trim().toUpperCase(java.util.Locale.ROOT);
        if (!CURRENCIES.contains(currency)) {
            throw unreadable("Moneda no reconocida");
        }

        List<ReceiptAnalysis.Item> items = new ArrayList<>();
        long sum = 0;
        if (raw.items() != null) {
            for (RawItem it : raw.items()) {
                if (it == null || it.name() == null || it.name().isBlank() || it.amount() == null) {
                    continue;
                }
                long cents;
                try {
                    cents = Money.roundToCents(it.amount());
                } catch (ArithmeticException e) {
                    throw unreadable("Importe ilegible");
                }
                if (Math.abs(cents) > MAX_LINE_CENTS) {
                    throw unreadable("Importe fuera de rango");
                }
                if (items.size() == MAX_LINES) {
                    warnings.add("El ticket tiene más de 100 líneas; se han omitido las últimas.");
                    break;
                }
                String name = it.name().trim();
                items.add(new ReceiptAnalysis.Item(name.length() > 120 ? name.substring(0, 120) : name, cents));
                sum += cents;
            }
        }
        if (items.isEmpty()) {
            throw unreadable("No se pudo leer ningún producto");
        }

        long total;
        if (raw.total() == null) {
            total = Math.max(sum, 0);
            warnings.add("No se pudo leer el total del ticket; se muestra la suma de las líneas.");
        } else {
            try {
                total = Money.roundToCents(raw.total());
            } catch (ArithmeticException e) {
                throw unreadable("Total ilegible");
            }
            if (total < 0 || total > MAX_LINE_CENTS * MAX_LINES) {
                throw unreadable("Total fuera de rango");
            }
        }
        return new ReceiptAnalysis(merchant, date, currency, total, items, warnings);
    }

    private static ResponseStatusException unreadable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private static Map<String, Object> schema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "merchant", Map.of("type", "STRING"),
                        "date", Map.of("type", "STRING"),
                        "currency", Map.of("type", "STRING"),
                        "total", Map.of("type", "NUMBER", "nullable", true),
                        "items", Map.of("type", "ARRAY", "items", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "name", Map.of("type", "STRING"),
                                        "amount", Map.of("type", "NUMBER")),
                                "required", List.of("name", "amount")))),
                "required", List.of("merchant", "date", "currency", "items"));
    }
}
