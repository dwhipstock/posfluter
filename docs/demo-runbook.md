# Local demo runbook

Two fictional Montréal stores of one owner (tenant `copperlantern`), both in
`America/New_York`, CAD, English/French:

| store | venue id | runs on | cloud key (`.env.local`) |
| --- | --- | --- | --- |
| Copper Lantern — Vieux-Port | `vieux-port` | the Android tablet | `STORE_API_KEY` |
| Copper Lantern — Plateau | `plateau` | this Mac (`DesktopMain.kt`, `POS_VENUE=plateau`) | `STORE_API_KEY_PLATEAU` |

Both stores share the pub menu in seven categories (Beer & Cider, Wine,
Cocktails, Starters, Burgers & Sandwiches, Mains & Salads, Desserts). Plateau
has the shared pub menu plus a Sushi Bar zone (tables S-1…S-11), a
"Sushi & Sake" category (maki, nigiri, sake) and Plateau Specials, listed
after the seven.

## Bring-up (one command)

Needs Docker (Docker Desktop or colima), Java 17+, python3 and openssl.

```sh
scripts/demo-up.sh            # or --no-seed to skip the Plateau demo sales
```

It (idempotently):

1. creates `.env.local` from `.env.local.example` if missing and mints both store
   keys (review `DB_PASSWORD` / `ADMIN_EMAIL` / `ADMIN_PASSWORD` before a real demo);
2. starts Postgres + cloud API (`:8081`) + owner portal (`:3000`) in Docker. The API
   boot seed provisions tenant `copperlantern` with stores `vieux-port` and
   `plateau` and one API key each (an older local db's `main` store is renamed to
   `vieux-port` by cloud migration 014, history and all);
3. builds the store server and starts **Plateau** in the background on `:8080`
   with its own database under `.demo/plateau/` (log: `.demo/plateau/store.log`),
   syncing to `http://localhost:8081` with `STORE_API_KEY_PLATEAU`;
4. seeds a few Plateau demo sales once, and prints the URLs.

Open the portal at `http://<Mac LAN IP>:3000`. The header's store picker offers
**All stores** (default, combined, with a per-store breakdown on sales and
reports) or one store; the choice is kept in the URL (`?store=plateau`). Menu
and staff are read-only in the portal — each store edits them on its own POS.

Portal sign-in enrolls MFA (TOTP) on first login by default. For a local demo
only, set `TOTP_REQUIRED=false` in `.env.local` (passed to the API by
`docker-compose.local.yml`) to skip it; never set it on a hosted deploy.

Plateau's POS: `cd client && flutter run -d macos` (it talks to `localhost:8080`),
or the staff app at `http://<Mac LAN IP>:8080/staff-app`. PINs: manager `1234`,
server `9999`.

The old Mac `store` container is no longer started (compose profile
`mac-store`). Do not start it next to the tablet: the tablet was imported from
that database, so both would push as the same store.

## Vieux-Port tablet → this Mac

The tablet reads its cloud settings from app-private
`store-cloud.properties` (`cloud.url`, `cloud.apiKey`, `portal.url`,
`store.installId`). To point it at this Mac, stage new settings over adb:

1. Tablet and Mac on the same Wi-Fi; USB debugging on; `adb devices` lists it.
   The POS app (this branch's APK) is installed and has been opened once.
2. `scripts/demo-up.sh` is running (check `http://<Mac LAN IP>:8081/health`
   answers from another device; allow incoming connections for Docker in the
   macOS firewall if it does not).
3. Run `scripts/tablet-cloud-config.sh`. It writes
   `cloud.url=http://<Mac LAN IP>:8081`, `cloud.apiKey=<STORE_API_KEY>` and
   `portal.url=http://<Mac LAN IP>:3000` to
   `/sdcard/Android/data/dev.dwhipstock.pos_client/files/migration/store-cloud.properties`
   and restarts the app. (`--print` shows the values, `--no-restart` stages only.)
4. At startup the POS validates and applies them (`adb logcat -s TabletStore`
   shows `Cloud sync re-pointed to …`): it keeps its store identity, keeps the
   previous settings as `store-cloud.properties.prev`, and re-sends its whole
   outbox so the local portal shows Vieux-Port's full history within a minute.
   A store that has never synced has no identity to keep: it is refused
   (`REFUSED to re-point cloud sync` in the log; it starts normally on its
   current settings) unless you pass `--install-id <id>` explicitly.

Plain `http://` is accepted only to a private LAN address (10/8, 172.16/12,
192.168/16); anything else must be `https://`. The Mac's DHCP address can
change — re-run step 3 if it does. With the Mac off or unreachable the tablet
keeps starting, signing in and selling; only its sync pauses. To send the
tablet back to another cloud, stage that cloud's URL and Vieux-Port key the
same way.

## Receipt printing: paper or digital

A config-file switch (no UI) decides whether sale receipts go to the thermal
printer: `print.receipts=paper|digital`, default `paper`.

- `paper`: closed-check receipts and provisional bills print as usual.
- `digital`: they are only saved (the `receipts/` and `bills/` spool and the
  `receipt.printed` event, exactly as today); nothing goes to paper. Refund
  slips are never sent to paper in either mode.
- Manual prints always use paper: test page, table QR slips (and other
  manual slips).

`GET /printer/status` reports `receiptMode`, and the store logs
`Receipt printing: <mode> (<source>)` at startup. A missing file, missing key
or bad value means `paper` (logged); startup never fails on it. The setting is
local only and never depends on the network.

**Tablet** (release build, so via adb): the POS reads
`/sdcard/Android/data/dev.dwhipstock.pos_client/files/store.properties` at
store startup.

```sh
scripts/tablet-print-mode.sh digital   # write print.receipts=digital + restart the app
scripts/tablet-print-mode.sh paper     # back to paper
adb logcat -s TabletStore | grep 'Receipt printing'
```

**Desktop / docker store**: `POS_PRINT_RECEIPTS=paper|digital` (e.g.
`POS_PRINT_RECEIPTS=digital scripts/demo-up.sh` for Plateau, or in
`.env.edge` for `docker-compose.edge.yml`), or `POS_CONFIG_FILE=<path>` to a
properties file in the same format. The env var wins over the file. Restart
the store to apply.

## Stripe (test mode)

An optional extra tender, **Card (Stripe)**, takes a card through Stripe
Terminal's **simulated reader** (no hardware), in Stripe **test mode only**.
Cash, Card (the external terminal) and Transfer are unchanged. Without a key the option is
not shown; with a key but no internet (or a refused key, or a denied
permission) it is greyed out with a hint. It never blocks startup, sign-in or
a sale: nothing contacts Stripe until someone picks it, and a failure records
nothing, so the bill can always be paid another way.

### Setup

1. Create (or use) a Stripe account and switch the dashboard to **Test mode**.
   Developers → API keys → copy the **secret test key** (`sk_test_…`).
2. Put it in the gitignored `.env` at the repo root (never commit it):
   ```sh
   STRIPE_KEY=sk_test_...
   # optional; otherwise the store creates/reuses its own Terminal Location
   # STRIPE_LOCATION_ID=tml_...
   ```
3. **Tablet**: push it and restart the POS:
   ```sh
   scripts/tablet-stripe-config.sh        # merges stripe.secretKey into store.properties
   scripts/tablet-stripe-config.sh --off  # remove it again
   adb logcat -s TabletStore | grep 'Stripe:'
   ```
   The key goes into `/sdcard/Android/data/dev.dwhipstock.pos_client/files/store.properties`
   next to `print.receipts` (other lines are kept).
   **Desktop / docker store**: `STRIPE_KEY` (and optional `STRIPE_LOCATION_ID`)
   in the environment; `scripts/demo-up.sh` reads it from `.env` for Plateau,
   and both compose files pass it through. `POS_CONFIG_FILE` with
   `stripe.secretKey=` works too.
4. The first time Card (Stripe) is used the tablet asks for **location and
   Bluetooth** permission (the Terminal SDK requires them even for the
   simulated reader). Denying just disables Card (Stripe) until the app restarts.

Rules the store enforces: only `sk_test_…` keys are accepted — a live,
restricted or malformed key is refused, logged as
`Stripe: DISABLED — … not an sk_test_ key`, and Stripe stays off. The key is
never logged, never stored in the database and never sent to the cloud; tender
and refund events carry only the tender type `STRIPE` and the Stripe
PaymentIntent / refund ids.

On startup (in the background, never gating anything) the store reads the
Stripe account and charges in the **account's default currency**. A Canadian
account charges CAD, matching the store. If an account in another currency is
used, the store logs a warning and still charges, for the same number of cents
with no conversion (test mode only); the payment screen then shows the currency
code. Unless `STRIPE_LOCATION_ID` is set, the store reuses a Terminal Location
tagged with its store id, or creates one (idempotently) with a fictional
Montréal address for a CA account. Its id is kept locally in `sync_state`.

### Taking a payment

Pay → **Card (Stripe)** → (optional amount on the keypad, blank = all due) →
pick a **simulated card** → **Charge card (Stripe)**. The screen shows
connecting → tap card (simulated) → processing → approved / declined. On
approval the store captures the payment and records a `STRIPE` tender (the
receipt says "Card (Stripe)"); the bill closes as usual. **Cancel** returns
to the tender screen and records nothing (the PaymentIntent is canceled at
Stripe). Declines and errors show a plain message plus "Nothing was charged".

How it works: `capture_method=manual`. The reader only *authorizes*; the
store re-checks that the bill still owes the amount, then captures and records
the tender. If the bill was paid some other way in the meantime, the
authorization is released instead of charged. Every Stripe write carries an
idempotency key, so a retry after a timeout can't charge, capture or refund
twice. If Stripe times out, nothing is recorded, and the bill stays payable
by cash or anything else.

Test cards and amounts (Stripe test mode; details on Stripe's Terminal
testing page, docs.stripe.com/terminal/references/testing):

| Simulated card on the Pay screen | Card number used     | Result                        |
|----------------------------------|----------------------|-------------------------------|
| Approved                         | reader default (Visa)| approved                      |
| Declined                         | 4000 0000 0000 0002  | declined (`card_declined`)    |
| Insufficient funds               | 4000 0000 0000 9995  | declined (`insufficient_funds`)|

Stripe's Terminal test amounts also decline by the cents of the amount: an
amount ending in **.01** (call issuer), **.05** (generic decline), **.55**
(incorrect PIN), **.65** (withdrawal count limit) or **.75** (PIN tries
exceeded); check Stripe's page for which apply to the simulated reader. The
POS keypad takes whole dollars, so make the bill total end in those cents
(e.g. add an open item at $10.05). Stripe's minimum charge is 0.50.

### Refunds

Refunds use the existing refund screen and rules (manager grant, reason,
by amount or by line, cumulative cap). **Card (Stripe)** appears as a refund
method only for a bill that was paid that way. The store refunds at Stripe
first (full or partial, against that PaymentIntent, idempotency key per
amount) and records the refund only after Stripe accepted it. **If Stripe
can't be reached, the refund is refused** ("Can't reach Stripe"), and
nothing is recorded: the POS never shows a card refund that didn't happen.
A cash refund of the same bill still works if the customer agrees.

### Real money later (not done here)

- **Activate** the Stripe account (business details, bank account) and swap
  in a live key. The store refuses live keys today, so that is a
  deliberate code change, not only config.
- A **physical reader** (e.g. a Bluetooth reader like the Stripe M2 or
  WisePad 3), or **Tap to Pay on Android** on a supported NFC device. Either
  replaces the simulated discovery in `client/lib/payments/card_reader.dart`.
- **Interac** (Canadian debit): `interac_present` payments are captured
  automatically, and their refunds must be made in person on the reader
  (card present). Neither is handled yet.
- Live-mode checks: receipts with the card brand / last 4 and auth code,
  reconciliation with Stripe payouts, and dispute handling.

## Tear-down

```sh
scripts/demo-down.sh          # stop Plateau + the cloud containers, keep data
scripts/demo-down.sh --reset  # also wipe the cloud db and .demo/plateau
```

After a `--reset`, re-run `scripts/tablet-cloud-config.sh` so the tablet
re-sends its history to the fresh cloud db.
