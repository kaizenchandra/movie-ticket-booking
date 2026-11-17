ALTER TABLE booking
    ADD COLUMN idempotency_key VARCHAR(100);
ALTER TABLE booking
    ADD COLUMN request_fingerprint VARCHAR(64);
CREATE UNIQUE INDEX booking_user_idempotency ON booking (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- Historical cancelled/expired reservations remain for auditing; one active owner per inventory row.
CREATE UNIQUE INDEX booking_seat_active_owner ON booking_seat (seat_inventory_id) WHERE status IN ('PENDING', 'BOOKED');
ALTER TABLE booking_seat
    ADD CONSTRAINT booking_seat_once UNIQUE (booking_id, seat_inventory_id);
ALTER TABLE show
    ADD CONSTRAINT show_inventory_bounds CHECK (available_seats >= 0 AND available_seats <= total_seats);
