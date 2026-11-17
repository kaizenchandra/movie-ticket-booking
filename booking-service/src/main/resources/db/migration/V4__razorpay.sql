ALTER TABLE payment
    ADD COLUMN gateway_order_id VARCHAR(255) UNIQUE;
ALTER TABLE payment
    ADD COLUMN gateway_payment_id VARCHAR(255) UNIQUE;
CREATE TABLE razorpay_webhook
(
    id        VARCHAR(255) PRIMARY KEY,
    payload   TEXT    NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX razorpay_webhook_pending ON razorpay_webhook (processed);
DROP INDEX payment_one_success;
CREATE UNIQUE INDEX payment_one_success ON payment (booking_id) WHERE status IN ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED');
