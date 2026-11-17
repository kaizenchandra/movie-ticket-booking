"""Deterministic Razorpay HTTP contract fixture. Never use as a payment gateway."""
import hashlib
import hmac
import json
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

orders, payments, refunds, refund_keys = {}, {}, {}, {}
faults = {}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_request(self):
        with lock:
            data = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or '{}')
            path = self.path
            result = None
            if path == '/__test/faults':
                faults.update(data);
                result = {'ok': True}
            elif path == '/__test/capture':
                oid = data['orderId'];
                order = orders[oid]
                pid = 'pay_' + uuid.uuid4().hex
                payment = dict(id=pid, order_id=oid, amount=order['amount'], currency=order['currency'],
                               status='captured')
                payments[pid] = payment
                result = dict(paymentId=pid, signature=hmac.new(b'contract-secret', (oid + '|' + pid).encode(),
                                                                hashlib.sha256).hexdigest())
            elif self.headers.get('Authorization') != 'Basic cnpwX3Rlc3RfY29udHJhY3Q6Y29udHJhY3Qtc2VjcmV0':
                self.send_response(401);
                self.end_headers();
                return
            elif path == '/v1/orders' and self.command == 'POST':
                oid = 'order_' + uuid.uuid4().hex;
                result = dict(id=oid, **data);
                orders[oid] = result
                if faults.pop('order_response_loss', False):
                    self.send_response(503);
                    self.end_headers();
                    return
            elif path.startswith('/v1/orders?'):
                receipt = parse_qs(urlparse(path).query).get('receipt', [''])[0]
                result = dict(items=[o for o in orders.values() if o['receipt'] == receipt])
            elif path.startswith('/v1/orders/') and path.endswith('/payments'):
                oid = path.split('/')[3];
                result = dict(items=[p for p in payments.values() if p['order_id'] == oid])
            elif path.startswith('/v1/payments/') and path.endswith('/refund'):
                key = self.headers.get('X-Refund-Idempotency');
                pid = path.split('/')[3]
                if key in refund_keys:
                    result = refund_keys[key]
                else:
                    rid = 'rfnd_' + uuid.uuid4().hex
                    result = dict(id=rid, payment_id=pid, amount=data['amount'], currency='INR', status='processed')
                    refunds[rid] = result;
                    refund_keys[key] = result
                    if faults.pop('refund_response_loss', False):
                        self.send_response(503);
                        self.end_headers();
                        return
            elif path.startswith('/v1/payments/'):
                result = payments.get(path.split('/')[-1])
            elif path.startswith('/v1/refunds/'):
                result = refunds.get(path.split('/')[-1])
            self.send_response(200 if result is not None else 404)
            self.send_header('Content-Type', 'application/json');
            self.end_headers()
            self.wfile.write(json.dumps(result or {}).encode())


ThreadingHTTPServer(('0.0.0.0', 9001), Handler).serve_forever()
