package com.pbs.bookingservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Component
public class RazorpayClient {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl, keyId, secret, webhookSecret;

    public RazorpayClient(ObjectMapper mapper,
                          @Value("${razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl,
                          @Value("${razorpay.key-id:}") String keyId,
                          @Value("${razorpay.key-secret:}") String secret,
                          @Value("${razorpay.webhook-secret:}") String webhookSecret) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.keyId = keyId;
        this.secret = secret;
        this.webhookSecret = webhookSecret;
        if (!keyId.isBlank() && !keyId.startsWith("rzp_test_"))
            throw new IllegalArgumentException("Only Razorpay test-mode keys are permitted");
    }

    public static String id(String id) {
        if (id == null || !id.matches("[a-z]+_[A-Za-z0-9]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Razorpay identifier");
        return id;
    }

    public String keyId() {
        return keyId;
    }

    public JsonNode call(String method, String path, Map<String, Object> body, String refundKey) {
        if (keyId.isBlank() || secret.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Razorpay test credentials are not configured");
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json");
            if (refundKey != null) builder.header("X-Refund-Idempotency", refundKey);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay request failed; retry or reconcile payment status");
            return mapper.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay request interrupted");
        } catch (java.io.IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay unavailable; retry or reconcile payment status");
        }
    }

    public void verifyCheckout(String storedOrderId, String paymentId, String signature) {
        verify((storedOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8), signature, secret);
    }

    public void verifyWebhook(byte[] body, String signature) {
        verify(body, signature, webhookSecret);
    }

    private void verify(byte[] body, String signature, String key) {
        if (key.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Razorpay signing secret is not configured");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (signature == null || !MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature)))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Razorpay signature");
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
