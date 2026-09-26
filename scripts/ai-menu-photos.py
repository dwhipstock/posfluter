#!/usr/bin/env python3
"""Fill a whole store's menu with AI photos, through the store's own AI photo
endpoints (the same "Generate photo" a manager presses in the item editor).

The store holds the provider key and builds the house-style prompt; this
script only signs in with the manager PIN, asks the store to generate, and
picks. It never sees or sends a key.

Which items it does:
  default      items with no photo yet
  --skip-ai    also replace non-AI photos (uploads, imported stock photos);
               items that already have an AI photo are left alone
  --force      every item, AI photo or not
  --only a,b   just these item ids (with the rules above; add --force to redo)

It is idempotent and resumable: an item that already has what was asked for
is skipped, so an interrupted run picks up where it stopped. Each chosen
photo is kept locally with a contact sheet for review, in the gitignored
.ai-menu-photos/<store>/ folder.

  --count 1    one candidate per item, saved straight away (the cheapest)
  --count 2-4  candidates are kept on the store for 30 minutes and shown on
               the contact sheet; pick with --pick id=N,id=N (N from 1)

Copy mode: --copy-to URL2 uploads the AI photos the first store already has
to a second store (the tablet, the other pub), keeping their AI-generated
provenance, so both menus match and each picture is paid for once. It
generates nothing and costs nothing.

Usage:
  python3 scripts/ai-menu-photos.py --dry-run                      # plan + cost estimate, no spend
  python3 scripts/ai-menu-photos.py --store http://localhost:8080 --skip-ai
  python3 scripts/ai-menu-photos.py --only lantern-lager,poutine --force
  python3 scripts/ai-menu-photos.py --only poutine --count 3       # then --pick poutine=2
  python3 scripts/ai-menu-photos.py --copy-to http://192.168.1.50:8080

The manager PIN comes from --pin, or DEMO_MANAGER_PIN (default 1234).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import html
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

REPO = Path(__file__).resolve().parents[1]
DEFAULT_OUT = REPO / ".ai-menu-photos"
AI_SOURCES = ("ai_generated", "ai_enhanced")


# --- HTTP ---------------------------------------------------------------------

@dataclass
class Response:
    status: int
    body: bytes
    headers: dict = field(default_factory=dict)

    def json(self):
        try:
            return json.loads(self.body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None


Http = Callable[[str, str, dict, Optional[bytes]], Response]


def urllib_http(method: str, url: str, headers: dict, body: Optional[bytes], timeout: float = 240) -> Response:
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return Response(r.status, r.read(), dict(r.headers))
    except urllib.error.HTTPError as e:
        return Response(e.code, e.read() or b"", dict(e.headers or {}))


class StoreError(Exception):
    def __init__(self, message: str, code: str = "", status: int = 0, retry_after: Optional[float] = None):
        super().__init__(message)
        self.code, self.status, self.retry_after = code, status, retry_after


# codes that stop the whole run: every further item would fail the same way
FATAL = {"image_quota", "image_auth", "image_disabled", "image_offline", "image_unavailable", "invalid_pin"}


def multipart(fields: dict[str, str], filename: str, mime: str, payload: bytes) -> tuple[bytes, str]:
    boundary = f"----ai-menu-photos-{time.time_ns()}"
    out = []
    for name, value in fields.items():
        out += [f"--{boundary}\r\n".encode(), f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                value.encode(), b"\r\n"]
    out += [f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="photo"; filename="{filename}"\r\n'.encode(),
            f"Content-Type: {mime}\r\n\r\n".encode(), payload, b"\r\n", f"--{boundary}--\r\n".encode()]
    return b"".join(out), f"multipart/form-data; boundary={boundary}"


class Store:
    """The store's HTTP API, signed in as a manager."""

    def __init__(self, url: str, pin: str, http: Http = urllib_http, sleep=time.sleep):
        self.url = url.rstrip("/")
        self._pin = pin
        self.http = http
        self.sleep = sleep
        self._token = ""

    def __repr__(self):  # never the PIN or the session
        return f"Store({self.url})"

    def _call(self, method: str, path: str, body: Optional[dict] = None, raw: Optional[tuple[bytes, str]] = None,
              auth: bool = True) -> Response:
        headers = {"accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if raw is not None:
            data, headers["Content-Type"] = raw
        if auth and self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        return self.http(method, self.url + path, headers, data)

    def _ok(self, res: Response, what: str):
        if 200 <= res.status < 300:
            return res.json()
        data = res.json() if isinstance(res.json(), dict) else {}
        retry = res.headers.get("Retry-After") or res.headers.get("retry-after")
        raise StoreError(f"{what}: HTTP {res.status} {data.get('code') or ''} {data.get('error') or ''}".strip(),
                         code=data.get("code") or "", status=res.status,
                         retry_after=float(retry) if retry and str(retry).isdigit() else None)

    def login(self):
        data = self._ok(self._call("POST", "/login", {"pin": self._pin}, auth=False), "sign in")
        # the endpoints that spend or save check the PIN is a manager's themselves
        self._token = data["token"]

    def status(self) -> dict:
        return self._ok(self._call("GET", "/ai-photos/status"), "AI photo status")

    def items(self) -> list[dict]:
        return self._ok(self._call("GET", "/items?all=true"), "menu")

    def generate(self, item_id: str, count: int) -> dict:
        path = f"/items/{urllib.parse.quote(item_id)}/ai-photo/generate"
        for attempt in range(3):
            try:
                return self._ok(self._call("POST", path, {"managerPin": self._pin, "count": count}), "generate")
            except StoreError as e:
                if e.code != "image_rate_limited" or attempt == 2:
                    raise
                self.sleep(min(e.retry_after or 10.0, 60.0))
        raise AssertionError("unreachable")  # pragma: no cover

    def choose(self, item_id: str, candidate_id: str) -> dict:
        path = f"/items/{urllib.parse.quote(item_id)}/ai-photo/choose"
        return self._ok(self._call("POST", path, {"managerPin": self._pin, "candidateId": candidate_id}), "choose")

    def photo(self, item_id: str) -> tuple[bytes, str]:
        res = self._call("GET", f"/photos/{urllib.parse.quote(item_id)}")
        if res.status != 200:
            raise StoreError(f"download photo: HTTP {res.status}", status=res.status)
        ctype = (res.headers.get("Content-Type") or res.headers.get("content-type") or "image/jpeg").split(";")[0]
        return res.body, ctype.strip().lower()

    def upload(self, item_id: str, data: bytes, content_type: str, source: str) -> dict:
        ext = "png" if content_type == "image/png" else "jpg"
        raw = multipart({"managerPin": self._pin, "source": source}, f"{item_id}.{ext}", content_type, data)
        return self._ok(self._call("POST", f"/items/{urllib.parse.quote(item_id)}/photo", raw=raw), "upload")


# --- local state (resumable, reviewable) ---------------------------------------

def store_dir(root: Path, url: str) -> Path:
    p = urllib.parse.urlparse(url)
    return root / (f"{p.hostname or 'store'}-{p.port}" if p.port else (p.hostname or "store"))


class State:
    """state.json: what was chosen per item, pending candidates, and copies made."""

    def __init__(self, folder: Path):
        self.folder = folder
        self.path = folder / "state.json"
        try:
            self.data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.data = {}
        self.data.setdefault("items", {})
        self.data.setdefault("copies", {})

    def save(self):
        self.folder.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, indent=2, sort_keys=True), encoding="utf-8")
        tmp.replace(self.path)

    def item(self, item_id: str) -> dict:
        return self.data["items"].setdefault(item_id, {})


def ext_of(content_type: str) -> str:
    return "png" if content_type == "image/png" else "jpg"


# --- the fill -----------------------------------------------------------------

def wanted(items: list[dict], only: list[str], skip_ai: bool, force: bool) -> tuple[list[dict], list[tuple[dict, str]]]:
    """(to do, [(skipped, why)]) in menu order."""
    todo, skipped = [], []
    by_id = {i["id"]: i for i in items}
    chosen = [by_id[i] for i in only if i in by_id] if only else items
    for item in chosen:
        has = item.get("photoVersion") is not None
        src = item.get("photoSource") if has else None
        if force:
            todo.append(item)
        elif src in AI_SOURCES:
            skipped.append((item, "already has an AI photo"))
        elif has and not skip_ai:
            skipped.append((item, "has a photo (--skip-ai replaces non-AI ones)"))
        else:
            todo.append(item)
    return todo, skipped


def fill(store: Store, state: State, items: list[dict], count: int, log=print) -> list[dict]:
    """Generate for each item; with count 1 the one candidate is saved. Returns per-item rows."""
    rows = []
    state.folder.mkdir(parents=True, exist_ok=True)
    for n, item in enumerate(items, 1):
        iid, name = item["id"], item.get("nameEn") or item.get("nameFr") or item["id"]
        prefix = f"[{n}/{len(items)}] {name}"
        row = {"id": iid, "name": name}
        start = time.monotonic()
        try:
            gen = store.generate(iid, count)
            cands = gen.get("candidates") or []
            if not cands:
                raise StoreError("the store returned no picture")
            row.update(cost=gen.get("estimatedCostUsd") or 0.0, provider=f"{gen.get('provider')}/{gen.get('model')}")
            rec = state.item(iid)
            if count == 1:
                store.choose(iid, cands[0]["id"])
                save_chosen(store, state, iid, gen.get("source") or "ai_generated")
                rec.pop("pending", None)
                row["status"] = "saved"
                log(f"{prefix}: saved ({time.monotonic() - start:.0f}s, ~${row['cost']:.3f})")
            else:
                files = []
                for k, c in enumerate(cands, 1):
                    f = f"{iid}--{k}.{ext_of(c.get('contentType', ''))}"
                    (state.folder / f).write_bytes(base64.b64decode(c["dataBase64"]))
                    files.append({"file": f, "candidateId": c["id"]})
                rec["pending"] = {"at": time.time(), "candidates": files, "source": gen.get("source")}
                row["status"] = f"{len(files)} to pick"
                log(f"{prefix}: {len(files)} candidates (pick with --pick {iid}=N within 30 minutes)")
            state.save()
        except StoreError as e:
            row["status"] = f"failed: {e}"
            log(f"{prefix}: FAILED {e}")
            rows.append(row)
            if e.code in FATAL:
                log(f"Stopping: {e.code} would fail every other item too. Nothing else was spent.")
                break
            continue
        rows.append(row)
    return rows


def save_chosen(store: Store, state: State, item_id: str, source: str):
    """Keep the store's saved picture (after its own resize) as the local copy of record."""
    data, ctype = store.photo(item_id)
    state.folder.mkdir(parents=True, exist_ok=True)
    f = f"{item_id}.{ext_of(ctype)}"
    (state.folder / f).write_bytes(data)
    rec = state.item(item_id)
    rec.update(file=f, source=source, contentType=ctype, sha256=hashlib.sha256(data).hexdigest(), at=time.time())


def pick(store: Store, state: State, picks: dict[str, int], log=print) -> list[dict]:
    rows = []
    for iid, k in picks.items():
        row = {"id": iid, "name": iid}
        pend = state.item(iid).get("pending")
        try:
            if not pend or not (1 <= k <= len(pend["candidates"])):
                raise StoreError("no such candidate (generate with --count 2-4 first)")
            store.choose(iid, pend["candidates"][k - 1]["candidateId"])
            save_chosen(store, state, iid, pend.get("source") or "ai_generated")
            state.item(iid).pop("pending", None)
            state.save()
            row["status"] = f"saved #{k}"
            log(f"{iid}: saved candidate {k}")
        except StoreError as e:
            row["status"] = f"failed: {e}"
            log(f"{iid}: FAILED {e}")
        rows.append(row)
    return rows


def copy(src: Store, dst: Store, state: State, only: list[str], force: bool, dry_run: bool, log=print) -> list[dict]:
    """Upload src's AI photos onto the same items at dst, keeping their provenance."""
    src_items = {i["id"]: i for i in src.items()}
    dst_items = {i["id"]: i for i in dst.items()}
    done = state.data["copies"].setdefault(dst.url, {})
    rows = []
    for iid, item in src_items.items():
        if only and iid not in only:
            continue
        source = item.get("photoSource") if item.get("photoVersion") is not None else None
        if source not in AI_SOURCES:
            continue
        name = item.get("nameEn") or iid
        row = {"id": iid, "name": name, "source": source}
        rows.append(row)
        target = dst_items.get(iid)
        if target is None:
            row["status"] = "not on the other menu"
            log(f"{name}: not on {dst.url}, skipped")
            continue
        try:
            data, ctype = src.photo(iid)
            digest = hashlib.sha256(data).hexdigest()
            same = done.get(iid) == digest and target.get("photoSource") == source
            if same and not force:
                row["status"] = "already copied"
                log(f"{name}: already copied")
                continue
            if dry_run:
                row["status"] = "would copy"
                log(f"{name}: would copy ({source})")
                continue
            dst.upload(iid, data, ctype, source)
            done[iid] = digest
            f = f"{iid}.{ext_of(ctype)}"
            state.folder.mkdir(parents=True, exist_ok=True)
            (state.folder / f).write_bytes(data)
            state.item(iid).update(file=f, source=source, contentType=ctype, sha256=digest)
            state.save()
            row["status"] = "copied"
            log(f"{name}: copied ({source})")
        except StoreError as e:
            row["status"] = f"failed: {e}"
            log(f"{name}: FAILED {e}")
    return rows


# --- the contact sheet --------------------------------------------------------

def contact_sheet(folder: Path, store_url: str, items: list[dict], state: State, rows: list[dict]):
    e = html.escape
    status = {r["id"]: r.get("status", "") for r in rows}
    cells = []
    for item in items:
        iid = item["id"]
        rec = state.data["items"].get(iid) or {}
        pics = []
        if rec.get("file") and (folder / rec["file"]).exists():
            pics.append(f"<img src='{e(rec['file'])}' alt='{e(iid)}'>")
        for k, c in enumerate((rec.get("pending") or {}).get("candidates") or [], 1):
            pics.append(f"<figure><img src='{e(c['file'])}' alt='{e(iid)} {k}'><figcaption>#{k}</figcaption></figure>")
        if not pics and iid not in status:
            continue
        name = item.get("nameEn") or iid
        cells.append(f"<div class=card><div class=pics>{''.join(pics) or '<span class=none>no picture</span>'}</div>"
                     f"<b>{e(name)}</b><small>{e(iid)} · {e(rec.get('source') or '')}</small>"
                     f"<small>{e(status.get(iid, 'kept from an earlier run'))}</small></div>")
    page = f"""<!doctype html>
<html lang=en><head><meta charset=utf-8><meta name=viewport content="width=device-width, initial-scale=1">
<title>AI menu photos</title>
<style>
:root {{ --bg:#F6F0E5; --card:#FFFCF7; --ink:#1C2733; --muted:#62574B; --line:#DCCFB9; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#121A23; --card:#1C2733; --ink:#F2EADC; --muted:#BFAF95; --line:#3E362D; }} }}
body {{ margin:0; padding:16px; background:var(--bg); color:var(--ink); font:14px/1.4 system-ui, sans-serif; }}
h1 {{ font-size:20px; margin:0 0 4px; }} p {{ color:var(--muted); margin:0 0 16px; }}
.grid {{ display:grid; grid-template-columns:repeat(auto-fill, minmax(220px, 1fr)); gap:12px; }}
.card {{ background:var(--card); border:1px solid var(--line); border-radius:8px; padding:8px; }}
.pics {{ display:flex; gap:6px; flex-wrap:wrap; }} figure {{ margin:0; }}
img {{ width:100%; max-width:204px; aspect-ratio:1; object-fit:cover; border-radius:6px; display:block; }}
.pics figure img {{ width:96px; }} figcaption, small {{ display:block; color:var(--muted); }}
.none {{ color:var(--muted); }}
</style></head><body>
<h1>AI menu photos</h1>
<p>{e(store_url)} · updated {e(time.strftime('%Y-%m-%d %H:%M'))}</p>
<div class=grid>{''.join(cells)}</div>
</body></html>
"""
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "index.html").write_text(page, encoding="utf-8")


# --- main ---------------------------------------------------------------------

def parse_picks(raw: str) -> dict[str, int]:
    out = {}
    for part in (p.strip() for p in raw.split(",") if p.strip()):
        iid, _, k = part.partition("=")
        if not k.strip().isdigit():
            raise SystemExit(f"--pick wants id=N (N from 1), got {part!r}")
        out[iid.strip()] = int(k)
    return out


def main(argv: Optional[list[str]] = None, http: Http = urllib_http, log=print) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--store", default=os.environ.get("STORE_URL", "http://localhost:8080"), help="store URL")
    ap.add_argument("--pin", default=os.environ.get("DEMO_MANAGER_PIN", "1234"), help="manager PIN")
    ap.add_argument("--count", type=int, default=1, help="candidates per item, 1-4 (1 = save the first)")
    ap.add_argument("--only", default="", help="comma list of item ids")
    ap.add_argument("--skip-ai", action="store_true", help="replace non-AI photos; leave AI ones alone")
    ap.add_argument("--force", action="store_true", help="redo every chosen item, AI photo or not")
    ap.add_argument("--pick", default="", help="save candidates from a --count 2-4 run: id=N,id=N")
    ap.add_argument("--copy-to", default="", help="upload this store's AI photos to a second store")
    ap.add_argument("--dry-run", action="store_true", help="show the plan and cost; spend nothing")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT, help="local folder (default: .ai-menu-photos)")
    args = ap.parse_args(argv)
    if not 1 <= args.count <= 4:
        log("--count is 1 to 4")
        return 2
    only = list(dict.fromkeys(i.strip() for i in args.only.split(",") if i.strip()))

    store = Store(args.store, args.pin, http)
    try:
        store.login()
    except (StoreError, urllib.error.URLError, OSError) as e:
        log(f"Cannot sign in to {args.store}: {e}")
        return 1
    folder = store_dir(args.out, store.url)
    state = State(folder)

    if args.copy_to:
        dst = Store(args.copy_to, args.pin, http)
        try:
            dst.login()
        except (StoreError, urllib.error.URLError, OSError) as e:
            log(f"Cannot sign in to {args.copy_to}: {e}")
            return 1
        rows = copy(store, dst, state, only, args.force, args.dry_run, log)
        copied = sum(r["status"] == "copied" for r in rows)
        failed = [r for r in rows if r["status"].startswith("failed")]
        log(f"\n{copied} copied, {len(rows) - copied - len(failed)} skipped, {len(failed)} failed "
            f"({store.url} -> {dst.url}); no AI spend.")
        return 1 if failed else 0

    items = store.items()
    if args.pick:
        rows = pick(store, state, parse_picks(args.pick), log)
        contact_sheet(folder, store.url, items, state, rows)
        log(f"Contact sheet: {folder / 'index.html'}")
        return 1 if any(r["status"].startswith("failed") for r in rows) else 0

    unknown = [i for i in only if i not in {x["id"] for x in items}]
    if unknown:
        log(f"Not on this menu: {', '.join(unknown)}")
        return 2
    todo, skipped = wanted(items, only, args.skip_ai, args.force)
    st = store.status()
    per_image = st.get("estimatedCostPerImageUsd")
    estimate = f"about ${per_image * args.count * len(todo):.2f}" if per_image is not None else "cost unknown"
    log(f"{store.url}: {len(todo)} to do, {len(skipped)} skipped, {args.count} candidate(s) each — {estimate} "
        f"({st.get('provider')}/{st.get('model') or '-'})")
    for item, why in skipped:
        log(f"  skip {item['id']}: {why}")
    if args.dry_run:
        for item in todo:
            log(f"  would generate {item['id']} ({item.get('nameEn') or ''})")
        return 0
    if todo and not st.get("available"):
        log(f"AI photos are not available on this store ({st.get('reason')}). Nothing was spent.")
        return 1
    rows = fill(store, state, todo, args.count, log)
    rows += [{"id": i["id"], "name": i.get("nameEn"), "status": f"skipped: {why}"} for i, why in skipped]
    contact_sheet(folder, store.url, items, state, rows)
    saved = sum(r.get("status") == "saved" for r in rows)
    failed = [r for r in rows if str(r.get("status", "")).startswith("failed")]
    spent = sum(r.get("cost") or 0.0 for r in rows)
    log(f"\n{saved} saved, {len(skipped)} skipped, {len(failed)} failed, ~${spent:.2f} estimated spend.")
    log(f"Contact sheet: {folder / 'index.html'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
