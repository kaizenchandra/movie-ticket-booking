#!/usr/bin/env python3
"""Live MFA regression using an independent Python TOTP generator and local Mailpit."""
import base64
import hashlib
import hmac
import http.cookiejar
import json
import secrets
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser

from mail_test_support import wait_for_token

base = 'http://localhost:9000/api/v1/auth'


def call(path, data=None, token=None, method=None):
    headers = {'Accept': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    if data is not None: data = json.dumps(data).encode();headers['Content-Type'] = 'application/json'
    url = path if path.startswith('http') else base + path
    with urllib.request.urlopen(urllib.request.Request(url, data, headers, method=method), timeout=20) as response:
        body = response.read();
        return response.status, json.loads(body) if body else None, response.headers


def expect(code, action):
    try:
        action();raise AssertionError('Request unexpectedly succeeded')
    except urllib.error.HTTPError as error:
        assert error.code == code, (error.code, code, error.read().decode())


def sql(query):
    return subprocess.run(
        ['docker', 'compose', 'exec', '-T', 'postgres-db', 'psql', '-U', 'kaizen', '-d', 'mtb', '-v', 'ON_ERROR_STOP=1',
         '-tA', '-c', query], check=True, capture_output=True, text=True).stdout.strip()


def otp(secret, step=None):
    step = int(time.time()) // 30 if step is None else step
    digest = hmac.new(base64.b32decode(secret), struct.pack('>Q', step), hashlib.sha1).digest();
    offset = digest[-1] & 15
    return str((struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7fffffff) % 1000000).zfill(6)


def claims(token):
    value = token.split('.')[1];
    return json.loads(base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)))


class Form(HTMLParser):
    def __init__(self, text): super().__init__();self.fields = [];self.feed(text)

    def handle_starttag(self, tag, attrs):
        data = dict(attrs)
        if tag == 'input' and data.get('name'): self.fields.append((data['name'], data.get('value', '')))


def browser_login(username, password, recovery=None, success=True):
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def open_url(url, data=None):
        response = client.open(urllib.request.Request(url, data, {'Accept': 'text/html'}), timeout=20)
        return response.geturl(), response.read().decode()

    _, html = open_url('http://localhost:8080/oauth2/authorization/oidc-client')
    fields = dict(Form(html).fields);
    fields.update(username=username, password=password, recoveryCode=recovery or '', code='')
    url, html = open_url('http://localhost:9000/login', urllib.parse.urlencode(fields).encode())
    if not success:
        assert '/login?error' in url, url
        return None, None
    if 'consent' in html.lower() and 'scope' in html:
        url, html = open_url('http://localhost:9000/oauth2/authorize',
                             urllib.parse.urlencode([(k, v) for k, v in Form(html).fields if k != 'cancel']).encode())
    assert '8080' in url and '/login' not in url, url
    _, body = open_url('http://localhost:8080/api/v1/auth/token')
    import re
    refresh = re.search(r'Refresh Token = ([^ <]+)', body).group(1)
    access = re.search(r'Access Token = ([^ <]+)', body).group(1)
    return client, (access, refresh)


def reject_password_only_oauth_grant(access):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs): return None

    client = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    parameters = dict(response_type='code', client_id='oidc-client',
                      redirect_uri='http://localhost:8080/login/oauth2/code/oidc-client', scope='profile read',
                      state=secrets.token_urlsafe(24))

    def authorize(url, data=None):
        try:
            response = client.open(
                urllib.request.Request(url, data, {'Accept': 'text/html', 'Authorization': 'Bearer ' + access}),
                timeout=20)
            return None, response.read().decode()
        except urllib.error.HTTPError as error:
            if error.code in (401, 403): return 'rejected', ''
            if error.code == 302: return error.headers.get('Location'), ' '
            raise

    location, html = authorize('http://localhost:9000/oauth2/authorize?' + urllib.parse.urlencode(parameters))
    if location is None:
        fields = Form(html).fields
        location, _ = authorize('http://localhost:9000/oauth2/authorize',
                                urllib.parse.urlencode([(k, v) for k, v in fields if k != 'cancel']).encode())
    if location == 'rejected': return
    assert location, 'Authorization did not produce a deterministic result'
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get('code', [None])[0]
    if code is None: return  # Rejected before code issuance.
    data = urllib.parse.urlencode(
        dict(grant_type='authorization_code', code=code, redirect_uri=parameters['redirect_uri'])).encode()
    try:
        urllib.request.urlopen(urllib.request.Request('http://localhost:9000/oauth2/token', data, {
            'Authorization': 'Basic ' + base64.b64encode(b'oidc-client:secret').decode()}), timeout=20)
        raise AssertionError('Password-only bearer minted an OAuth2 token after MFA enrollment')
    except urllib.error.HTTPError as error:
        assert error.code in (400, 401, 403), error.code


username = 'mfa_' + uuid.uuid4().hex[:16];
email = username + '@example.test';
password = 'Initial-' + secrets.token_urlsafe(16)
credentials = dict(username=username, password=password)
call('/register', dict(**credentials, email=email));
verification, _ = wait_for_token(email, 'Verify your email');
call('/verify-email', dict(token=verification))
_, initial, _ = call('/login', credentials);
old_access = initial['access_token']
old_browser, old_oauth = browser_login(username.upper(), password)
expect(401, lambda: call('/mfa/setup', dict(password=password)))
expect(401, lambda: call('/mfa/setup', dict(password='incorrect-password'), old_access))
_, pending, headers = call('/mfa/setup', dict(password=password), old_access)
assert 'no-store' in headers['Cache-Control'] and pending['otpauth_uri'].startswith('otpauth://totp/')
assert call('/mfa', token=old_access)[1]['enabled'] is False
sql("UPDATE auth.mfa_config SET pending_expires=clock_timestamp()-INTERVAL '1 second' WHERE username='" + username + "'")
expect(401, lambda: call('/mfa/confirm', dict(code=otp(pending['secret'])), old_access))
_, pending, _ = call('/mfa/setup', dict(password=password), old_access);
secret = pending['secret']
enrollment_step = int(time.time()) // 30
_, enabled, headers = call('/mfa/confirm', dict(code=otp(secret, enrollment_step)), old_access)
recovery = enabled['recovery_codes'];
assert len(recovery) == 10 and len(set(recovery)) == 10 and 'no-store' in headers['Cache-Control']
assert call('/mfa', token=old_access)[1] == dict(enabled=True, recovery_codes_remaining=10)
assert sql("SELECT encrypted_secret FROM auth.mfa_config WHERE username='" + username + "'") != secret
stored = sql("SELECT code_hash FROM auth.mfa_recovery_code WHERE username='" + username + "'")
assert all(code not in stored for code in recovery)
expect(401, lambda: call('/refresh', dict(refresh_token=initial['refresh_token'])))
try:
    request = urllib.request.Request('http://localhost:9000/oauth2/token', urllib.parse.urlencode(
        dict(grant_type='refresh_token', refresh_token=old_oauth[1])).encode(),
                                     {'Authorization': 'Basic ' + base64.b64encode(b'oidc-client:secret').decode()})
    urllib.request.urlopen(request, timeout=20);
    raise AssertionError('Pre-MFA OAuth2 grant bypassed MFA')
except urllib.error.HTTPError as error:
    assert error.code == 400, error.code
reject_password_only_oauth_grant(old_access)
print(
    'PASS password reauthentication, expiring setup, enrollment, recovery disclosure, encrypted storage and rejection of pre-MFA/alternate OAuth grants')

status, challenge, _ = call('/login', credentials)
assert status == 202 and challenge[
    'mfa_required'] and 'access_token' not in challenge and 'refresh_token' not in challenge
expect(401, lambda: call('/me', token=challenge['challenge_token']))
expect(401, lambda: call('/mfa/verify',
                         dict(challenge_token=challenge['challenge_token'], code=otp(secret, enrollment_step))))
last_step = max(int(time.time()) // 30, enrollment_step) + 1
_, verified, _ = call('/mfa/verify', dict(challenge_token=challenge['challenge_token'], code=otp(secret, last_step)))
assert claims(verified['access_token'])['amr'] == ['pwd', 'otp']
expect(401, lambda: call('/mfa/verify', dict(challenge_token=challenge['challenge_token'], recovery_code=recovery[0])))
_, rotated, _ = call('/refresh', dict(refresh_token=verified['refresh_token']))
assert claims(rotated['access_token'])['amr'] == ['pwd', 'otp']
assert call('http://localhost:8080/api/v1/shows', token=verified['access_token'])[0] == 200
browser_login(username, password, success=False)
_, browser_tokens = browser_login(username, password, recovery[0])
assert claims(browser_tokens[0])['amr'] == ['pwd', 'recovery_code']
browser_login(username, password, recovery[0], success=False)
print(
    'PASS password-only challenge gating, TOTP verification/replay rejection, JWT/refresh AMR, booking access and mandatory browser MFA')

_, concurrent, _ = call('/login', credentials)


def verify_once(_):
    try:
        return call('/mfa/verify', dict(challenge_token=concurrent['challenge_token'], recovery_code=recovery[1]))[0]
    except urllib.error.HTTPError as error:
        return error.code


with ThreadPoolExecutor(max_workers=2) as pool: assert sorted(pool.map(verify_once, range(2))) == [200, 401]
_, challenge, _ = call('/login', credentials)
expect(401, lambda: call('/mfa/verify', dict(challenge_token=challenge['challenge_token'], recovery_code=recovery[1])))
_, active, _ = call('/mfa/verify', dict(challenge_token=challenge['challenge_token'], recovery_code=recovery[2]))
_, expired, _ = call('/login', credentials)
digest = hashlib.sha256(expired['challenge_token'].encode()).hexdigest()
sql("UPDATE auth.mfa_challenge SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE token_hash='" + digest + "'")
expect(401, lambda: call('/mfa/verify', dict(challenge_token=expired['challenge_token'], recovery_code=recovery[3])))
_, challenge, _ = call('/login', credentials)
for _ in range(5): expect(401, lambda: call('/mfa/verify',
                                            dict(challenge_token=challenge['challenge_token'], recovery_code='0' * 32)))
expect(429, lambda: call('/mfa/verify', dict(challenge_token=challenge['challenge_token'], recovery_code=recovery[3])))
expect(429, lambda: call('/login', credentials))
sql("UPDATE auth.mfa_config SET blocked_until=clock_timestamp()-INTERVAL '1 second' WHERE username='" + username + "'")
_, active, _ = call('/mfa/verify', dict(challenge_token=challenge['challenge_token'], recovery_code=recovery[3]))
_, regenerated, _ = call('/mfa/recovery-codes', dict(password=password, recovery_code=recovery[4]),
                         active['access_token'])
new_codes = regenerated['recovery_codes'];
assert set(new_codes).isdisjoint(recovery)
_, challenge, _ = call('/login', credentials)
expect(401, lambda: call('/mfa/verify', dict(challenge_token=challenge['challenge_token'], recovery_code=recovery[5])))
_, active, _ = call('/mfa/verify', dict(challenge_token=challenge['challenge_token'], recovery_code=new_codes[0]))
print('PASS concurrent recovery single-use, expired challenges, durable account lockout and recovery-code regeneration')

subprocess.run(['docker', 'compose', 'restart', 'auth-service'], check=True, stdout=subprocess.DEVNULL,
               stderr=subprocess.DEVNULL)
for _ in range(60):
    try:
        if call('http://localhost:9000/actuator/health')[1]['status'] == 'UP': break
    except (urllib.error.URLError, ConnectionError):
        pass
    time.sleep(1)
else:
    raise AssertionError('Auth service did not restart')
# Wait at most one real 30-second window rather than weakening the replay counter in the database.
while int(time.time()) // 30 < last_step: time.sleep(1)
_, challenge, _ = call('/login', credentials)
_, active, _ = call('/mfa/verify',
                    dict(challenge_token=challenge['challenge_token'], code=otp(secret, int(time.time()) // 30 + 1)))
assert claims(active['access_token'])['amr'] == ['pwd', 'otp']
print('PASS restart-persistent encrypted authenticator secret, configuration and recovery codes')

_, pre_reset, _ = call('/login', credentials)
call('/forgot-password', dict(email=email));
reset, _ = wait_for_token(email, 'Reset your password')
changed_password = 'Changed-' + secrets.token_urlsafe(16)
call('/reset-password', dict(token=reset, newPassword=changed_password))
expect(401, lambda: call('/mfa/verify', dict(challenge_token=pre_reset['challenge_token'], recovery_code=new_codes[1])))
credentials['password'] = changed_password
status, challenge, _ = call('/login', credentials);
assert status == 202
_, active, _ = call('/mfa/verify', dict(challenge_token=challenge['challenge_token'], recovery_code=new_codes[1]))
expect(401, lambda: call('/mfa/disable', dict(password=changed_password), active['access_token']))
call('/mfa/disable', dict(password=changed_password, recovery_code=new_codes[2]), active['access_token'])
expect(401, lambda: call('/refresh', dict(refresh_token=active['refresh_token'])))
status, plain, _ = call('/login', credentials);
assert status == 200 and 'access_token' in plain
assert claims(plain['access_token'])['amr'] == ['pwd']
assert call('/mfa', token=plain['access_token'])[1] == dict(enabled=False, recovery_codes_remaining=0)
assert sql(
    "SELECT count(*) FROM auth.mfa_config WHERE username='" + username + "' AND encrypted_secret IS NOT NULL") == '0'
print(
    'PASS password reset preserves MFA and invalidates challenges; disabling requires both factors and revokes sessions')
print('MFA end-to-end checks passed')
