# Hosted: split Sage & Poppy into its own portal

**Today:** one manager stack on the box (compose project `copper-lantern-manager`,
`docker-compose.manager.yml`) serves `copperlantern-manager.lostmindllc.com`,
with three stores in tenant `copperlantern`: `vieux-port`, `plateau` and — by
mistake — `sage-poppy`.

**After:** one edge proxy on the box and two client portals behind it:

| Client | Hostname | Stores | Database |
|---|---|---|---|
| Copper Lantern (`copperlantern`) | `copperlantern-manager.lostmindllc.com` | vieux-port, plateau | the existing one, unchanged |
| Sage & Poppy (`sagepoppy`) | `sagepoppy-manager.lostmindllc.com` | sage-poppy | new, filled by the store re-sending its history |

Nothing here changes a store's selling. A store whose portal is briefly down
just queues its sales and sends them later.

Order matters: Copper Lantern moves first **without changing its images**
(only the plumbing), then the proxy takes over ports 80/443, then Sage & Poppy
is created, the Mac store is pointed at it, and **only after checking** is
`sage-poppy` removed from Copper Lantern's database.

## 0. Before you start

* The PRs are merged and `build.yml` has pushed images for the merge commit:
  `NEW=<that git sha>` below.
* DNS: an A record `sagepoppy-manager.lostmindllc.com` → the box's IP. Check:
  `dig +short sagepoppy-manager.lostmindllc.com`.
* Your IP is allowed by the box's security group (SSH).

On the Mac:

```sh
export HOST=ubuntu@<box ip>          # the manager box
export KEY=~/.ssh/<key>.pem
export REGISTRY=<account>.dkr.ecr.us-east-1.amazonaws.com
aws ecr get-login-password --region us-east-1 | ssh -i $KEY $HOST docker login --username AWS --password-stdin $REGISTRY
ssh -i $KEY $HOST
```

On the box (every later command runs here unless it says "on the Mac"):

```sh
export APP=/opt/posflutter-manager/app
export REGISTRY=<account>.dkr.ecr.us-east-1.amazonaws.com NEW=<git sha>
cd $APP/cloud/infra
ls -l .env                   # the env file the current stack runs with
grep -E '^(IMAGE_TAG|WEB_IMAGE_TAG|TOTP_REQUIRED|ACME_EMAIL|DOMAIN|LEGACY_DOMAIN)=' .env
docker ps --format '{{.Names}}  {{.Image}}  {{.Status}}'
```

## 1. Back up

```sh
mkdir -p ~/backups
TS=$(date +%Y%m%d-%H%M)
docker exec copper-lantern-manager-db-1 pg_dump -U pos pos_cloud | gzip > ~/backups/pos_cloud-pre-split-$TS.sql.gz
ls -l ~/backups/pos_cloud-pre-split-$TS.sql.gz           # not tiny
cp -p .env ~/backups/manager.env-pre-split-$TS
docker volume ls | grep copper-lantern-manager           # note the pgdata and caddy_data names
```

Also on the Mac, back up the Sage & Poppy store (it is backed up again by the
script in step 5, this is belt and braces):

```sh
cd ~/projects/posflutter
sqlite3 .demo/sage-poppy-hosted/pos.db ".backup .demo/sage-poppy-hosted/pos.db.bak-pre-split"
cp -p .demo/sage-poppy/store.env .demo/sage-poppy/store.env.bak-pre-split
```

## 2. Bring up the shared edge proxy (Copper Lantern on its existing database)

Update the checkout to the merge commit (only files change; nothing restarts):

```sh
cd $APP && git fetch origin && git checkout $NEW && cd cloud/infra
```

**2a. Copper Lantern becomes a client — same images, same database.** Adopt
the running stack's settings and secrets; keep its compose project and its
Postgres volume; keep today's image tag for now:

```sh
OLD_TAG=$(grep '^IMAGE_TAG=' .env | cut -d= -f2-)
./new-client.sh copperlantern copperlantern-manager.lostmindllc.com \
  --from-env .env --project copper-lantern-manager \
  --pg-volume copper-lantern-manager_manager_pgdata \
  --registry $REGISTRY --image-tag $OLD_TAG --dry-run
```

Read the plan: tenant `copperlantern`, stores vieux-port, plateau, sage-poppy,
secrets and keys all **kept**, alias = the old `LEGACY_DOMAIN`. Then write it
(no `--up`: the old Caddy still owns 80/443):

```sh
./new-client.sh copperlantern copperlantern-manager.lostmindllc.com \
  --from-env .env --project copper-lantern-manager \
  --pg-volume copper-lantern-manager_manager_pgdata \
  --registry $REGISTRY --image-tag $OLD_TAG
diff <(grep -vE '^(#|$)' .env | cut -d= -f1 | sort) <(grep -vE '^(#|$)' clients/copperlantern/.env | cut -d= -f1 | sort)   # key names only
```

Recreate db/api/web under the client file. They join the shared `pos-edge`
network **and** stay on the project's own network, so the old Caddy keeps
reaching them by the same names while it still serves:

```sh
docker network create pos-edge 2>/dev/null || true
clients/copperlantern/compose.sh config -q                 # renders
clients/copperlantern/compose.sh stop api                  # stop-then-start: never two APIs on one DB
clients/copperlantern/compose.sh up -d                     # db, api, web (the old caddy/store are left alone: "orphans")
clients/copperlantern/compose.sh ps
curl -fsS https://copperlantern-manager.lostmindllc.com/health    # still via the OLD caddy
```

Expected: about half a minute of 502s while the API restarts; both Copper
Lantern stores keep selling and catch up within seconds.

**2b. The edge proxy takes over 80/443, reusing the old Caddy's certificates:**

```sh
ACME=$(grep '^ACME_EMAIL=' .env | cut -d= -f2-)
mkdir -p clients
cat > clients/proxy.env <<EOF
HTTP_PORT=80
HTTPS_PORT=443
ACME_EMAIL=$ACME
CADDY_DATA_VOLUME=copper-lantern-manager_manager_caddy_data
EDGE_NETWORK=pos-edge
EOF
docker stop copper-lantern-manager-caddy-1 && ./proxy.sh up
curl -fsS https://copperlantern-manager.lostmindllc.com/health
curl -sI https://copperlantern-manager.lostmindllc.com/login | head -1     # 200
docker logs pos-proxy-proxy-1 2>&1 | grep -i -E 'error|certificate' | tail -5
```

A few seconds without HTTPS, no new certificate needed (same data volume).
If anything is wrong: **rollback A** below.

**2c. Copper Lantern onto the new images** (brand packs, tenant id support):

```sh
./new-client.sh copperlantern copperlantern-manager.lostmindllc.com --image-tag $NEW
clients/copperlantern/compose.sh pull api web
clients/copperlantern/compose.sh stop api && clients/copperlantern/compose.sh up -d
curl -fsS https://copperlantern-manager.lostmindllc.com/health
```

Sign in at https://copperlantern-manager.lostmindllc.com: it must look exactly
as before (navy sidebar, lantern badge), and still list three stores for now.

## 3. Copper Lantern keeps its existing database

Nothing to run: step 2 kept the volume `copper-lantern-manager_manager_pgdata`,
the tenant id `copperlantern`, every store key and the owner's sign-in. Check:

```sh
docker exec copper-lantern-manager-db-1 psql -U pos -d pos_cloud -c \
  "SELECT venue_id, count(*) AS checks FROM checks GROUP BY 1 ORDER BY 1"
```

## 4. Create the Sage & Poppy portal

```sh
TOTP=$(grep '^TOTP_REQUIRED=' clients/copperlantern/.env | cut -d= -f2-)   # match Copper Lantern
./new-client.sh sagepoppy sagepoppy-manager.lostmindllc.com \
  --name "Sage & Poppy" --brand sagepoppy \
  --store "sage-poppy=Sage & Poppy Bottle Shop" \
  --zone America/Los_Angeles --currency USD --country US --retail \
  --admin-email "$(grep '^ADMIN_EMAIL=' clients/copperlantern/.env | cut -d= -f2-)" \
  --registry $REGISTRY --image-tag $NEW $( [[ "$TOTP" == false ]] && echo --no-totp ) --dry-run
# same command without --dry-run, with --up
```

`--up` creates the new database volume, starts db/api/web, and reloads the
proxy (Caddy fetches the new hostname's certificate on the first request).

```sh
curl -fsS https://sagepoppy-manager.lostmindllc.com/health
curl -s https://sagepoppy-manager.lostmindllc.com/login | grep -o '<title>[^<]*</title>'   # <title>Sage &amp; Poppy</title>
grep '^ADMIN_PASSWORD=' clients/sagepoppy/.env    # the owner's first password — read it here, don't paste it anywhere
```

Sign in (enrol the authenticator if asked). The portal is empty: that is
expected until step 5.

## 5. Point the Mac's Sage & Poppy store at its portal and re-send its history

On the Mac — copy the store's settings file (it holds the store's key):

```sh
cd ~/projects/posflutter
mkdir -p .demo/clients-hosted && chmod 700 .demo/clients-hosted
scp -i $KEY $HOST:/opt/posflutter-manager/app/cloud/infra/clients/sagepoppy/stores/sage-poppy.env .demo/clients-hosted/
chmod 600 .demo/clients-hosted/sage-poppy.env
scripts/demo-point-store.sh sage-poppy --hosted .demo/clients-hosted/sage-poppy.env --resend          # the plan
scripts/demo-point-store.sh sage-poppy --hosted .demo/clients-hosted/sage-poppy.env --resend --yes
```

This backs up `store.env` and `pos.db`, stops the store, writes the new URL
and key, clears `push_hwm` and `catalog_cursor` in its `sync_state` (the
outbox keeps every event, so the whole history — catalog, staff, sales, stock
— goes again), and restarts it (its login agent too, if installed). The first
push pins the store's identity on the new portal.

Wait for it to catch up (the 5,000-product catalog is sent in chunks; a few
minutes):

```sh
sqlite3 .demo/sage-poppy-hosted/pos.db "SELECT (SELECT value FROM sync_state WHERE key='push_hwm') AS acked, (SELECT max(id) FROM sync_outbox) AS outbox"
```

When `acked` = `outbox`, compare the two portals on the box:

```sh
for db in copper-lantern-manager-db-1 sagepoppy-portal-db-1; do
  echo "== $db"; docker exec $db psql -U pos -d pos_cloud -Atc \
  "SELECT 'events', count(*) FROM events WHERE venue_id='sage-poppy'
   UNION ALL SELECT 'checks', count(*) FROM checks WHERE venue_id='sage-poppy'
   UNION ALL SELECT 'items', count(*) FROM catalog_items WHERE venue_id='sage-poppy'
   UNION ALL SELECT 'stock moves', count(*) FROM stock_movements WHERE venue_id='sage-poppy'"
done
```

The Sage & Poppy portal should have at least as many of each (more if the store
sold since). Then look at it: sign-in and dashboard in the Sage & Poppy look,
USD only (no "≈ CA$" anywhere), English/Spanish toggle, Stock page, the store
card "online".

If the Sage & Poppy app on the tablet should feed this portal instead of the
Mac, that is `scripts/tablet-sagepoppy-setup.sh move-from-mac` as before (it
takes the Mac store's settings, now pointing here) — or `scripts/tablet-cloud-config.sh
--app sagepoppy --from .demo/clients-hosted/sage-poppy.env` for a tablet store that
already has the same identity.

## 6. Only after verifying: take Sage & Poppy out of Copper Lantern

**6a. Env first** (so no API restart re-creates the store): drop it from the
store list; its key and its zone/currency/country/retail entries go with it,
and `--no-fx` drops the conversion rate Copper Lantern no longer needs:

```sh
./new-client.sh copperlantern copperlantern-manager.lostmindllc.com \
  --store "vieux-port=$(grep '^STORES=' clients/copperlantern/.env | tr ',' '\n' | sed -n 's/.*vieux-port=//p')" \
  --store "plateau=$(grep '^STORES=' clients/copperlantern/.env | tr ',' '\n' | sed -n 's/.*plateau=//p')" \
  --currency CAD --country CA --no-fx --dry-run
# check: "no longer listed: sage-poppy"; then again without --dry-run
grep -E '^(STORES|STORE_API_KEYS|STORE_ZONES|STORE_CURRENCIES|STORE_COUNTRIES|RETAIL_STORES|REPORTING_CURRENCY|FX_USD_CAD)=' clients/copperlantern/.env | sed 's/\(STORE_API_KEYS=\).*/\1…/'
clients/copperlantern/compose.sh stop api && clients/copperlantern/compose.sh up -d api
```

**6b. The data** — a fresh backup, a rehearsal, then the real thing
(`cloud/infra/sql/remove-venue.sql`: one transaction; row counts per table for
every store before and after; aborts unless `sage-poppy` ends at zero rows and
Vieux-Port and Plateau are exactly unchanged):

```sh
docker exec copper-lantern-manager-db-1 pg_dump -U pos pos_cloud | gzip > ~/backups/pos_cloud-pre-remove-sp-$(date +%Y%m%d-%H%M).sql.gz
docker cp sql/remove-venue.sql copper-lantern-manager-db-1:/tmp/remove-venue.sql
docker exec copper-lantern-manager-db-1 psql -U pos -d pos_cloud -v tenant=copperlantern -v venue=sage-poppy -v commit=false -f /tmp/remove-venue.sql
# read the table: sage-poppy rows → 0, every vieux-port / plateau row before = after, "check passed", ROLLED BACK
docker exec copper-lantern-manager-db-1 psql -U pos -d pos_cloud -v tenant=copperlantern -v venue=sage-poppy -v commit=true -f /tmp/remove-venue.sql
docker exec copper-lantern-manager-db-1 psql -U pos -d pos_cloud -Atc "SELECT id FROM venues"   # vieux-port, plateau
```

The Copper Lantern portal now shows Vieux-Port and Plateau only, in CAD, with
no "≈" totals and no bottle-shop badge.

**6c. Tidy up** (optional, once happy): the old stack's leftover containers
(`docker rm copper-lantern-manager-caddy-1 copper-lantern-manager-store-1`)
and, on the Mac, the old key `DEMO_HOSTED_*` lines were already replaced by
step 5.

## 7. Rollback

**A — the proxy (step 2b) misbehaves:** give 80/443 back to the old Caddy.

```sh
./proxy.sh down && docker start copper-lantern-manager-caddy-1
```

(Its config still routes to `api`/`web` on the project network, which the
client-file containers still answer.)

**B — undo step 2 entirely:** back to the single-stack file, same volumes.

```sh
./proxy.sh down
clients/copperlantern/compose.sh stop api
docker compose -p copper-lantern-manager --env-file .env -f docker-compose.manager.yml up -d --no-deps db api web caddy
```

**C — Sage & Poppy back onto Copper Lantern's portal (before step 6):** on the
Mac, restore the store settings (the script printed the exact `undo` line):

```sh
cp .demo/sage-poppy-hosted/backups/repoint-<time>/store.env .demo/sage-poppy/store.env
scripts/demo-autostart.sh restart --store sage-poppy
```

Copper Lantern's database still has all of Sage & Poppy's history and key, and
events it already has are ignored, so nothing doubles. Then, on the box, stop
the new portal: `clients/sagepoppy/compose.sh down` (its volume is kept) and
`rm clients/proxy-sites/sagepoppy.caddy && ./proxy.sh reload`.

**D — after step 6:** restore Copper Lantern's database from the backup taken
in 6b, and put the env back:

```sh
cp ~/backups/manager.env-pre-split-<TS> /tmp/old.env   # the STORES/keys lines, if needed
clients/copperlantern/compose.sh stop api
gunzip -c ~/backups/pos_cloud-pre-remove-sp-<time>.sql.gz > /tmp/restore.sql
docker exec copper-lantern-manager-db-1 psql -U pos -d postgres -c "DROP DATABASE pos_cloud WITH (FORCE)" -c "CREATE DATABASE pos_cloud OWNER pos"
docker exec -i copper-lantern-manager-db-1 psql -U pos -d pos_cloud < /tmp/restore.sql
# re-add sage-poppy: re-run new-client.sh for copperlantern with all three --store flags
# (its old key comes back only from the backup env: STORE_API_KEYS in manager.env-pre-split-<TS>)
clients/copperlantern/compose.sh up -d api
```
