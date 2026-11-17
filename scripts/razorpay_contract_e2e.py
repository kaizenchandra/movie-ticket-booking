#!/usr/bin/env python3
"""Local HTTP contract end-to-end test; does not contact Razorpay sandbox."""
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
import hashlib, hmac, time


def expect_error(action, code=400):
    try:
        action()
        raise AssertionError('Invalid request accepted')
    except urllib.error.HTTPError as exc:
        assert exc.code == code, exc.code


def mock_capture(order):
    return json.loads(request('http://localhost:18090/__test/capture', {'orderId': order['orderId']})[1])


def webhook(order, captured, signature_valid=True):
    body = json.dumps({'event': 'payment.captured', 'payload': {
        'payment': {'entity': {'id': captured['paymentId'], 'order_id': order['orderId']}}}}).encode()
    signature = hmac.new(b'contract-webhook', body, hashlib.sha256).hexdigest() if signature_valid else '0' * 64
    return urllib.request.urlopen(urllib.request.Request('http://localhost:8080/api/v1/webhooks/razorpay', body,
                                                         {'Content-Type': 'application/json',
                                                          'X-Razorpay-Signature': signature}), timeout=10).read()


def wait_for(action, check):
    for _ in range(75):
        value = action()
        if check(value): return value
        time.sleep(1)
    raise AssertionError('State transition timed out')


show = api('/api/v1/shows')[0]
seats = [s['seatNumber'] for s in api(f"/api/v1/shows/{show['id']}/seats") if s['seatStatus'] == 'AVAILABLE']


def reserve(seat):
    return api('/api/v1/booking', dict(showId=show['id'], seatCount=1, seatNumbers=[seat]))['booking']['id']


booking_id = reserve(seats[0]);
prefix = f'/api/v1/booking/{booking_id}'
request('http://localhost:18090/__test/faults', {'order_response_loss': True})
expect_error(lambda: api(prefix + '/razorpay/order', method='POST', key='gateway-order'), 502)
order = api(prefix + '/razorpay/order', method='POST', key='gateway-order')
assert api(prefix + '/razorpay/order', method='POST', key='gateway-order') == order
capture = mock_capture(order)
expect_error(lambda: api(prefix + '/razorpay/verify',
                         dict(orderId=order['orderId'], paymentId=capture['paymentId'], signature='00' * 32)))
expect_error(lambda: webhook(order, capture, False))
payment = api(prefix + '/razorpay/verify',
              dict(orderId=order['orderId'], paymentId=capture['paymentId'], signature=capture['signature']))
assert payment['status'] == 'SUCCEEDED'
assert api(prefix)['booking']['status'] == 'CONFIRMED_BOOKING'
webhook(order, capture);
webhook(order, capture)
request('http://localhost:18090/__test/faults', {'refund_response_loss': True})
api(prefix + '/cancel', method='PUT');
api(prefix + '/cancel', method='PUT')
refund = wait_for(lambda: api(prefix + '/refunds'), lambda r: r['status'] == 'SUCCEEDED')
assert refund['amount'] == payment['amount']
assert api(prefix + '/refunds', method='POST')['id'] == refund['id']
print('PASS orders, HMAC rejection, captured verification, duplicate webhooks, cancellation and asynchronous refund')

# Payment arrives after cancellation: never confirm or reclaim released seats.
late_id = reserve(seats[0]);
late_prefix = f'/api/v1/booking/{late_id}'
late_order = api(late_prefix + '/razorpay/order', method='POST', key='late-order')
api(late_prefix + '/cancel', method='PUT')
late_capture = mock_capture(late_order)
webhook(late_order, late_capture)
late_refund = wait_for(lambda: api(late_prefix + '/payments'), lambda rows: rows[0]['status'] == 'REFUNDED')
assert api(late_prefix)['booking']['status'] == 'CANCELLED_BOOKING'
assert api('/api/v1/shows')[0]['availableSeats'] == show['availableSeats']
print('PASS late captured payment automatically refunded without reclaiming seats')

# Missing checkout callback and missing webhook: periodic provider reconciliation recovers.
recovery_id = reserve(seats[1]);
recovery = f'/api/v1/booking/{recovery_id}'
recovery_order = api(recovery + '/razorpay/order', method='POST', key='recovery-order')
mock_capture(recovery_order)
wait_for(lambda: api(recovery), lambda row: row['booking']['status'] == 'CONFIRMED_BOOKING')
api(recovery + '/cancel', method='PUT')
wait_for(lambda: api(recovery + '/refunds'), lambda row: row['status'] == 'SUCCEEDED')
print('PASS recovery without webhook or browser callback; full contract workflow passed')
