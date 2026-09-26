#!/usr/bin/env bash
# The shared edge proxy for every client portal on this box
# (docker-compose.proxy.yml + Caddyfile.proxy + clients/proxy-sites/*.caddy).
#
#   cloud/infra/proxy.sh up        # network + cert volume + proxy, idempotent
#   cloud/infra/proxy.sh reload    # graceful: pick up new/changed site files
#   cloud/infra/proxy.sh status
#   cloud/infra/proxy.sh down      # stops the proxy (every portal on the box goes dark)
#
# Settings: clients/proxy.env (written with defaults on first use; edit it):
#   HTTP_PORT=80  HTTPS_PORT=443  ACME_EMAIL=…
#   CADDY_DATA_VOLUME=pos-proxy_caddy_data   # an existing Caddy's data volume keeps its certs
#   EDGE_NETWORK=pos-edge  PROXY_IMAGE=…     # optional
# CLIENTS_DIR overrides where clients/ lives (default: next to this script).
set -euo pipefail

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENTS_DIR="${CLIENTS_DIR:-$INFRA_DIR/clients}"
PROXY_ENV="$CLIENTS_DIR/proxy.env"
SITES_DIR="$CLIENTS_DIR/proxy-sites"

die() { echo "ERROR: $*" >&2; exit 1; }

ensure_env() {
  mkdir -p "$SITES_DIR"
  if [[ ! -f "$PROXY_ENV" ]]; then
    umask 077
    cat > "$PROXY_ENV" <<EOF
# Edge proxy settings (cloud/infra/proxy.sh). No secrets here.
HTTP_PORT=80
HTTPS_PORT=443
ACME_EMAIL=
CADDY_DATA_VOLUME=pos-proxy_caddy_data
EDGE_NETWORK=pos-edge
EOF
    echo "Wrote $PROXY_ENV (defaults: ports 80/443). Edit it before a first public start."
  fi
}

penv() { grep "^$1=" "$PROXY_ENV" 2>/dev/null | tail -1 | cut -d= -f2- || true; }

dc() {
  SITES_DIR="$SITES_DIR" docker compose -p pos-proxy --env-file "$PROXY_ENV" \
    -f "$INFRA_DIR/docker-compose.proxy.yml" "$@"
}

proxy_cid() { dc ps -q proxy 2>/dev/null || true; }

cmd="${1:-}"
case "$cmd" in
  up)
    ensure_env
    net="$(penv EDGE_NETWORK)"; net="${net:-pos-edge}"
    vol="$(penv CADDY_DATA_VOLUME)"; vol="${vol:-pos-proxy_caddy_data}"
    docker network inspect "$net" >/dev/null 2>&1 || { docker network create "$net" >/dev/null; echo "created network $net"; }
    docker volume inspect "$vol" >/dev/null 2>&1 || { docker volume create "$vol" >/dev/null; echo "created volume $vol"; }
    dc up -d proxy
    echo "edge proxy up (http :$(penv HTTP_PORT), https :$(penv HTTPS_PORT); sites: $(ls "$SITES_DIR"/*.caddy 2>/dev/null | wc -l | tr -d ' '))"
    ;;
  reload)
    ensure_env
    cid="$(proxy_cid)"
    [[ -n "$cid" ]] || die "the edge proxy is not running (cloud/infra/proxy.sh up)"
    docker exec "$cid" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 \
      || die "the new site config does not validate — nothing was reloaded (docker exec $cid caddy validate --config /etc/caddy/Caddyfile)"
    docker exec "$cid" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 \
      || die "caddy refused the reload — docker logs $cid"
    echo "edge proxy reloaded ($(ls "$SITES_DIR"/*.caddy 2>/dev/null | wc -l | tr -d ' ') sites)"
    ;;
  status)
    ensure_env
    dc ps
    ls "$SITES_DIR"/*.caddy 2>/dev/null | sed 's|.*/|  site: |' || true
    ;;
  down)
    ensure_env
    dc stop proxy
    ;;
  *)
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
