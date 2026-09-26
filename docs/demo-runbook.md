# Local demo runbook

Three fictional stores of one owner (tenant `copperlantern`): two Montréal
pubs in `America/New_York`, CAD, English/French, and a Los Angeles bottle
shop in USD, English/Spanish (below):

| store | venue id | runs on | cloud key (`.env.local`) |
| --- | --- | --- | --- |
| Copper Lantern — Vieux-Port | `vieux-port` | the Android tablet | `STORE_API_KEY` |
| Copper Lantern — Plateau | `plateau` | this Mac (`DesktopMain.kt`, `POS_VENUE=plateau`) | `STORE_API_KEY_PLATEAU` |
| Sage & Poppy Bottle Shop | `sage-poppy` | this Mac, `:8082` (`POS_VENUE=sage-poppy`) | `STORE_API_KEY_SAGE_POPPY` |

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

## Sage & Poppy Bottle Shop (the US retail store)

The owner's third store, and the first outside Canada: a fictional
neighbourhood liquor store in Los Angeles (`POS_VENUE=sage-poppy`), its own
brand (sage and poppy, not copper), **USD**, **English + Spanish**,
`America/Los_Angeles`, and a **retail counter** instead of tables.

| | |
| --- | --- |
| runs on | this Mac, a second desktop store: `:8082` (8081 is the cloud API), DB under `.demo/sage-poppy/` |
| cloud key | `STORE_API_KEY_SAGE_POPPY` in `.env.local` (demo-up.sh mints it) |
| PINs | manager `1234`, cashier `9999`, Spanish-speaking cashier `5555` (receipts in Spanish) |
| shelf | ~50 fictional products, each with a made-up UPC-A (number system 4 = in-store codes, valid check digits) |
| money | 9.5% sales tax on taxable goods (snacks and ice exempt), CRV bottle deposit per container × pack on its own untaxed line; cash rounds to the nickel (see Cash rounding) |
| age | ID check at 21 before age-restricted items can be paid for (`POS_LEGAL_AGE`; tablet: `legal.age` in store.properties) |
| payments | cash, and card on the counter's own external terminal. **No Stripe**: the Stripe integration is Canada-only (CAD) for now |

`scripts/demo-up.sh` starts it (after Plateau) and seeds five counter sales
once (`scripts/demo-seed-retail.py`).

### The counter screen on this Mac (no Xcode needed)

The POS client also builds for the web. Point it at the store and open it in
Chrome:

```sh
cd client && flutter run -d chrome --dart-define=SERVER_URL=http://localhost:8082
```

(or `flutter build web --dart-define=SERVER_URL=http://localhost:8082` and
serve `client/build/web`). Sign in with a PIN, then:

- **Scan**: a USB/Bluetooth HID scanner types the code and Enter. Without a
  scanner, type a barcode fast and press Enter, or type it in the search box
  and press Enter (e.g. `487230001029` = Golden Hour Lager 6-pack). Scanning
  the same product again bumps its quantity.
- **ID check**: a basket with alcohol shows "ID check needed (21+)"; Pay opens
  the check. A 2D scanner reading a licence's barcode types its AAMVA text;
  otherwise pick the date of birth and tick "I have seen the customer's ID".
  Under 21 or expired: remove the restricted items and sell the rest. Only the
  outcome is stored (method, pass/fail, age in years, cashier, time).
- **Unknown barcode**: "Add product (manager)" — name (suggested from Open Food
  Facts when online), price, category, 21+, taxable, CRV size × units. It is
  rung up straight away.
- **Language**: the EN/ES pill in the header; a cashier's own language
  (Cajera Demo = Spanish) is used after sign-in and on their receipts.

On a tablet, `store.venue=sage-poppy` in
`/sdcard/Android/data/dev.dwhipstock.pos_client/files/store.properties` makes it
this store (one tablet per store).

### The portal with two currencies

"All stores" never adds CAD and USD together: each store is shown exactly in
its own currency (US$ / CA$), the headline figures have exact per-currency
rows, and the combined figure is an **approximate** CAD total at the fixed
rate `FX_USD_CAD` (default 1.37 locally), with the rate shown. Pick the store
to see it on its own, all in US$. Exports carry a currency column.

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

## Cash rounding (nickel)

Canada and the US no longer make pennies, so every store rounds the final
amount of a **cash** payment to the nearest 5¢ on its last cent digit: 1–2
down to 0, 3–4 up to 5, 6–7 down to 5, 8–9 up to 10 (0 and 5 stay). Prices,
CRV and each tax are still worked out to the cent first; only the cash that
settles the balance rounds (on a split bill, each group's cash on its own;
after a card, only the cash remainder). Card, Stripe and transfers are always
the exact amount. Cash refunds round the same way. The tender screens, the
bill (paper, the staff app and the guest's phone) and the receipt show a
**Rounding / Arrondi / Redondeo** line and the rounded cash total; the X/Z
drawer counts the rounded cash; the portal reports the net rounding per store
and per currency, beside the exact revenue and tax.

A config switch (no UI), default `nickel` for every store:
`cash.rounding=nickel|off`. **Tablet**: add the line to the same
`store.properties` as `print.receipts` and restart the POS. **Desktop /
docker store**: `POS_CASH_ROUNDING=nickel|off`, or `cash.rounding` in the
`POS_CONFIG_FILE` properties file (the env var wins). The store logs
`Cash rounding: <mode> (<source>)` at startup; a bad value means `nickel`
(logged) and never fails startup.

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
Stripe account. The store sells in CAD, so the account must be a **Canadian
(CAD) account**: any other default currency disables Stripe, logged as
`Stripe: DISABLED — the Stripe account's currency is …`, and the Pay screen
shows "Stripe is off: the Stripe account is not in CAD". Unless
`STRIPE_LOCATION_ID` is set, the store reuses a Terminal Location tagged with
its store id, or creates one (idempotently) with a fictional Montréal address.
Its id is kept locally in `sync_state`.

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
