"""Helpers for local Mailpit integration tests; never prints action tokens."""
import json
import re
import time
import urllib.request


def mailbox():
    with urllib.request.urlopen('http://localhost:8025/api/v1/messages?limit=1000', timeout=10) as response:
        return json.load(response).get('messages', [])


def wait_for_token(email, subject, exclude=(), timeout=75):
    for _ in range(timeout):
        for item in mailbox():
            if item['ID'] in exclude or item['Subject'] != subject: continue
            if not any(address['Address'].lower() == email.lower() for address in item['To']): continue
            with urllib.request.urlopen('http://localhost:8025/api/v1/message/' + item['ID'], timeout=10) as response:
                message = json.load(response)
            match = re.search(r'#token=([A-Za-z0-9_-]{43})', message['Text'])
            if match: return match.group(1), item['ID']
        time.sleep(1)
    raise AssertionError('Expected action email was not delivered through SMTP')
