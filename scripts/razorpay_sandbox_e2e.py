#!/usr/bin/env python3
"""Live Razorpay TEST-mode runner. Complete Checkout in the browser when prompted."""
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
import time, uuid, threading
from concurrent.futures import ThreadPoolExecutor


def race(action, count=6):
    barrier = threading.Barrier(count)

    def run(i):
        barrier.wait(timeout=10)
        try:
            return (200, action(i))
        except urllib.error.HTTPError as e:
            return (e.code, e.read().decode())

    with ThreadPoolExecutor(max_workers=count) as pool:
        return list(pool.map(run, range(count)))


show = api('/api/v1/shows')[0]
seat = next(s['seatNumber'] for s in api(f"/api/v1/shows/{show['id']}/seats") if s['seatStatus'] == 'AVAILABLE')
payload = dict(showId=show['id'], seatCount=1, seatNumbers=[seat])
results = race(lambda _: api('/api/v1/booking', payload, key=str(uuid.uuid4())))
winners = [r['booking'] for code, r in results if code == 200]
assert len(winners) == 1 and all(code in (200, 409) for code, _ in results), results
booking = winners[0]
prefix = f"/api/v1/booking/{booking['id']}"
print('PASS competing seat requests: one reservation, five conflicts; booking', booking['id'])
orders = race(lambda _: api(prefix + '/razorpay/order', method='POST', key=str(uuid.uuid4())))
assert all(code == 200 for code, _ in orders), orders
assert len({o['orderId'] for _, o in orders}) == 1, orders
order = orders[0][1]
assert order['keyId'].startswith('rzp_test_') and order['keyId'] != 'rzp_test_contract', 'Real test keys required'
print('PASS six concurrent payment/order attempts reused real Razorpay order', order['orderId'])
print('CHECKOUT', f"http://localhost:8080/checkout.html?bookingId={booking['id']}", 'amount', booking['finalAmount'],
      'INR', flush=True)
for _ in range(360):
    if api(prefix)['booking']['status'] == 'CONFIRMED_BOOKING': break
    time.sleep(2)
else:
    raise AssertionError('Checkout not completed within twelve minutes; inspect booking/provider before retrying')
payments = api(prefix + '/payments')
assert len(payments) == 1 and payments[0]['status'] == 'SUCCEEDED', payments
print('PASS real sandbox captured payment and booking confirmation', payments[0]['gatewayPaymentId'])
blocked = race(lambda _: api(prefix + '/razorpay/order', method='POST', key=str(uuid.uuid4())))
assert all(code in (400, 409) for code, _ in blocked), blocked
assert len(api(prefix + '/payments')) == 1
print('PASS six duplicate payment requests rejected after capture')
results = race(lambda _: api(prefix + '/cancel', method='PUT'))
assert all(code == 200 for code, _ in results), results
for _ in range(180):
    refund = api(prefix + '/refunds')
    if refund['status'] == 'SUCCEEDED': break
    if refund['status'] == 'FAILED': raise AssertionError('Razorpay refund failed; inspect provider dashboard')
    time.sleep(2)
else:
    raise AssertionError('Refund still pending; reconciliation continues')
assert refund['amount'] == booking['finalAmount']
assert api(prefix)['booking']['status'] == 'CANCELLED_BOOKING'
retried = race(lambda _: api(prefix + '/refunds', method='POST'))
assert all(code == 200 and r['id'] == refund['id'] for code, r in retried), retried
assert next(s for s in api(f"/api/v1/shows/{show['id']}/seats") if s['seatNumber'] == seat)['seatStatus'] == 'AVAILABLE'
assert next(s for s in api('/api/v1/shows') if s['id'] == show['id'])['availableSeats'] == show['availableSeats']
print('PASS concurrent cancellation, one processed full refund, repeated refund requests and seat release',
      refund['providerReference'])
print('RESULT', dict(bookingId=booking['id'], orderId=order['orderId'], paymentId=payments[0]['gatewayPaymentId'],
                     refundId=refund['providerReference']))
print('Provider API totals and signed webhook inbox delivery must be independently verified.')
