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
