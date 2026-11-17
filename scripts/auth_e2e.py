#!/usr/bin/env python3
"""Live auth API regression. Creates uniquely named users and a cancelled booking."""
import hashlib
import json
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

base = 'http://localhost:9000/api/v1/auth'


def call(path, body=None, token=None, method=None):
    headers = {'Accept': 'application/json'}
    if body is not None:
        body = json.dumps(body).encode();
        headers['Content-Type'] = 'application/json'
    if token: headers['Authorization'] = 'Bearer ' + token
    url = path if path.startswith('http') else base + path
    with urllib.request.urlopen(urllib.request.Request(url, body, headers, method=method), timeout=20) as response:
        data = response.read()
        return json.loads(data) if data else None, response.headers


def expect(code, fn):
    try:
        fn()
        raise AssertionError('Request unexpectedly succeeded')
    except urllib.error.HTTPError as error:
        assert error.code == code, (error.code, code, error.read().decode())


username = 'test_' + uuid.uuid4().hex[:16]
password = 'Password-' + secrets.token_urlsafe(18)
credentials = dict(username=username, password=password, email=username + "@example.test")
expect(401, lambda: call('/me'))
expect(400, lambda: call('/register', dict(username='short', password='short')))
created, _ = call('/register', credentials)
assert created['username'] == username
from mail_test_support import wait_for_token

verification, _ = wait_for_token(credentials['email'], 'Verify your email')
call('/verify-email', dict(token=verification))
expect(409, lambda: call('/register', dict(username=username.upper(), password=password, email=credentials["email"])))
expect(401, lambda: call('/login', dict(username=username, password='invalid-password')))
tokens, headers = call('/login', credentials)
assert tokens['token_type'] == 'Bearer' and tokens['expires_in'] == 900
assert 'no-store' in headers['Cache-Control']
assert call('/me', token=tokens['access_token'])[0]['username'] == username
expect(401, lambda: call('/me', token=tokens['access_token'] + 'tampered'))
print('PASS registration, password validation, duplicate handling, login, JWT verification and no-cache headers')

# The generated JWT works directly against booking-service's issuer/JWKS validation.
access = tokens['access_token']
shows, _ = call('http://localhost:8080/api/v1/shows', token=access)
seats, _ = call(f"http://localhost:8080/api/v1/shows/{shows[0]['id']}/seats", token=access)
seat = next(s['seatNumber'] for s in seats if s['seatStatus'] == 'AVAILABLE')
booking, _ = call('http://localhost:8080/api/v1/booking', dict(showId=shows[0]['id'], seatCount=1, seatNumbers=[seat]),
                  access)
booking_id = booking['booking']['id']
other = 'test_' + uuid.uuid4().hex[:16]
other_credentials = dict(username=other, password=password, email=other + "@example.test")
call('/register', other_credentials)
verification, _ = wait_for_token(other_credentials['email'], 'Verify your email')
call('/verify-email', dict(token=verification))
other_tokens, _ = call('/token', other_credentials)
expect(404, lambda: call(f'http://localhost:8080/api/v1/booking/{booking_id}', token=other_tokens['access_token']))
call(f'http://localhost:8080/api/v1/booking/{booking_id}/cancel', token=access, method='PUT')
print('PASS API-issued JWT booking workflow and cross-user isolation')

rotated, _ = call('/refresh', dict(refresh_token=tokens['refresh_token']))
assert rotated['refresh_token'] != tokens['refresh_token']
assert call('/me', token=rotated['access_token'])[0]['username'] == username
expect(401, lambda: call('/refresh', dict(refresh_token=tokens['refresh_token'])))
expect(401, lambda: call('/refresh', dict(refresh_token=rotated['refresh_token'])))
print('PASS refresh rotation and replay detection revoking the entire token family')

from concurrent.futures import ThreadPoolExecutor

concurrent, _ = call('/login', credentials)


def rotate_once(_):
    try:
        return call('/refresh', dict(refresh_token=concurrent['refresh_token']))[0]
    except urllib.error.HTTPError as exc:
        assert exc.code == 401
        return None


with ThreadPoolExecutor(max_workers=2) as executor:
    attempts = list(executor.map(rotate_once, range(2)))
winners = [item for item in attempts if item is not None]
assert len(winners) == 1
expect(401, lambda: call('/refresh', dict(refresh_token=winners[0]['refresh_token'])))
print('PASS concurrent refresh serializes rotation and rejects replay')

a, _ = call('/login', credentials)
call('/logout', dict(refresh_token=a['refresh_token']))
call('/logout', dict(refresh_token=a['refresh_token']))
expect(401, lambda: call('/refresh', dict(refresh_token=a['refresh_token'])))
a, _ = call('/login', credentials);
b, _ = call('/login', credentials)
call('/logout-all', token=a['access_token'], method='POST')
for item in (a, b): expect(401, lambda item=item: call('/refresh', dict(refresh_token=item['refresh_token'])))
print('PASS idempotent logout and logout-all')

for _ in range(5): expect(401, lambda: call('/login', dict(username=other, password='bad-password')))
expect(429, lambda: call('/login', other_credentials))
print('PASS durable account login throttling')

if '--restart' in sys.argv:
    persisted, _ = call('/login', credentials)
    before = call('http://localhost:9000/oauth2/jwks')[0]
    assert all('d' not in key for key in before['keys'])
    subprocess.run(['docker', 'compose', 'restart', 'auth-service'], check=True, stdout=subprocess.DEVNULL)
    for _ in range(60):
        try:
            if call('http://localhost:9000/actuator/health')[0]['status'] == 'UP': break
        except (urllib.error.URLError, ConnectionError):
            pass
        time.sleep(1)
    else:
        raise AssertionError('Auth service did not restart')
    assert call('/me', token=persisted['access_token'])[0]['username'] == username
    assert call('http://localhost:9000/oauth2/jwks')[0] == before
    fresh, _ = call('/refresh', dict(refresh_token=persisted['refresh_token']))
    # Expiry is accelerated only for this test session, never for another user.
    digest = hashlib.sha256(fresh['refresh_token'].encode()).hexdigest()
    sql = "UPDATE auth.refresh_session SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=(SELECT session_id FROM auth.refresh_token WHERE token_hash='" + digest + "')"
    subprocess.run(
        ['docker', 'compose', 'exec', '-T', 'postgres-db', 'psql', '-U', 'kaizen', '-d', 'mtb', '-v', 'ON_ERROR_STOP=1',
         '-c', sql], check=True, capture_output=True)
    expect(401, lambda: call('/refresh', dict(refresh_token=fresh['refresh_token'])))
    print('PASS restart-persistent users, signing keys and refresh tokens; expired refresh rejected')
print('Auth API end-to-end checks passed')
