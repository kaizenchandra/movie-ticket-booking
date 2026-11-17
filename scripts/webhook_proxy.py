"""Expose only Razorpay's signed webhook route to the development tunnel."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != '/api/v1/webhooks/razorpay':
            self.send_error(404)
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            self.send_error(400)
            return
        if not 0 < size <= 1048576:
            self.send_error(413)
            return
        body = self.rfile.read(size)
        connection = http.client.HTTPConnection('booking-service', 8080, timeout=20)
        try:
            connection.request('POST', self.path, body, {
                'Content-Type': 'application/json',
                'X-Razorpay-Signature': self.headers.get('X-Razorpay-Signature', ''),
            })
            response = connection.getresponse()
            payload = response.read()
            self.send_response(response.status)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (OSError, http.client.HTTPException):
            self.send_error(502)
        finally:
            connection.close()

    def do_GET(self):
        self.send_error(404)


if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0', 8091), Handler).serve_forever()
