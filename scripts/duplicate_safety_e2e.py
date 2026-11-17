#!/usr/bin/env python3
"""Concurrent HTTP regression against Compose's local Razorpay contract fixture.

Run only with scripts/compose.razorpay-contract.yml enabled. No real payments.
"""
import concurrent.futures
import http.cookiejar
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
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


BASE = os.environ.get('BASE_URL', 'http://localhost:8080').rstrip('/')
AUTH = os.environ.get('AUTH_URL', 'http://localhost:9000').rstrip('/')
MOCK = os.environ.get('MOCK_URL', 'http://localhost:18090').rstrip('/')
jar = http.cookiejar.CookieJar()
login = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
with login.open(urllib.request.Request(BASE + '/oauth2/authorization/oidc-client', headers={'Accept': 'text/html'}),
                timeout=30) as response:
    assert '/login' in response.geturl(), response.geturl()
    fields = dict(Form(response.read().decode()).inputs)
fields.update(username='user', password='password')
with login.open(AUTH + '/login', urllib.parse.urlencode(fields).encode(), timeout=30) as response:
    url, html = response.geturl(), response.read().decode()
if 'consent' in html.lower() and 'scope' in html:
    fields = [(k, v) for k, v in Form(html).inputs if k != 'cancel']
    with login.open(AUTH + '/oauth2/authorize', urllib.parse.urlencode(fields).encode(), timeout=30) as response:
        url = response.geturl()
assert urllib.parse.urlparse(url).netloc == urllib.parse.urlparse(BASE).netloc and '/login' not in url, url
probe = urllib.request.Request(BASE + '/api/v1/booking')
jar.add_cookie_header(probe)
cookie = probe.get_header('Cookie')
assert cookie, 'No authenticated session cookie'


def api(path, data=None, method=None, key=None):
    url = path if path.startswith('http') else BASE + path
    headers = {'Accept': 'application/json', 'Content-Type': 'application/json'}
    if url.startswith(BASE + '/'):
        headers['Cookie'] = cookie
    if key is not None:
        headers['Idempotency-Key'] = key
    body = json.dumps(data).encode() if data is not None else None
    # Each request gets its own opener; the frozen authenticated cookie is read-only.
    with urllib.request.build_opener().open(
            urllib.request.Request(url, body, headers, method=method), timeout=60
    ) as response:
        body = response.read()
        return json.loads(body) if body else None


def race(action, workers=6):
    barrier = threading.Barrier(workers)

    def run(index):
        barrier.wait(timeout=10)
        try:
            return (200, action(index))
        except urllib.error.HTTPError as error:
            return (error.code, error.read().decode())

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(run, range(workers)))


def wait_for(action, predicate):
    for _ in range(90):
        result = action()
        if predicate(result):
            return result
        time.sleep(1)
    raise AssertionError('Timed out waiting for state transition')


def prefix(booking_id):
    return f'/api/v1/booking/{booking_id}'


created = set()
try:
    # Fail early if the explicit fixture is unavailable.
    assert api(MOCK + '/__test/faults', {})['ok']
    show = api('/api/v1/shows')[0]
    seats_url = f"/api/v1/shows/{show['id']}/seats"
    available = [s['seatNumber'] for s in api(seats_url) if s['seatStatus'] == 'AVAILABLE']
    assert len(available) >= 2, 'At least two available seats are required'


    def request_for(seat):
        return dict(showId=show['id'], seatCount=1, seatNumbers=[seat])


    # Distinct booking attempts compete for one seat: exactly one can reserve it.
    results = race(lambda i: api('/api/v1/booking', request_for(available[0]), key=str(uuid.uuid4())))
    winners = [value['booking']['id'] for code, value in results if code == 200]
    created.update(winners)
    assert len(winners) == 1, results
    assert all(code in (200, 400, 409) for code, _ in results), results
    booking_id = winners[0]
    held = next(s for s in api(seats_url) if s['seatNumber'] == available[0])
    assert held['seatStatus'] == 'RESERVED', held
    print('PASS concurrent distinct booking attempts: exactly one seat reservation', flush=True)

    # A single logical booking retried concurrently returns one durable result.
    booking_key = 'duplicate-safety-' + uuid.uuid4().hex
    repeated = race(lambda _: api('/api/v1/booking', request_for(available[1]), key=booking_key))
    created.update(value['booking']['id'] for code, value in repeated if code == 200)
    assert all(code == 200 for code, _ in repeated), repeated
    assert len({value['booking']['id'] for _, value in repeated}) == 1, repeated
    try:
        api('/api/v1/booking', request_for(available[0]), key=booking_key)
        raise AssertionError('Changed request reused a booking key')
    except urllib.error.HTTPError as error:
        assert error.code == 409, error.code
    print('PASS concurrent booking retries return one booking; changed payload rejected', flush=True)
    # Multiple tabs and request keys must still get one provider order per booking.
    orders = race(lambda _: api(prefix(booking_id) + '/razorpay/order', method='POST', key=str(uuid.uuid4())))
    assert all(code == 200 for code, _ in orders), orders
    assert len({value['orderId'] for _, value in orders}) == 1, orders
    order = orders[0][1]
    assert order['keyId'] == 'rzp_test_contract', 'This script requires the local gateway fixture'
    capture = api(MOCK + '/__test/capture', {'orderId': order['orderId']})
    verification = dict(orderId=order['orderId'], paymentId=capture['paymentId'], signature=capture['signature'])
    callbacks = race(lambda _: api(prefix(booking_id) + '/razorpay/verify', verification))
    assert all(code == 200 and value['status'] == 'SUCCEEDED' for code, value in callbacks), callbacks
    assert len({value['id'] for _, value in callbacks}) == 1, callbacks
    assert api(prefix(booking_id))['booking']['status'] == 'CONFIRMED_BOOKING'
    attempts = api(prefix(booking_id) + '/payments')
    assert len(attempts) == 1 and attempts[0]['gatewayPaymentId'] == capture['paymentId'], attempts
    print('PASS concurrent order requests and verification callbacks produce one payment', flush=True)

    cancelled = race(lambda _: api(prefix(booking_id) + '/cancel', method='PUT'))
    assert all(code == 200 for code, _ in cancelled), cancelled
    refund = wait_for(lambda: api(prefix(booking_id) + '/refunds'), lambda r: r['status'] == 'SUCCEEDED')
    refunds = race(lambda _: api(prefix(booking_id) + '/refunds', method='POST'))
    assert all(code == 200 and value['id'] == refund['id'] for code, value in refunds), refunds
    assert refund['amount'] == attempts[0]['amount'], refund
    assert api(prefix(booking_id))['booking']['status'] == 'CANCELLED_BOOKING'
    print('PASS concurrent cancellation and refund requests produce one full refund', flush=True)
finally:
    for booking_id in created:
        api(prefix(booking_id) + '/cancel', method='PUT')

assert all(s['seatStatus'] == 'AVAILABLE' for s in api(seats_url) if s['seatNumber'] in available[:2])
assert next(s for s in api('/api/v1/shows') if s['id'] == show['id'])['availableSeats'] == show['availableSeats']
print('PASS seats and inventory restored; concurrent duplicate-safety workflow passed', flush=True)
