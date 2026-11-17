#!/usr/bin/env python3
"""Live local-stack smoke test. Creates and confirms a booking using demo OIDC login."""
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser


class Form(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.inputs = []
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'input' and attrs.get('name'):
            self.inputs.append((attrs['name'], attrs.get('value', '')))


client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(path, data=None, method=None, key=None):
    url = path if path.startswith('http') else 'http://localhost:8080' + path
    headers = {'Accept': 'application/json' if '/api/' in url else 'text/html'}
    if key:
        headers["Idempotency-Key"] = key
    if isinstance(data, dict):
        data = json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
    response = client.open(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=30)
    return response.geturl(), response.read().decode()


def api(path, data=None, method=None, key=None):
    _, body = request(path, data, method, key)
    return json.loads(body) if body else None


url, html = request('/oauth2/authorization/oidc-client')
assert '/login' in url, url
fields = dict(Form(html).inputs)
fields.update(username='user', password='password')
url, html = request('http://localhost:9000/login', urllib.parse.urlencode(fields).encode())
if 'consent' in html.lower() and 'scope' in html:
    fields = [(name, value) for name, value in Form(html).inputs if name not in ('cancel',)]
    url, html = request('http://localhost:9000/oauth2/authorize', urllib.parse.urlencode(fields).encode())
assert '8080' in url and '/login' not in url, (url, html[:300])
print('PASS OIDC login and consent')
shows = api('/api/v1/shows')
assert shows, 'No future shows; initialize fresh demo data or add a future show'
show = shows[0]
seats = api(f"/api/v1/shows/{show['id']}/seats")
available = [s['seatNumber'] for s in seats if s['seatStatus'] == 'AVAILABLE']
assert len(available) >= 3, 'Need at least three available seats'
payload = dict(showId=show['id'], seatCount=3, seatNumbers=available[:3], offerCode='THIRD_TICKET_50_DISCOUNT')
booking = api('/api/v1/booking', payload)['booking']
booking_id = booking['id']
assert booking['status'] == 'PENDING_PAYMENT', booking
assert api(f'/api/v1/booking/{booking_id}')['booking']['status'] == 'PENDING_PAYMENT'
assert len([s for s in api(f"/api/v1/shows/{show['id']}/seats") if
            s['seatNumber'] in available[:3] and s['seatStatus'] == 'RESERVED']) == 3
print(f'PASS reservation and read-only retrieval: booking {booking_id}')
try:
    api('/api/v1/booking', payload)
    raise AssertionError('Duplicate reservation accepted')
except urllib.error.HTTPError as exc:
    assert exc.code in (400, 409), exc.code
try:
    api(f'/api/v1/booking/{booking_id}/confirm', method='PUT')
    raise AssertionError('Unpaid booking confirmed')
except urllib.error.HTTPError as exc:
    assert exc.code == 400
payment_path = f'/api/v1/booking/{booking_id}/payments'
declined = api(payment_path, {'paymentToken': 'tok_decline'}, key='declined-test')
assert declined['status'] == 'DECLINED'
assert api(f'/api/v1/booking/{booking_id}')['booking']['status'] == 'PENDING_PAYMENT'
assert api(payment_path, {'paymentToken': 'tok_decline'}, key='declined-test')['id'] == declined['id']
from concurrent.futures import ThreadPoolExecutor

with ThreadPoolExecutor(max_workers=2) as executor:
    attempts = list(
        executor.map(lambda _: api(payment_path, {'paymentToken': 'tok_success'}, key='successful-test'), range(2)))
paid = attempts[0]
assert attempts[0]['id'] == attempts[1]['id'], 'Concurrent retries created two payments'
assert paid['status'] == 'SUCCEEDED' and paid['amount'] == booking['finalAmount']
assert api(payment_path, {'paymentToken': 'tok_success'}, key='successful-test')['id'] == paid['id']
try:
    api(payment_path, {'paymentToken': 'tok_decline'}, key='successful-test')
    raise AssertionError('Conflicting idempotent request accepted')
except urllib.error.HTTPError as exc:
    assert exc.code == 400
assert len(api(payment_path)) == 2
print('PASS unpaid confirmation blocked, decline, retry, successful payment and idempotency')
api(f'/api/v1/booking/{booking_id}/confirm', method='PUT')
api(f'/api/v1/booking/{booking_id}/confirm', method='PUT')
assert api(f'/api/v1/booking/{booking_id}')['booking']['status'] == 'CONFIRMED_BOOKING'
assert any(b['booking']['id'] == booking_id for b in api('/api/v1/booking/me/bookings'))
assert len([s for s in api(f"/api/v1/shows/{show['id']}/seats") if
            s['seatNumber'] in available[:3] and s['seatStatus'] == 'BOOKED']) == 3
assert api('/api/v1/shows')[0]['availableSeats'] == show['availableSeats'] - 3
print(
    f'PASS duplicate rejection, confirmation, repeat confirmation, listing and inventory; amount={booking["finalAmount"]}')

# Optional expiry check accelerates only a newly created test booking's timestamp.
# It requires access to this Compose stack's PostgreSQL container.
if '--expiry' in __import__('sys').argv:
    import subprocess
    import time

    payload['seatNumbers'] = available[3:4]
    payload['seatCount'] = 1
    payload.pop('offerCode')
    assert payload['seatNumbers'], 'Need one more available seat for expiry test'
    pending = api('/api/v1/booking', payload)['booking']['id']
    subprocess.run(
        ['docker', 'compose', 'exec', '-T', 'postgres-db', 'psql', '-U', 'kaizen', '-d', 'mtb', '-v', 'ON_ERROR_STOP=1',
         '-c',
         f"UPDATE booking SET expiry_time = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = {int(pending)} AND status = 'PENDING_PAYMENT'"],
        check=True, capture_output=True)
    for _ in range(75):
        if api(f'/api/v1/booking/{pending}')['booking']['status'] == 'EXPIRED_BOOKING':
            break
        time.sleep(1)
    else:
        raise AssertionError('Expiry scheduler did not release the booking')
    expired_seat = next(
        s for s in api(f"/api/v1/shows/{show['id']}/seats") if s['seatNumber'] == payload['seatNumbers'][0])
    assert expired_seat['seatStatus'] == 'AVAILABLE'
    assert api(f'/api/v1/booking/{booking_id}')['booking']['status'] == 'CONFIRMED_BOOKING'
    print('PASS scheduled expiry releases pending seats and preserves confirmed booking')

try:
    api(f'/api/v1/booking/{booking_id}/refunds', method='POST')
    raise AssertionError('Active booking refunded')
except urllib.error.HTTPError as exc:
    assert exc.code == 400
cancelled = api(f'/api/v1/booking/{booking_id}/cancel', method='PUT')['booking']
assert cancelled['status'] == 'CANCELLED_BOOKING'
api(f'/api/v1/booking/{booking_id}/cancel', method='PUT')
refund_path = f'/api/v1/booking/{booking_id}/refunds'
refunded = api(refund_path)
assert refunded['status'] == 'SUCCEEDED' and refunded['amount'] == paid['amount']
assert api(refund_path, method='POST')['id'] == refunded['id']
assert api(payment_path)[-1]['status'] == 'REFUNDED'
assert api('/api/v1/shows')[0]['availableSeats'] == show['availableSeats']
assert all(s['seatStatus'] == 'AVAILABLE' for s in api(f"/api/v1/shows/{show['id']}/seats") if
           s['seatNumber'] in available[:3])
new_pending = api('/api/v1/booking', dict(showId=show['id'], seatCount=1, seatNumbers=available[:1]))['booking']['id']
api(f'/api/v1/booking/{new_pending}/cancel', method='PUT')
try:
    api(f'/api/v1/booking/{new_pending}/refunds', method='POST')
    raise AssertionError('Unpaid booking refunded')
except urllib.error.HTTPError as exc:
    assert exc.code == 400
try:
    api(f'/api/v1/booking/{new_pending}/payments', {'paymentToken': 'tok_success'}, key='after-cancellation')
    raise AssertionError('Cancelled booking paid')
except urllib.error.HTTPError as exc:
    assert exc.code == 400
print('PASS paid cancellation, full refund, retry safety, seat resale, unpaid cancellation and invalid transitions')
