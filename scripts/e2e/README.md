# Acceptance E2E (the gates)

The scripted end-to-end check for validating an isolated deployment after a
deploy, restore, or infrastructure change.

Stdlib-only Python (no pip). Drives the **cloud portal API** + **two live venue
stores** over HTTPS exactly as a terminal + owner would.

## Why two venues

Several gates are about **isolation** (a device token paired to venue A must be
rejected by venue B — gates B1/B2) and **group rollup** (both venues in one
report — C3/C4). Those are structural and need a real second venue. So the suite
targets a pair. By default that pair is the **qa staging tenant** (`qa1` + `qa2`)
— never demo or production venues, so test transactions cannot reach a
customer-facing venue.

## The gates

| Group | Gates | Asserts |
|---|---|---|
| A — provisioning/pairing | A1–A7 | both venues in `/v1/venues`, stores online, HTTPS + `pairingRequired`, a pairing code pairs exactly once (re-use → 404) |
| B — isolation | B1–B3 | venue A's device token → 401 on venue B (`/staff`, `/login`); no device → 401 |
| C — money → reporting | C1–C5 | a cash sale on each venue closes; `/v1/reports/by-venue` sees both under the right venue; per-venue summary is correct |
| D — store-owned staff + menu | D1–D6 | the portal cannot create staff or edit the menu (404/405); each store's manager and menu item are created **on the store** (an empty store's bootstrap manager, PIN 1234, creates the e2e manager, PIN 4711), sign in and sell there, and then show up in the portal's read-only per-store view |
| F — settings isolation | F1 | a fresh venue does **not** inherit another venue's payment settings (blank card processor + zero corkage) |

## Prerequisites (one-time)

Provision the two staging venues under the `qa` tenant with a portal admin
(`staging` is a reserved slug — hence `qa1`/`qa2`):

```sh
scripts/provision-venue.sh qa1 --name "QA One" --tenant qa --tenant-name "QA Staging" \
    --admin-email qa@example.com --admin-password '<pw>' --image-tag <sha> --xmx 160m
scripts/provision-venue.sh qa2 --name "QA Two" --tenant qa \
    --admin-email qa@example.com --admin-password '<pw>' --image-tag <sha> --xmx 160m
```

`--image-tag <sha>` pulls the immutable store image from ECR (see
`scripts/aws/ci-ecr-setup.sh`). `--xmx 160m` keeps the idle staging JVMs small.

## Run

```sh
E2E_PORTAL_ADMIN_EMAIL=qa@example.com \
E2E_PORTAL_ADMIN_PASSWORD='<pw>' \
scripts/e2e/run.sh
```

First run enrolls the admin's TOTP and caches the secret in
`scripts/e2e/.qa-totp-secret` (gitignored); later runs reuse it. Exit code is
non-zero if any gate fails; the failing gate names are printed.

## Aim it elsewhere (override any of these)

```
E2E_BASE_DOMAIN   venue subdomain base    (default example.com)
E2E_PORTAL_BASE   cloud API / portal URL  (default https://copperlantern.example.com)
E2E_VENUE_A       first venue slug        (default qa1)
E2E_VENUE_B       second venue slug       (default qa2)
```
