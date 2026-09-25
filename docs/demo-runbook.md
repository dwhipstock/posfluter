# Local demo runbook

Two fictional Montréal stores of one owner (tenant `copperlantern`), both in
`America/New_York`, CAD, English/French:

| store | venue id | runs on | cloud key (`.env.local`) |
| --- | --- | --- | --- |
| Copper Lantern — Vieux-Port | `vieux-port` | the Android tablet | `STORE_API_KEY` |
| Copper Lantern — Plateau | `plateau` | this Mac (`DesktopMain.kt`, `POS_VENUE=plateau`) | `STORE_API_KEY_PLATEAU` |

Plateau has the shared pub menu plus a Sushi Bar zone (tables S-1…S-11), a
"Sushi & Sake" category (maki, nigiri, sake) and Plateau Specials.

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

## Tear-down

```sh
scripts/demo-down.sh          # stop Plateau + the cloud containers, keep data
scripts/demo-down.sh --reset  # also wipe the cloud db and .demo/plateau
```

After a `--reset`, re-run `scripts/tablet-cloud-config.sh` so the tablet
re-sends its history to the fresh cloud db.
