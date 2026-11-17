# Movie Ticket Booking

Java 21, Spring Boot 4, PostgreSQL, Kafka and Redis. Auth service provides an OIDC demo login; booking service provides
show discovery, seat selection, reservations, confirmation and automatic expiry.

## Run locally

Start Docker, then run:

```sh
docker compose up --build -d
docker compose logs -f auth-service booking-service
```

Open http://localhost:8080/oauth2/authorization/oidc-client and log in with `user` / `password`, then approve consent.
Swagger opens at http://localhost:8080/swagger-ui.html. These credentials and client secret are for local development
only. Use localhost consistently for login.

The booking service applies Flyway migrations and seeds show 1 with seats A1–J10, priced at 250 each, seven days after
first initialization. Fresh databases need no manual SQL. Existing databases created with the old schema require an
explicit backup and migration before startup; automatic baselining is deliberately disabled. The legacy SQL under `db/`
is not used by Compose. Do not delete existing volumes containing data you need.

## Workflow

Use Swagger after login or supply an OAuth bearer access token to these authenticated endpoints:

1. `GET /api/v1/shows?movieId=1&theatreId=1` — discover future active shows.
2. `GET /api/v1/shows/1/seats` — inspect inventory.
3. `POST /api/v1/booking` with:

```json
{
  "showId": 1,
  "seatCount": 3,
  "seatNumbers": ["A1", "A2", "A3"],
  "offerCode": "THIRD_TICKET_50_DISCOUNT"
}
```

The response contains `booking.id`. Base price is 750, discount 125, final amount 625. Omit `offerCode` for no discount.
The server derives the user ID from the authenticated subject; client user IDs are ignored.

4. `GET /api/v1/booking/{id}` — inspect pending booking without changing it.
5. `POST /api/v1/booking/{id}/payments` with `Idempotency-Key: <unique-attempt-key>` and
   `{"paymentToken":"tok_success"}` — pay the server-calculated amount and confirm automatically. Direct
   `PUT /api/v1/booking/{id}/confirm` requires a successful payment; repeated confirmation is safe.
6. `GET /api/v1/booking/me/bookings` — list your bookings.

Reservations expire after 15 minutes; a scheduled task releases them within the following minute. PostgreSQL row locks
serialize competing seat reservations; seats remain RESERVED until confirmation or expiry. Confirmed bookings are
excluded from expiry. Payments use a local simulator. There is no external payment gateway, user registration or movie
metadata catalog.

Booking transitions and outbox writes share a database transaction. The publisher waits for Kafka acknowledgement and
retries failed delivery. Delivery is at least once: consumers must deduplicate events (booking reference and event
type). Topics are `booking-initiated`, `booking-confirmed`, `booking-expired`.

## Validate

Ensure Maven runs on Java 21 (`mvn -version`):

```sh
mvn test
docker compose config --quiet
```

Regression tests cover pricing, optional offers, duplicate seats, ownership, read-only retrieval, expired confirmation
and idempotent confirmation. A running Docker daemon is needed for the full live workflow. Production deployment also
requires persistent authorization keys/users, secret management, TLS, real payment verification and operational
monitoring.

## Live smoke test

With the Compose services running, execute `python3 scripts/e2e.py`. It signs in with the local demo user and tests
payment, confirmation, cancellation and refund for three available seats. Each successful run cancels its paid booking
and releases its seats.

`python3 scripts/e2e.py --expiry` additionally creates a pending booking, adjusts only that booking's expiry timestamp
through the PostgreSQL container, and waits for the scheduler to release it. This option requires Docker access and
takes up to 75 seconds. Confirmed bookings remain intact.

## Payments, cancellation and refunds

The local provider accepts `tok_success` and `tok_decline`. These are test tokens: no real charge or refund occurs and
no card details are accepted. Amounts are calculated by the booking service in INR. Disable simulated charges outside
local development with `PAYMENTS_LOCAL_ENABLED=false`; a real provider requires a separate integration with durable
provider idempotency and verified webhooks.

- `POST /api/v1/booking/{id}/payments`: send `Idempotency-Key` (1–100 characters) and `{"paymentToken":"tok_success"}`.
  A successful payment atomically confirms the booking. A decline returns a durable `DECLINED` payment and leaves the
  booking pending; retry with a new key to make a new attempt. Reusing a key returns the existing attempt; using that
  key with a different token fails. A returned `DECLINED` payment is an HTTP 200 response, so inspect its status.
- `GET /api/v1/booking/{id}/payments`: view payment attempts for your booking.
- `PUT /api/v1/booking/{id}/cancel`: cancel a pending or confirmed booking before showtime. Paid cancellations
  automatically receive a full refund of the amount actually charged, including discounts. Seats are released and
  confirmed inventory counts restored exactly once. Repeating cancellation is safe.
- `GET /api/v1/booking/{id}/refunds`: view the full refund record.
- `POST /api/v1/booking/{id}/refunds`: request/retrieve the same refund for a cancelled, paid booking. Only one full
  refund is supported; unpaid bookings and active bookings cannot be refunded.

Payment, refund, booking and outbox records commit together for the local provider. Database booking locks serialize
payment, cancellation, expiry and retries; database constraints enforce one successful payment and one refund per
booking. New event topics are `payment-succeeded`, `payment-declined`, `payment-refunded` and `booking-cancelled`.
Legacy confirmed bookings created before payments existed have no charge record and therefore no refund to issue.

## Razorpay test-mode gateway

The Razorpay integration uses real HTTPS Orders, Payments and Refunds APIs when enabled. It accepts only `rzp_test_`
keys. The local token simulator is disabled while Razorpay is enabled. Existing local test records remain available;
they do not represent Razorpay transactions.

1. Copy `.env.example` to `.env` and enter your Razorpay **Test Mode** key ID, key secret, and a separate webhook
   signing secret. `.env` is ignored by Git. Do not enter live keys.
2. Run `docker compose up --build -d --remove-orphans --wait`. Compose passes the secrets to booking-service; they are
   never returned by the API. Keep automatic capture enabled in Razorpay Test Mode: authorization alone does not confirm
   a booking.
3. Sign in at `http://localhost:8080/oauth2/authorization/oidc-client`, create a booking using Swagger, then open
   `http://localhost:8080/checkout.html?bookingId=BOOKING_ID`. Checkout uses Razorpay's hosted JavaScript and test
   payment methods.
4. In the Razorpay Test Mode dashboard, register `https://YOUR-PUBLIC-HOST/api/v1/webhooks/razorpay`, using the same
   webhook secret. Subscribe to `payment.captured`, `order.paid`, `refund.processed`, and `refund.failed`. Your public
   host must forward to booking-service. Localhost cannot receive provider webhooks; use a supported HTTPS tunnel or
   staging deployment. See [Razorpay webhook testing guidance](https://razorpay.com/docs/webhooks/validate-test/).
5. Run `python3 -u scripts/razorpay_sandbox_e2e.py`. It creates a booking and opens Checkout; complete a test payment in
   the browser. It verifies confirmation, cancels the booking, and waits for the full refund. Inspect Razorpay's
   delivery logs and the `razorpay_webhook` inbox separately to confirm that the provider delivered the webhook, since
   reconciliation can recover without it.

Gateway endpoints (authenticated owner only):

- `POST /api/v1/booking/{id}/razorpay/order` with `Idempotency-Key`: returns public `keyId`, `orderId`, amount in paise
  and currency. The same server-created order is reused for checkout retries while the booking is payable.
- `POST /api/v1/booking/{id}/razorpay/verify` with `{"orderId":"order_...","paymentId":"pay_...","signature":"..."}`:
  validates the signature against the stored order ID, then fetches the payment from Razorpay and checks ID, order,
  amount, currency and captured status before confirming.
  See [Razorpay integration steps](https://github.com/razorpay/markdown-docs/blob/master/payments/payment-gateway/quick-integration/integration-steps.md).
- `POST /api/v1/webhooks/razorpay`: public ingress authenticated by HMAC-SHA256 over exact raw request bytes. Valid JSON
  is persisted before acknowledgement. A SHA-256 body digest deduplicates identical signed deliveries independently of
  unsigned headers. Processing is asynchronous and repeat state transitions are safe.
- Existing cancellation and refund endpoints apply. Cancellation commits seat release and a durable `PENDING` refund;
  the worker issues the provider refund afterward. Refunds become `SUCCEEDED` only when Razorpay reports `processed`.
  `failed` is visible as `FAILED` and needs operator investigation rather than generating another refund automatically.

Every ten seconds the worker processes the webhook inbox, reconciles outstanding orders, and retries/polls refunds. A
late capture after cancellation or expiry queues a full refund without confirming or reclaiming seats. A stable
`booking-refund-ID` key and identical amount are sent in `X-Refund-Idempotency`, so a timeout after money moved can be
retried safely.
See [Razorpay idempotent refunds](https://github.com/razorpay/markdown-docs/blob/master/api/refunds/normal-refunds-idempotent.md).
Order creation has no assumed universal provider idempotency header: retries first reconcile orders by the booking
receipt to recover a remote create whose response was lost; only a persisted order is returned to Checkout.

### Automated gateway contract tests

These tests use a **local HTTP fixture**, not the actual Razorpay sandbox. They need no gateway credentials and do not
prove account activation, real test-card acceptance or public webhook delivery:

```sh
docker compose -f docker-compose.yml -f scripts/compose.razorpay-contract.yml up --build -d --wait
python3 -u scripts/razorpay_contract_e2e.py
docker compose up -d --remove-orphans --wait
```

The override uses explicit fixture-only keys and a mock API host. Contract tests cover order reuse, tampered signatures,
captured-payment verification, duplicate webhooks, queued refunds, late captures, and recovery without callbacks.
`mvn test` also checks amount/currency tampering, authorization-only payments, signature byte integrity, test-key
enforcement, refund idempotency headers and late-capture compensation. Real sandbox validation requires the credentials,
Checkout interaction and reachable webhook URL above.

## Authentication API (port 9000)

The auth service provides JSON authentication for first-party clients and keeps the existing OAuth2/OIDC
authorization-code login. New JSON access tokens are RS256 JWTs with issuer `http://localhost:9000`, a 15-minute
lifetime, scopes `profile read write`, and audiences `booking-service` and `auth-service`. Use them as
`Authorization: Bearer ACCESS_TOKEN` with either service. Public signing keys are available at `/oauth2/jwks`; private
keys are never returned.

| Endpoint                       | Request                                                                                | Result                                                    |
|--------------------------------|----------------------------------------------------------------------------------------|-----------------------------------------------------------|
| `POST /api/v1/auth/register`   | `{"username":"alice","password":"a-long-unique-password","email":"alice@example.com"}` | 201, creates a user                                       |
| `POST /api/v1/auth/login`      | Username/password JSON (no email required)                                             | 200, tokens; 202, MFA challenge when enabled              |
| `POST /api/v1/auth/token`      | Same username/password JSON                                                            | Alias for first-party login                               |
| `POST /api/v1/auth/refresh`    | `{"refresh_token":"REFRESH_TOKEN"}`                                                    | 200, new access token and rotated refresh token           |
| `POST /api/v1/auth/logout`     | Refresh-token JSON                                                                     | 204, revokes that login session; repeat requests are safe |
| `POST /api/v1/auth/logout-all` | Bearer access token, no body                                                           | 204, revokes all JSON refresh sessions for the user       |
| `GET /api/v1/auth/me`          | Bearer access token                                                                    | Username and roles                                        |

Example login:

```sh
curl -sS http://localhost:9000/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"user","password":"password"}'
```

Token responses contain `access_token`, `refresh_token`, `token_type`, `expires_in`, `refresh_expires_in` and `scope`,
with `Cache-Control: no-store`. Refresh tokens are opaque random secrets; only their SHA-256 hashes are stored. Every
refresh consumes the old token and returns a new one. Save the new refresh token atomically and serialize refresh calls
in the client: reusing a consumed token revokes the entire login family, including tokens issued by a simultaneous
refresh. Families expire seven days after login; rotation does not extend that deadline. Logout revokes refresh
credentials; existing signed access tokens remain valid until expiry (up to 15 minutes).

Usernames are case-insensitive and contain 3–64 letters, digits, dots, underscores or hyphens. Registered passwords
require at least 12 characters and at most 72 UTF-8 bytes, preventing bcrypt truncation. Passwords are bcrypt-hashed.
Invalid credentials return 401; malformed requests return 400; duplicate usernames return 409. Five failed login
attempts block that username for five minutes (429). Deployments should also enforce IP-based rate limiting at their
gateway. Browser form login continues to use Spring Security's browser authentication flow.

Flyway manages auth tables in the separate `auth` PostgreSQL schema. Users, JSON refresh-token families and the signing
key persist across restarts. Expired families are cleaned hourly after a one-day retention interval. Protect database
access, backups and the private signing-key column; production key rotation/KMS integration is separate operational
work. The legacy OIDC authorization/consent sessions still use Spring's default in-memory store; JSON API session
persistence does not make those sessions durable. For OAuth2 clients continue using `/oauth2/authorize`, `/oauth2/token`
(authorization code / refresh token), `/oauth2/revoke`, and `/connect/logout`; JSON logout endpoints affect the JSON
API's refresh sessions.

The local `user` / `password` account is seeded for compatibility with the demo booking flow. Set
`AUTH_DEMO_ENABLED=false` before first initialization to omit it; disabling seeding does not delete an existing account.
Compose also accepts `AUTH_ACCESS_TTL` (default `15m`) and `AUTH_REFRESH_TTL` (default `7d`). Use TLS outside localhost
and keep tokens out of URLs and logs. Administration is not implemented by these endpoints. MFA is described below.
Email verification and recovery endpoints are described below.

Run `python3 -u scripts/auth_e2e.py --restart` with the stack running to verify registration, booking access, ownership
isolation, refresh rotation/replay detection, concurrent refresh, logout, throttling and persistence. This creates
unique test users, cancels its test booking, restarts only auth-service, and accelerates the expiry timestamp of only
its own test refresh session. Run without `--restart` to omit restart/database manipulation.

## Email verification and password recovery

New registration now requires an email address and creates an unverified account. The service sends a verification email
automatically. New accounts cannot use API or browser login until verification succeeds. Usernames remain stable; emails
are normalized to lowercase and must be unique. Existing accounts were migrated as verified to preserve access; accounts
with no email cannot use email recovery until an address is attached through account administration.

| Endpoint on port 9000                          | JSON request                                                         | Result                                            |
|------------------------------------------------|----------------------------------------------------------------------|---------------------------------------------------|
| `POST /api/v1/auth/forgot-password`            | `{"email":"alice@example.com"}`                                      | 202, queues a reset email for an eligible account |
| `POST /api/v1/auth/reset-password`             | `{"token":"TOKEN_FROM_EMAIL","newPassword":"a-new-unique-password"}` | 204, changes the password                         |
| `POST /api/v1/auth/email-verification/request` | `{"email":"alice@example.com"}`                                      | 202, resends a verification email when eligible   |
| `POST /api/v1/auth/verify-email`               | `{"token":"TOKEN_FROM_EMAIL"}`                                       | 204, verifies the address                         |

Forgot-password and resend requests return the same 202 message for unknown, disabled, already verified or rate-limited
addresses. Issuance is limited to once per account/purpose per minute. Reset links expire in 30 minutes; verification
links expire in 24 hours. Tokens have 256 bits of randomness, are stored as hashes, are single-use, and are bound to
their purpose. Resending invalidates previous tokens for that purpose. Invalid, expired, used, or wrong-purpose tokens
return 400. Weak reset passwords return 400 without consuming the token.

Reset changes the bcrypt password, invalidates all JSON refresh sessions and other reset links, clears account login
lockout, and sends a password-change notification. Auth-service browser sessions are invalidated after commit. Pre-reset
OAuth2 authorization-code/refresh grants are rejected at subsequent JWT issuance. Existing stateless access tokens
remain usable until their expiry; independently held booking-service browser sessions retain that service's session
lifecycle. Browser invalidation tracks sessions on this auth-service instance; a multi-instance deployment needs a
shared session store and distributed invalidation.

Email links open `/verify-email.html` and `/reset-password.html`. The token is in the URL fragment (not sent in HTTP
request URLs), is removed from browser history on load, and is submitted as JSON only after the user confirms. Merely
following a link does not consume a token. The pages send no referrer and load no third-party resources.

The SMTP adapter uses a durable outbox in the same transaction as account/token changes. Pending mail bodies are
encrypted with AES-GCM; ciphertext is erased after successful delivery. The encryption key persists in the protected
`auth.mail_key` table and must be included in protected backups; production key custody should use managed secrets/KMS.
SMTP failures retain queued messages and retry every 30 seconds, including across restarts. SMTP delivery is at least
once: a crash after sending may produce a duplicate email, but its token can still be used only once. Expired action
records and sent-mail metadata are cleaned periodically.

Local Compose starts [Mailpit](https://mailpit.axllent.org/docs/install/docker/), an SMTP capture inbox, at
**http://localhost:8025**. It captures mail without sending it to external recipients. SMTP and inbox ports bind only to
localhost. View the real rendered message and use its link to verify or reset an account. Do not expose this
unauthenticated development inbox publicly.

For an external SMTP server, set `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH=true`, and
`SMTP_STARTTLS=true` in your ignored `.env`. STARTTLS is required when enabled, with finite connection/read/write
timeouts. Set `AUTH_MAIL_FROM` to your permitted sender and `AUTH_PUBLIC_BASE_URL` to the public HTTPS origin for
auth-service. Links are built from that configured origin, never an incoming Host header. Use deployment-level IP rate
limits in addition to the per-account resend limit.

Run `python3 -u scripts/account_recovery_e2e.py` to test delivered SMTP messages, verification login gating, purpose
separation, expiry, resend throttling, concurrent single-use, password reset, old-password rejection, JSON/OAuth2
session revocation, and retry delivery through a Mailpit outage and auth-service restart. It creates unique test users,
changes only their token timestamps, temporarily stops/restarts Mailpit and auth-service, and leaves them running.
Existing auth regression tests now verify their new users through Mailpit before login.

## Preventing duplicate bookings and payments

Send a stable `Idempotency-Key` (1–100 characters) with `POST /api/v1/booking` for each logical reservation. Concurrent
retries by the same authenticated user return the original booking, including after cancellation or expiry; use a new
key for a new reservation. Reusing a key with a different show, seats or offer returns HTTP 409. Seat order does not
change the request identity. The header remains optional for existing clients, but retries without it do not replay a
prior result.

PostgreSQL transaction locks serialize reservation retries and competing seat operations. A unique partial index permits
only one active reservation/booking per seat, and inventory bounds are checked in the database. Lifecycle operations
lock inventory in a consistent order and refresh locked rows before changing counters.

All Checkout retries and browser tabs reuse one Razorpay order per booking, enforced by a database unique index. Settled
payments cannot open another payable attempt. Callbacks, signed webhook redelivery, cancellation and refunds are
idempotent under the booking lock. An ambiguous gateway order response is recovered using the stable booking receipt. A
late capture queues a refund without reclaiming released seats. Unexpected additional provider payment IDs are rejected
for operator investigation; this integration supports one full refund per booking.

Checkout keeps the booking fixed while a payment is open and reuses its order request key. Correctness is enforced on
the server and database, including when clients bypass the UI.

Run the concurrent HTTP regression with the local gateway fixture (no real Razorpay charge):

```sh
docker compose -f docker-compose.yml -f scripts/compose.razorpay-contract.yml up --build -d --wait
python3 -u scripts/duplicate_safety_e2e.py
python3 -u scripts/razorpay_contract_e2e.py
docker compose up -d --wait booking-service
```

The concurrent regression verifies seat contention, reservation replay, changed-payload conflicts, order reuse across
request keys, duplicate callbacks, cancellation/refund retries, and restored inventory. V5/V6 add uniqueness constraints
without deleting or repairing historical data; existing conflicting rows must be reviewed before migration can succeed.

## Multi-factor authentication (MFA)

Users can opt into authenticator-app TOTP. It uses [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238) HMAC-SHA1, six
digits and 30-second steps, with one step of clock tolerance in either direction. Enrolled secrets are encrypted at rest
using the protected persistent encryption key. Accepted counters cannot be reused, including across JSON and browser
login. Keep server and authenticator clocks synchronized; after enrollment or any successful OTP verification, wait for
a new code before using that authenticator again.

All management endpoints below require a bearer access token. Setup also requires the current password. The sole public
MFA endpoint is login challenge verification.

| Endpoint on port 9000                  | Request                                                                                                                | Result                                                                         |
|----------------------------------------|------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------|
| `GET /api/v1/auth/mfa`                 | Bearer token                                                                                                           | Enabled status and count of unused recovery codes; no secrets                  |
| `POST /api/v1/auth/mfa/setup`          | `{"password":"CURRENT_PASSWORD"}`                                                                                      | A temporary Base32 `secret`, `otpauth_uri`, and 600-second enrollment lifetime |
| `POST /api/v1/auth/mfa/confirm`        | `{"code":"123456"}`                                                                                                    | Enables MFA and returns ten recovery codes **once**                            |
| `POST /api/v1/auth/mfa/verify`         | `{"challenge_token":"CHALLENGE","code":"123456"}` or `{"challenge_token":"CHALLENGE","recovery_code":"RECOVERY_CODE"}` | Access and refresh tokens after the second factor succeeds                     |
| `POST /api/v1/auth/mfa/recovery-codes` | `{"password":"CURRENT_PASSWORD","code":"123456"}` or password plus `recovery_code`                                     | Replaces all recovery codes and returns the new set once                       |
| `POST /api/v1/auth/mfa/disable`        | Password plus `code` or `recovery_code`                                                                                | 204, disables MFA and erases the enrolled secret/recovery codes                |

Setup does not enable MFA until confirmation proves possession of the authenticator. Add the provided Base32 secret
manually to an authenticator app or use an app that can import the `otpauth_uri`. Provisioning data never goes to a
third-party QR service. Pending setup expires after ten minutes; repeating setup replaces that pending secret. An
enabled authenticator cannot be overwritten by calling setup: disable it using both factors first.

For an enabled account, `POST /api/v1/auth/login` and `/token` validate the password and return **202**, not tokens:

```json
{"mfa_required":true,"challenge_token":"OPAQUE_CHALLENGE","expires_in":300}
```

Submit the challenge with exactly one authenticator code or recovery code to `/mfa/verify`. A challenge expires after
five minutes, is stored only as a hash, and is consumed once. It cannot authenticate to any protected endpoint. Tokens
are issued only after verification; JWT `amr` records `pwd` plus `otp` or `recovery_code`. Refresh tokens preserve that
authentication-method claim for their existing session lifetime.

The browser `/login` form also requires a second factor for enabled users, before creating an authenticated browser
session or OAuth2 authorization. Ordinary password-only browser users continue to work. OAuth2 token issuance rejects
grants created before a password/MFA change and rejects password-only bearer principals trying to obtain new grants for
an MFA-enabled account. A pre-change bearer cannot bypass this by obtaining a fresh authorization code.

Enabling or disabling MFA revokes existing JSON refresh sessions and pending challenges, invalidates auth-service
browser sessions after commit, and invalidates older OAuth2 grants at subsequent issuance. Already issued stateless
access tokens retain their expiry; independent booking-service sessions retain that service's lifecycle. Password reset
invalidates pending MFA challenges and keeps MFA enabled: email access alone does not disable the second factor. If the
authenticator is lost, use a recovery code to log in, then use your password and another code to disable/re-enroll the
authenticator. There is no email-only MFA bypass.

Recovery codes each contain 128 bits of random entropy and are stored only as SHA-256 hashes of their normalized value.
Hyphens and letter case are ignored. Store the initial response in a password manager; codes cannot be retrieved later.
Each code is consumed atomically once, including during simultaneous login attempts. Regeneration requires password
reauthentication plus a valid second factor, and immediately invalidates every previous code. A lost response can be
replaced by regenerating with another valid factor.

Five failed MFA checks block further checks for five minutes across challenges and browser/API entry points. Requesting
a new challenge does not reset the failure count. At most ten unexpired challenges are allowed per account; a new
request beyond that returns 429. Successful second-factor verification clears the failure count. Deployment-level IP
limits should supplement account-level limits. Setup, challenge, token and recovery-code responses use
`Cache-Control: no-store`; raw TOTP secrets, challenge tokens and recovery codes are never logged. Protect encryption
keys and backups; multi-instance browser invalidation still requires a shared session store.

Run `python3 -u scripts/mfa_e2e.py` with auth-service, booking-service and Mailpit running. It creates a unique verified
user, enrolls TOTP, exercises API and browser enforcement, verifies booking access and refresh AMR, tests
replay/concurrent recovery use, expiry, throttling, regeneration, restart persistence, password-reset behavior and
disabling MFA. It restarts only auth-service, accelerates only its test user's enrollment/challenge/lockout timestamps,
uses independent Python TOTP calculations against real time, and leaves the test user's MFA disabled at the end.
`mvn test` includes RFC TOTP vectors, factor enforcement and OAuth2 alternate-path security tests.
