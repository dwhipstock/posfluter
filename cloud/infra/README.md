# Cloud infrastructure

This directory contains generic infrastructure for deploying the POS cloud API,
portal, and per-venue store services. It contains no production credentials or venue
data.

## Components

- `docker-compose.yml` runs PostgreSQL, the Kotlin cloud API, the web portal, and Caddy.
- `Caddyfile` terminates TLS and routes venue subdomains.
- `systemd/` contains service templates for managed hosts.
- `monitoring/` contains health checks and alerting examples.

Copy the sample environment file, provide your own domain and secrets, then follow the
comments in `docker-compose.yml`. Example hostnames use `example.com` and must be
replaced for a real deployment.

Never commit `.env` files, TLS private keys, database dumps, device tokens, customer
photos, or backups. Use `scripts/provision-venue.sh` for a new venue and the scripts in
`scripts/e2e/` for validation against an isolated test deployment.

## Deploying a new cloud API

Cloud migrations run when the API starts, and they are forward-only. An old and a
new API must never run against the same database at the same time: migration
`013_timestamptz` converts every timestamp column to `timestamptz`, and an old API
still writing zone-less local times during or after it would store wrong instants.
So a cloud deploy is **stop, then start** — never a rolling or blue/green overlap:

```sh
docker compose stop api            # old API fully down (the store outboxes queue)
docker compose pull api
docker compose up -d api           # new API migrates, then serves
```

Stores lose nothing while the API is down: each keeps selling and queues in its
outbox. Upgrade the cloud API **before** the stores: an upgraded store asks
`GET /v1/store/capabilities` first and holds its pushes against an older cloud
until it is upgraded (cloud/CONTRACT.md §0), so `scripts/upgrade-venue.sh`'s
sync-caught-up gate would fail and roll the venue back.

## Independent manager portal

`docker-compose.manager.yml` runs a reporting-only deployment on its own host and
volumes. It uses immutable ECR image tags produced by the build workflow and the
exact-host-only `Caddyfile.manager`, so it cannot intercept or modify existing venue
store traffic. Set `REGISTRY`, `IMAGE_TAG`, `DOMAIN`, `LEGACY_DOMAIN`, `ACME_EMAIL`,
the database and admin credentials, and `STORE_API_KEY` in an untracked `.env`, then run:

For a portal-only update, `WEB_IMAGE_TAG` can select a newer immutable web
image without changing the store, API, or Caddy image tag.

```sh
docker compose -f docker-compose.manager.yml pull
docker compose -f docker-compose.manager.yml up -d
```
