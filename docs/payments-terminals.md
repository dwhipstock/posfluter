# Card terminals

How a store takes a card on a terminal, and how to demo it without real cards,
real terminals or the internet.

**Frozen rule:** stores work offline, and a payment problem never breaks the
rest of the POS. Every terminal call runs outside a database transaction, and
nothing waits on a terminal except the card payment itself. A dead terminal
returns a coded error such as `terminal_unavailable`. When that happens the
check can still be paid in cash or with the hand-keyed "Card" tender.

## Picking a terminal: `payment.terminal`

| value | what it is | default for |
|---|---|---|
| `stripe` | Stripe Terminal, test mode. The tablet's Terminal SDK (mek_stripe_terminal) connects the reader. Also needs `stripe.secretKey`. Only works for CAD accounts. | Copper Lantern |
| `simulator` | The built-in card terminal simulator (see below). | Sage & Poppy, the gas station, and any new store |
| `jpmorgan` | J.P. Morgan. `payment.jpmorgan.mode=online` (the default) puts the simulated reader in front of J.P. Morgan's Online Payments **sandbox**. `instore` uses their Payment Terminal Application on a real J.P. Morgan terminal. It is never a default. | none |
| `external` | A card on the counter's own terminal, keyed in by hand. There is no integration: staff press "Card" after the terminal approves. | a non-CAD store that asks for `stripe` |
| `off` | No card tender at all, not even the hand-keyed one. | none |

You can set it in the tablet's `store.properties` (`payment.terminal=…`) or
with `POS_PAYMENT_TERMINAL` on a desktop or docker store. The
`POS_CONFIG_FILE` pattern also works, the same way as `kitchen.printing` and
`print.receipts`. If a value is bad, the store logs a warning and falls back
to its default; it never fails to start. Other keys:

| key | env | meaning |
|---|---|---|
| `payment.terminal.host` | `POS_PAYMENT_TERMINAL_HOST` | `ip[:port]` of a terminal on the LAN, such as the Mac simulator (port 8090) or a J.P. Morgan terminal (port 8442). This can also be set by pairing from the POS. |
| `payment.terminal.timeoutSeconds` | `POS_PAYMENT_TERMINAL_TIMEOUT` | How long a payment waits for a card. Default 90, allowed range 15 to 600. |
| `payment.jpmorgan.mode` | `JPM_MODE` | `online` (default) or `instore`. |
| `payment.jpmorgan.clientId` / `.clientSecret` / `.tokenUrl` / `.scope` | `JPM_CLIENT_ID` / `JPM_CLIENT_SECRET` / `JPM_TOKEN_URL` / `JPM_SCOPE` | OAuth client credentials from the J.P. Morgan developer portal. The secret is never logged. |
| `payment.jpmorgan.merchantId` | `JPM_MERCHANT_ID` | The 12-digit `merchant-id` header. On the mock host any value works. |
| `payment.jpmorgan.baseUrl` | `JPM_BASE_URL` | Default `https://api-mock.payments.jpmorgan.com/api/v2`. Only sandbox hosts are accepted. |
| `payment.jpmorgan.truststore` / `.keystore` (+ `…Password`) | `POS_JPM_TRUSTSTORE` … | PKCS#12 TLS files for the in-store terminal. |

The store logs one line at startup, for example
`Card terminal: simulator (store default, timeout 90s)`.

## The adapter layer (store side)

`server/.../payments/terminal/PaymentTerminal.kt` is the contract every
terminal implements:

- **Pair or connect:** `connect()`.
- **Reader status:** `status()`. It never throws; an unreachable reader
  reports `OFFLINE`.
- **Take a payment:**
  - `startPayment(amount, currency, tipMode, reference)`
  - `result(ref)` reports the outcome: `PENDING`, `APPROVED`, `DECLINED`,
    `CANCELLED`, `TIMEOUT` or `ERROR`. It also carries the card details:
    brand, last 4 digits, entry mode (tap, insert or swipe), auth code, and
    the EMV fields AID, TVR, TSI, application label and CVM.
  - `capture(ref)` takes the money once the store confirms the check still
    owes it.
  - `cancel(ref)` stops a payment in progress, or voids an approval the store
    no longer wants.
- **Refund:** `refund(ref, amount?)`, full or partial.

Adapters:

| adapter | file |
|---|---|
| Stripe | `payments/StripeTerminalAdapter.kt`. `StripeService` now makes every money call through it. The HTTP calls, idempotency keys and behaviour are unchanged; the only addition is `expand[]=latest_charge` on capture, so the receipt can show the card. |
| Simulator | `payments/simulator/SimulatorTerminal.kt` |
| J.P. Morgan online | `payments/jpm/JpmOnlineAdapter.kt` + `JpmOnlineHttp.kt` |
| J.P. Morgan in-store | `payments/jpm/JpmInStoreAdapter.kt` + `MiniWebSocket.kt` |

`payments/TerminalPaymentService.kt` handles the check side for every
terminal except Stripe:

- It locks the amount due and starts the sale on the reader.
- On each poll it checks the reader. On approval it re-checks what is still
  due, captures, and records a **`TERMINAL`** tender.
- If the check was paid another way meanwhile, the approval is voided instead
  of charged twice.
- Refunds happen on the terminal first. Only what the terminal refunded is
  recorded.

Stripe keeps its own `StripeService` and `/stripe/...` routes, because the
tablet SDK collects the card there.

HTTP routes, all behind the normal session gate:

```
GET  /payments/terminal                  kind, available?, reader state (every store)
POST /payments/terminal/pair             {host, code}    (manager)
POST /payments/terminal/unpair                           (manager)
POST /checks/{id}/terminal/payments      {amountCents?, groupId?, tipMode}
GET  /terminal/payments/{id}             poll; records the tender on approval
POST /terminal/payments/{id}/cancel
POST /checks/{id}/refund                 tenderType=TERMINAL
```

Receipts print a card slip under a card tender (`TERMINAL`, and `STRIPE` when
Stripe returns the charge):

```
Card                                 23.28
Tip                                   4.00
Total charged                        27.28
VISA **** 4242                 Contactless
Auth code                           367024
AID                         A0000000031010
TVR 0000000000                    TSI 0000
VISA CREDIT                         NO CVM
J.P. Morgan sandbox (mock)          ← J.P. Morgan only
Ref       0b6f7a94-4d07-4715-a425-67b721a5de2e
                 APPROVED
```

Only the brand and last 4 digits are ever stored or synced, never a card
number. The AID, TVR and TSI values from the simulator are placeholders shaped
like a real EMV slip.

## The built-in simulator

It is a pretend countertop card reader. It needs no internet and uses only
test cards. There are three ways to play it:

1. **The Mac as the terminal** (the demo setup). The Mac shows a
   customer-facing reader screen full screen. The tablet POS sends it payments
   over the store Wi-Fi by IP, the same way it would reach a real countertop
   terminal.
   ```
   scripts/demo-terminal.sh --push-tablet            # Copper Lantern app
   scripts/demo-terminal.sh --push-tablet --app sagepoppy
   ```
   The script:
   - builds the store jar once and starts the terminal on port 8090;
   - opens `http://localhost:8090/?kiosk` (press **Full screen**);
   - with `--push-tablet`, writes `payment.terminal=simulator` and
     `payment.terminal.host=<Mac IP>:8090` into the tablet's
     `store.properties` and restarts the POS;
   - prints what to set by hand if you don't use `--push-tablet`.

   On the POS, go to **Settings → Card terminal → Pair** and type the 6-digit
   code shown on the Mac.
2. **Built in, on any browser on the LAN.** With no `payment.terminal.host`
   set, the reader runs inside the store and its page is `http://<store>:<port>/terminal`.
3. **Tablet only.** In the same built-in mode, the POS payment screen offers
   "Play the card reader here": a sheet on the tablet that plays the reader,
   for when there's no second screen.

**On the reader:**
- It shows the amount (with the tip, if tipping on the reader), "Tap, insert
  or swipe", and a countdown.
- The "wallet" under the reader screen is the demo control:
  - Pick a test card: Visa 4242, Mastercard 4444, Amex 8431, Interac 0002 or
    Discover 1117.
  - Pick an outcome: approve, decline for insufficient funds, decline for do
    not honour, time out, or customer cancels.
  - Then press **Tap**, **Insert** (which asks for a PIN) or **Swipe**.
- Delays are realistic: a tap takes about 1.4 s, a swipe about 1.9 s and a
  chip about 2.6 s, plus some random jitter. The result stays on screen for
  6 seconds.
- If the payment runs in tip mode, the reader offers 15%, 18%, 20% or no tip
  before the card.

**Refunds** work, full or partial, against the original payment. Each refund
has its own id, and a retried refund can't refund twice.

**Offline:** if the Mac is unplugged, `/payments/terminal` says
`offline`, and a payment already on the reader stays PENDING with
`readerOffline` set until the Mac comes back. A new payment gets a clean
`503 terminal_unavailable`. Cash and the hand-keyed card still work.

The reader's own page and buttons (`/terminal`, `/terminal/ui/*`) are public on
the LAN, like a physical reader's keypad. They move no real money. The POS side
(starting, recording and refunding payments) stays behind the session gate.

## J.P. Morgan

Everything below comes from J.P. Morgan's public docs (developer.payments.jpmorgan.com;
the portal publishes a machine-readable index at `/llms.txt`). I also probed the
owner's sandbox project with read-only and sandbox-test calls. Nothing here
guesses at undocumented endpoints.

### What their in-store integration is

- **Product:** the *Payment Terminal Application*, an Android app that J.P.
  Morgan installs on its own Ingenico AXIUM terminals (RX7000, RX5000, DX4000,
  DX8000).
- **It is not a cloud API.** The terminal runs a small server. The POS
  connects to it **directly over the store LAN** with a persistent **secure
  WebSocket** (`wss://<terminal-ip>:<port>`) and sends plain JSON. The
  terminal, not the POS, talks to J.P. Morgan's payment host.
- **Two setups:**
  - *Semi-integrated:* the POS on another device, which is ours.
  - *On-device:* the POS app runs on the terminal itself, at `wss://localhost`.
- **Port:** the simulator how-to says "use the default port 8442", but its
  code sample uses 8443. We default to 8442, and it is configurable.
- **Security:** TLS, with mutual TLS (a client certificate) supported. The
  certificates are managed with `GenerateCsr`, `ImportCertificate` and
  `InstallTrustCertificate`.
- **Messages:**
  - Every message has an `operation` field. `Transaction` covers SALE,
    AUTHORIZATION, COMPLETION, REFUND, VOID and SETTLEMENT; the others are
    `Cancel`, `LastTransaction`, `GetInformation`, `Status`, `Display` and so
    on.
  - A transaction answers with several `Status` notifications, then one
    final message.
  - Only one operation can run at a time; result `5` means busy.
  - After a dropped connection, the POS reconnects and sends
    `LastTransaction`.
- **Result codes:**
  - `result` `0` only means the operation completed. Whether the card was
    approved is in `approval`.
  - 10 = timeout, 11 = cancelled by the user, 12 = cancelled by the POS,
    13 = cancel not available, 17 / 19 = declined by the card / by the host,
    83 = missing credentials.
- **Response fields:** `authCode`, `account` (masked), `cardBrand`,
  `entryMode`, `AID`, `TVR`, `TSI`, `cvm`, `transactionID`, and ready-made
  receipt text.
- **Tip:** set with `parameters:[{"key":"TIP","value":"1"}]`.
- **Credentials:** there are no API keys in the messages. The merchant ID and
  terminal ID live on the terminal.

**The "In-Store POS Simulator"** is an Android app, the "Payment Application POS
Semi-Integrated Simulator":
- It plays the **POS** side, not the terminal. It lets you edit and send the
  JSON messages.
- It still needs a real terminal running the Payment Terminal Application.
- It isn't downloadable: *"Contact the Integration Specialist assigned to your
  project to obtain the POS Simulator."*

**What we built: `JpmInStoreAdapter`**, selected with `payment.jpmorgan.mode=instore`.
- It implements the documented protocol:
  - SALE with the TIP parameter, Status handling, Cancel (and result 13),
    VOID with `originalAuthCode`, REFUND, GetInformation, and LastTransaction
    recovery.
  - It maps the result codes and card fields listed above.
- It uses a dependency-free WebSocket client and requires a truststore; there
  is no "trust all" mode.
- It is tested against a fake terminal that speaks the documented JSON, but
  **it has not yet been run against a real terminal**. The TODOs in the code
  mark the parts that need access:
  - the TLS material;
  - refunds that are tokenized versus card-present.

### What the owner's sandbox project can reach today (probed 2026-09-26)

- **The OAuth token works.** The client-credentials request to
  `https://id.payments.jpmorgan.com/am/oauth2/alpha/access_token` with scope
  `jpm:payments:sandbox` returns a Bearer JWT valid for 3599 s. Its claims
  have **no merchant id**.
- **Only J.P. Morgan's mock host answers:**
  `https://api-mock.payments.jpmorgan.com`, for Online Payments, Checkout
  and Global Payments. The mock **doesn't check the token**. It returns
  canned, stateless answers: the same transaction id every time, and
  approval for everything, including the amounts that should trigger a
  decline.
- **The real client-testing hosts refuse this token:**
  - `api-ms-test.payments.jpmorgan.com/api/v2` (Online Payments) answers
    401 `invalid_token, Invalid issuer`.
  - The Checkout CAT host answers 403.
  - The Global Payments test hosts need mTLS.
  - Those hosts use different credentials: a certificate-based client, where
    you sign a JWT, that J.P. Morgan issues at onboarding.
- **There is no card-present cloud API** to reach. In-store is only the
  terminal app described above.

### The demo path: `payment.terminal=jpmorgan` (online mode)

- The simulated reader (on the Mac, or the tablet sheet) is the card reader
  people touch.
- When a card is presented, the store sends a **real** J.P. Morgan Online
  Payments call:
  - `POST /payments` with `captureMethod: MANUAL`, and a documented sandbox
    test card standing in for the simulated one: Visa 4112344112344113,
    Mastercard 5112345112345114, Amex 371144371144376 or Discover
    6011016011016011.
  - Then `POST /payments/{id}/captures` once the store has re-checked what's
    due.
  - Voids are `PATCH /payments/{id}` `{isVoid:true}`.
  - Refunds are `POST /refunds` referencing the payment.
- **Headers:** `Authorization: Bearer`, `merchant-id`, and a unique
  `request-id` derived from our idempotency key, so a retry reuses it.
- **Decline buttons:** J.P. Morgan triggers declines by amount on specific
  cards (52100 gives INSUFFICIENT_FUNDS on Mastercard …5114; 53000 gives
  DO_NOT_HONOR on Visa …4113), so we send that amount instead. The mock host
  can't decline, so on the mock a decline button declines **on the reader**,
  and the message says so; we don't pretend J.P. Morgan declined it.
- **The J.P. Morgan transaction id** shows on the tablet's result screen and on
  the receipt, and refunds use it.
- **If J.P. Morgan is unreachable** (or not configured), the reader shows a
  decline with `processor_unavailable` and nothing is recorded. Cash and the
  counter card still work.
- **One real call** (run locally, not in CI; `JPM_LIVE_SANDBOX=1
  JPM_ENV_FILE=../.env ./gradlew test --tests '*JpmOnlineHttpTest*'`) went to
  the mock host:

  | call | HTTP | result | id |
  |---|---|---|---|
  | authorize | 200 | AUTHORIZED | `0b6f7a94-4d07-4715-a425-67b721a5de2e` |
  | capture | 200 | CLOSED | `0b6f7a94-4d07-4715-a425-67b721a5de2e` |
  | refund | 200 | (canned) | `397cc44c-fe58-4ab5-b880-ce5915ea6c59` |

  The same ids come back on every call, because the mock returns canned
  answers.

**Honest framing:** Online Payments is J.P. Morgan's **card-not-present** (online)
API. Putting a simulated reader in front of it is a demo of the flow: a real
processor authorizes, captures and refunds. It is not a card-present
integration. A real card-present store would use their in-store terminal
program, the Payment Terminal Application above. Sandbox only: production
hosts are refused in code.

### What the owner needs to get from J.P. Morgan

1. **Real Online Payments sandbox access** (so declines and amounts behave for
   real). Ask for:
   - "Commerce client-testing (CAT) credentials for Online Payments". This is
     a certificate-based client: you send a CSR, and they send back a client
     id, the signed certificate, a resource id and the token URL.
   - A **test merchant id** (12 digits).

   Then set `JPM_BASE_URL=https://api-ms-test.payments.jpmorgan.com/api/v2`
   and `JPM_MERCHANT_ID`. The certificate-signed JWT token flow would need a
   small addition to `JpmOnlineHttp` (TODO in the code). The current
   client-credentials token is rejected by that host's issuer check.
2. **For real card-present (in-store) payments:**
   - In-Store Payments enabled for the project, and an assigned
     **Integration Specialist**. That person is the only source for:
     - a pre-configured **test AXIUM terminal** with the Payment Terminal
       Application;
     - the **POS Simulator** app.
   - **EMV test cards**, ordered separately; the docs link a B2 test-card
     order page.
   - The terminal's **CA certificate**, for our truststore, and, if they
     require mutual TLS, a client certificate. Put them in
     `payment.jpmorgan.truststore` / `.keystore`.
   - The terminal's LAN IP. Set `payment.terminal=jpmorgan`,
     `payment.jpmorgan.mode=instore`, and
     `payment.terminal.host=<ip>:8442` (or `:8443`).
   - The integration test script, to complete before production.
3. Nothing is needed from J.P. Morgan for the **built-in simulator** demo.

## Stripe (unchanged)

The flow is the same as before: `/stripe/...` routes, a manual-capture
PaymentIntent, and the tablet's Terminal SDK collecting the card on the
simulated reader. Refunds still go through `POST /checks/{id}/refund
tenderType=STRIPE`.

## Other terminals

PAX countertop terminals (POSLink semi-integration) are a possible future
adapter behind the same contract.
