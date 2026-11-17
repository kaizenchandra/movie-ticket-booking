#!/usr/bin/env python3
"""Live SMTP verification/recovery tests using Mailpit; changes only unique test users."""
import hashlib
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

from mail_test_support import mailbox, wait_for_token

base = 'http://localhost:9000/api/v1/auth'


def call(path, data):
    with urllib.request.urlopen(
            urllib.request.Request(base + path, json.dumps(data).encode(), {'Content-Type': 'application/json'}),
            timeout=20) as response:
        value = response.read();
        return response.status, json.loads(value) if value else None


def expect(code, action):
    try:
        action();raise AssertionError('Request unexpectedly succeeded')
    except urllib.error.HTTPError as error:
        assert error.code == code, (error.code, code)


def sql(query):
    result = subprocess.run(
        ['docker', 'compose', 'exec', '-T', 'postgres-db', 'psql', '-U', 'kaizen', '-d', 'mtb', '-v', 'ON_ERROR_STOP=1',
         '-tA', '-c', query], capture_output=True, text=True, check=True)
    return result.stdout.strip()


def age(username, purpose):
    sql("UPDATE auth.account_action SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes' WHERE username='" + username + "' AND purpose='" + purpose + "'")


def expire(token):
    digest = hashlib.sha256(token.encode()).hexdigest()
    sql("UPDATE auth.account_action SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE token_hash='" + digest + "'")


def browser_login(username, password):
    import http.cookiejar, urllib.parse, re
    from html.parser import HTMLParser
    class Form(HTMLParser):
        def __init__(self, html): super().__init__();self.fields = [];self.feed(html)

        def handle_starttag(self, tag, attrs):
            data = dict(attrs)
            if tag == 'input' and data.get('name'): self.fields.append((data['name'], data.get('value', '')))

    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def open_url(url, data=None):
        response = client.open(urllib.request.Request(url, data, {'Accept': 'text/html'}), timeout=20)
        return response.geturl(), response.read().decode()

    _, html = open_url('http://localhost:8080/oauth2/authorization/oidc-client')
    fields = dict(Form(html).fields);
    fields.update(username=username, password=password)
    url, html = open_url('http://localhost:9000/login', urllib.parse.urlencode(fields).encode())
    if 'consent' in html.lower() and 'scope' in html:
        fields = [(key, value) for key, value in Form(html).fields if key != 'cancel']
        url, html = open_url('http://localhost:9000/oauth2/authorize', urllib.parse.urlencode(fields).encode())
    assert '8080' in url and '/login' not in url
    _, token_body = open_url('http://localhost:8080/api/v1/auth/token')
    match = re.search(r'Refresh Token = ([^ <]+)', token_body)
    assert match and match.group(1) != 'Unavailable'
    import base64
    preflight = urllib.request.Request('http://localhost:9000/oauth2/token', urllib.parse.urlencode(
        {'grant_type': 'refresh_token', 'refresh_token': match.group(1)}).encode(),
                                       {'Authorization': 'Basic ' + base64.b64encode(b'oidc-client:secret').decode()})
    with urllib.request.urlopen(preflight, timeout=20) as response:
        standard = json.load(response)
    return client, standard.get('refresh_token', match.group(1))


username = 'recovery_' + uuid.uuid4().hex[:12];
email = username + '@example.test'
password = 'Initial-' + secrets.token_urlsafe(16);
new_password = 'Changed-' + secrets.token_urlsafe(16)
credentials = dict(username=username, password=password)
status, registered = call('/register', dict(**credentials, email=email))
assert status == 201 and registered['email_verified'] is False
expect(400, lambda: call('/register', dict(username=username + 'x', password=password, email='bad-email')))
expect(409, lambda: call('/register', dict(username=username + 'x', password=password, email=email.upper())))
expect(401, lambda: call('/login', credentials))
verification, _ = wait_for_token(email, 'Verify your email')
unknown = call('/forgot-password', dict(email='absent_' + uuid.uuid4().hex + '@example.test'))
known = call('/forgot-password', dict(email=email))
assert unknown == known and known[0] == 202
reset, reset_mail = wait_for_token(email, 'Reset your password')
expect(400, lambda: call('/verify-email', dict(token=reset)))
expect(400, lambda: call('/reset-password', dict(token=verification, newPassword=new_password)))
expect(400, lambda: call('/verify-email', dict(token='x' * 43)))


def verify_once(_):
    try:
        return call('/verify-email', dict(token=verification))[0]
    except urllib.error.HTTPError as error:
        return error.code


with ThreadPoolExecutor(max_workers=2) as pool: assert sorted(pool.map(verify_once, range(2))) == [204, 400]
_, session1 = call('/login', credentials);
_, session2 = call('/login', credentials)
browser, oauth_refresh = browser_login(username, password)
print(
    'PASS SMTP verification, login gating, duplicate email rejection, generic recovery responses, purpose isolation and concurrent token consumption')

expire(reset)
expect(400, lambda: call('/reset-password', dict(token=reset, newPassword=new_password)))
assert call('/login', credentials)[0] == 200
age(username, 'RESET_PASSWORD')
call('/forgot-password', dict(email=email))
reset2, mail2 = wait_for_token(email, 'Reset your password', [reset_mail])
age(username, 'RESET_PASSWORD');
call('/forgot-password', dict(email=email))
reset3, mail3 = wait_for_token(email, 'Reset your password', [reset_mail, mail2])
expect(400, lambda: call('/reset-password', dict(token=reset2, newPassword=new_password)))
expect(400, lambda: call('/reset-password', dict(token=reset3, newPassword='short')))


def reset_once(_):
    try:
        return call('/reset-password', dict(token=reset3, newPassword=new_password))[0]
    except urllib.error.HTTPError as error:
        return error.code


with ThreadPoolExecutor(max_workers=2) as pool: assert sorted(pool.map(reset_once, range(2))) == [204, 400]
expect(401, lambda: call('/login', credentials))
assert call('/login', dict(username=username, password=new_password))[0] == 200
for session in (session1, session2): expect(401, lambda session=session: call('/refresh', dict(
    refresh_token=session['refresh_token'])))
expect(400, lambda: call('/reset-password', dict(token=reset3, newPassword=password)))
import base64, urllib.parse

try:
    request = urllib.request.Request('http://localhost:9000/oauth2/token', urllib.parse.urlencode(
        {'grant_type': 'refresh_token', 'refresh_token': oauth_refresh}).encode(),
                                     {'Authorization': 'Basic ' + base64.b64encode(b'oidc-client:secret').decode()})
    urllib.request.urlopen(request, timeout=20)
    raise AssertionError('Pre-reset OAuth2 refresh grant remained usable')
except urllib.error.HTTPError as error:
    assert error.code == 400, error.code
response = browser.open(urllib.request.Request('http://localhost:9000/', headers={'Accept': 'text/html'}), timeout=20)
assert '/login' in response.geturl(), 'Auth browser session was not revoked'
print(
    'PASS expired/superseded reset tokens, password policy, concurrent single-use reset, old-password rejection and refresh-session revocation')
browser_login(username, new_password)
print('PASS pre-reset OAuth2 refresh rejected, auth browser session invalidated and new-password OIDC login allowed')

second = 'verify_' + uuid.uuid4().hex[:12];
second_email = second + '@example.test'
call('/register', dict(username=second, password=password, email=second_email))
first, first_mail = wait_for_token(second_email, 'Verify your email')
before = {m['ID'] for m in mailbox()}
call('/email-verification/request', dict(email=second_email));
time.sleep(4)
assert not [m for m in mailbox() if m['ID'] not in before and any(a['Address'] == second_email for a in m['To'])]
age(second, 'VERIFY_EMAIL');
call('/email-verification/request', dict(email=second_email))
second_token, second_mail = wait_for_token(second_email, 'Verify your email', [first_mail])
expect(400, lambda: call('/verify-email', dict(token=first)))
expire(second_token);
expect(400, lambda: call('/verify-email', dict(token=second_token)))
age(second, 'VERIFY_EMAIL');
call('/email-verification/request', dict(email=second_email))
third, _ = wait_for_token(second_email, 'Verify your email', [first_mail, second_mail])
assert call('/verify-email', dict(token=third))[0] == 204
assert call('/login', dict(username=second, password=password))[0] == 200
print('PASS verification resend throttling, superseded/expired tokens and successful recovery by resend')

# SMTP outage must not roll back a recovery request; the encrypted outbox survives restart.
age(username, 'RESET_PASSWORD')
subprocess.run(['docker', 'compose', 'stop', 'mailpit'], check=True, stdout=subprocess.DEVNULL,
               stderr=subprocess.DEVNULL)
try:
    assert call('/forgot-password', dict(email=email))[0] == 202
    assert int(
        sql("SELECT count(*) FROM auth.mail_outbox WHERE recipient='" + email + "' AND status='PENDING' AND encrypted_body IS NOT NULL")) >= 1
    for _ in range(20):
        if int(
            sql("SELECT count(*) FROM auth.mail_outbox WHERE recipient='" + email + "' AND status='PENDING' AND attempts>0")) > 0: break
        time.sleep(1)
    else:
        raise AssertionError('SMTP delivery failure was not recorded for retry')
    subprocess.run(['docker', 'compose', 'restart', 'auth-service'], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)
finally:
    subprocess.run(['docker', 'compose', 'start', 'mailpit'], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)
# Mailpit may briefly refuse connections during startup.
for _ in range(30):
    try:
        mailbox();break
    except (urllib.error.URLError, ConnectionError):
        time.sleep(1)
final_reset, _ = wait_for_token(email, 'Reset your password', [reset_mail, mail2, mail3])
assert sql("SELECT count(*) FROM auth.account_action WHERE token_hash='" + hashlib.sha256(
    final_reset.encode()).hexdigest() + "'") == '1'
assert call('/reset-password', dict(token=final_reset, newPassword=password))[0] == 204
print('PASS SMTP outage queueing and eventual delivery; action token hashes persisted without plaintext tokens')
print('Email verification and password recovery end-to-end checks passed')
