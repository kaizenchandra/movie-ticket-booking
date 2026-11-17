-- Retries and simultaneous Checkout tabs must share one durable gateway order.
-- Keep the existing unique gateway_order_id and gateway_payment_id constraints too.
CREATE UNIQUE INDEX payment_one_gateway_order_per_booking
    ON payment (booking_id) WHERE gateway_order_id IS NOT NULL;
