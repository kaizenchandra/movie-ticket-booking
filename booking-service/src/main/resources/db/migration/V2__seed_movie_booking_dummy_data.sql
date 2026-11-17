INSERT INTO show(movie_id, theatre_id, show_time, end_time, total_seats, available_seats, base_price, status)
VALUES (1, 1, CURRENT_TIMESTAMP + INTERVAL '7 days', CURRENT_TIMESTAMP + INTERVAL '7 days 3 hours', 100, 100, 250,
        'ACTIVE');
INSERT INTO seat_inventory(show_id, seat_number, price, seat_status)
SELECT 1, chr(65 + r) || n, 250, 'AVAILABLE'
FROM generate_series(0, 9) r
         CROSS JOIN generate_series(1, 10) n;
