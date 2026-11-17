package com.pbs.bookingservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbs.bookingservice.repository.PaymentRepository;
import com.pbs.bookingservice.repository.RazorpayWebhookRepository;
import com.pbs.bookingservice.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "razorpay.enabled", havingValue = "true")
public class RazorpayWorker {
    private final RazorpayService gateway;
    private final RazorpayClient client;
    private final RazorpayWebhookRepository inbox;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private long reconciliationCursor = 0;

    @Scheduled(fixedDelay = 10000)
    public void reconcile() {
        for (var event : inbox.findTop50ByProcessedFalseOrderById()) {
            try {
                transactions.executeWithoutResult(tx -> {
                    try {
                        var root = mapper.readTree(event.getPayload());
                        String type = root.path("event").asText();
                        if (type.equals("payment.captured") || type.equals("order.paid")) {
                            var p = root.path("payload").path("payment").path("entity");
                            gateway.captured(p.path("order_id").asText(), p.path("id").asText());
                        }
                        if (type.equals("refund.processed") || type.equals("refund.failed")) {
                            String paymentId = root.path("payload").path("refund").path("entity").path("payment_id").asText();
                            payments.findByGatewayPaymentId(paymentId).flatMap(p -> refunds.findByBookingId(p.getBookingId()))
                                    .ifPresent(r -> gateway.processRefund(r.getId()));
                        }
                        event.setProcessed(true);
                        inbox.save(event);
                    } catch (java.io.IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                });
            } catch (RuntimeException ex) {
                log.warn("Razorpay webhook {} will be retried", event.getId());
            }
        }
        // Recover missing webhooks and browser callbacks by polling server-created orders.
        var candidates = payments.findTop50ByGatewayOrderIdIsNotNullAndStatusAndIdGreaterThanOrderById("ORDER_CREATED", reconciliationCursor);
        if (candidates.isEmpty()) reconciliationCursor = 0;
        for (var payment : candidates) {
            reconciliationCursor = payment.getId();
            try {
                var items = client.call("GET", "/orders/" + RazorpayClient.id(payment.getGatewayOrderId()) + "/payments", null, null).path("items");
                for (var item : items)
                    if ("captured".equals(item.path("status").asText()))
                        gateway.captured(payment.getGatewayOrderId(), item.path("id").asText());
            } catch (RuntimeException ex) {
                log.warn("Razorpay order {} will be reconciled again", payment.getId());
            }
        }
        for (var refund : refunds.findTop50ByStatusOrderById("PENDING")) {
            try {
                gateway.processRefund(refund.getId());
            } catch (RuntimeException ex) {
                log.warn("Razorpay refund {} will be retried", refund.getId());
            }
        }
    }
}
