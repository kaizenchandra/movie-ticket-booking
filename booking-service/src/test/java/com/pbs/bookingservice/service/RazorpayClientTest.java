package com.pbs.bookingservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RazorpayClientTest {
    private RazorpayClient client(String url) {
        return new RazorpayClient(new ObjectMapper(), url, "rzp_test_contract", "contract-secret", "contract-webhook");
    }

    private String sign(String text, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void checkoutUsesServerOrderAndConstantTimeSignatureValidation() throws Exception {
        var gateway = client("http://localhost");
        gateway.verifyCheckout("order_1", "pay_1", sign("order_1|pay_1", "contract-secret"));
        assertThrows(ResponseStatusException.class, () -> gateway.verifyCheckout("order_2", "pay_1", sign("order_1|pay_1", "contract-secret")));
        assertThrows(ResponseStatusException.class, () -> gateway.verifyCheckout("order_1", "pay_1", "not-hex"));
    }

    @Test
    void webhookVerifiesRawBytesWithSeparateSecret() throws Exception {
        var gateway = client("http://localhost");
        String body = "{\"event\": \"payment.captured\"}";
        String signature = sign(body, "contract-webhook");
        gateway.verifyWebhook(body.getBytes(StandardCharsets.UTF_8), signature);
        assertThrows(ResponseStatusException.class, () -> gateway.verifyWebhook(body.replace(": ", ":").getBytes(StandardCharsets.UTF_8), signature));
        assertThrows(ResponseStatusException.class, () -> gateway.verifyWebhook(body.getBytes(StandardCharsets.UTF_8), sign(body, "contract-secret")));
    }

    @Test
    void liveKeysRejectedAndMissingCredentialsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new RazorpayClient(new ObjectMapper(), "http://localhost", "rzp_live_key", "secret", "webhook"));
        var gateway = new RazorpayClient(new ObjectMapper(), "http://localhost", "", "", "");
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> gateway.call("GET", "/payments/pay_1", null, null)).getStatusCode().value());
    }

    @Test
    void httpContractIncludesBasicAuthenticationAndRefundIdempotency() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments/pay_1/refund", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean valid = "booking-refund-1".equals(exchange.getRequestHeaders().getFirst("X-Refund-Idempotency"))
                    && "Basic cnpwX3Rlc3RfY29udHJhY3Q6Y29udHJhY3Qtc2VjcmV0".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    && request.contains("62500");
            byte[] body = "{\"id\":\"rfnd_1\",\"status\":\"processed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var result = client("http://127.0.0.1:" + server.getAddress().getPort()).call("POST", "/payments/pay_1/refund", Map.of("amount", 62500), "booking-refund-1");
            assertEquals("processed", result.path("status").asText());
        } finally {
            server.stop(0);
        }
    }
}
