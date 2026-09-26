#!/usr/bin/env python3
"""AI menu photo bake-off: the same 10 Copper Lantern items through every
image provider we have a key for, side by side on one HTML contact sheet.

Rows are items, columns are providers; each cell shows the picture, how long
it took and a cost estimate. It uses the same endpoints, models and house-style
prompt as the store (server/.../aiphotos/), so what you see here is what a
manager would get from "Generate photo".

Keys come from the repo-root .env (gitignored), one per line:
  BFL_API_KEY=...        # FLUX (Black Forest Labs)
  GEMINI_API_KEY=...     # Google Gemini image
  OPENAI_API_KEY=...     # OpenAI GPT Image
A provider without a key is skipped (the script says so). Nothing is sent
anywhere else; keys are never printed or written to the output.

Usage:
  python3 scripts/image-bakeoff.py                 # every provider with a key
  python3 scripts/image-bakeoff.py --providers flux,openai --items 3
  python3 scripts/image-bakeoff.py --dry-run       # the plan and cost estimate, no calls
  python3 scripts/image-bakeoff.py --fake          # a fake provider, no keys, no network

Output: .image-bakeoff/<timestamp>/index.html (gitignored) plus the images.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import html
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

REPO = Path(__file__).resolve().parents[1]
DEFAULT_OUT = REPO / ".image-bakeoff"

# --- the house style: keep in sync with server/.../aiphotos/HouseStyle.kt ----
# (tests/test_image_bakeoff.py checks these strings are still in that file)
CPR_SHOT = "professional food and drink menu photograph for a pub"
CPR_SCENE = (
    "rustic pub setting: dark, worn wooden table, warm low tungsten light with a soft "
    "glow from the left, gentle shadows, camera at a 45-degree angle, shallow depth of field, "
    "blurred background of brick and copper tones"
)
CLEAN = ("No text, no captions, no logos or readable brand names, no watermark, "
         "no people or hands, no cutlery clutter.")


@dataclass(frozen=True)
class Item:
    id: str
    name: str
    description: str
    category: str


# Ten varied Copper Lantern items (the soup is not on today's seeded menu; it
# covers the "soup" slot of the bake-off with a plausible pub dish).
ITEMS = [
    Item("lantern-burger", "Copper Lantern Burger", "Beef, cheddar, bacon, onions and house sauce.", "Burgers & Sandwiches"),
    Item("poutine", "Classic Poutine", "Fries, cheese curds and savoury gravy.", "Starters"),
    Item("fish-chips", "Beer-Battered Fish and Chips", "Haddock, fries, slaw and tartar sauce.", "Mains & Salads"),
    Item("caesar-salad", "Caesar Salad", "Romaine, parmesan, croutons and Caesar dressing.", "Mains & Salads"),
    Item("cheesecake", "Maple Cheesecake", "Maple cheesecake with toasted pecans.", "Desserts"),
    Item("lantern-lager", "Lantern House Lager", "Crisp, malty lager brewed in Montréal, in a pint glass.", "Beer & Cider"),
    Item("copper-old-fashioned", "Copper Old Fashioned", "Canadian whisky, maple, bitters and orange.", "Cocktails"),
    Item("pinot-noir", "Eastern Townships Pinot Noir", "Light red with cherry and spice, a glass of red wine.", "Wine"),
    Item("reuben", "Montreal Reuben", "Smoked meat, Swiss cheese, sauerkraut and Russian dressing.", "Burgers & Sandwiches"),
    Item("onion-soup", "French Onion Soup", "Caramelised onion broth, toasted bread and melted Gruyère.", "Starters"),
]


def generate_prompt(item: Item) -> str:
    """Same template as PhotoPrompts.generate in the store."""
    desc = item.description.strip().rstrip(".")
    return (f"A {CPR_SHOT} of {item.name.strip()}" + (f": {desc}" if desc else "") +
            f". Menu category: {item.category.strip()}. "
            f"House style, shared by every photo on this menu: {CPR_SCENE}. "
            "One single serving is the only subject, centred and filling most of the frame, realistic, "
            "appetising and true to how it is actually served. " + CLEAN)


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


def urllib_http(method: str, url: str, headers: dict, body: Optional[bytes], timeout: float = 150) -> Response:
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return Response(r.status, r.read(), dict(r.headers))
    except urllib.error.HTTPError as e:
        return Response(e.code, e.read() or b"", dict(e.headers or {}))


class ProviderError(Exception):
    pass


def _check(provider: str, res: Response) -> dict:
    data = res.json()
    if 200 <= res.status < 300 and isinstance(data, dict):
        return data
    detail = ""
    if isinstance(data, dict):
        err = data.get("error")
        detail = (err.get("message") if isinstance(err, dict) else None) or str(data.get("detail") or err or "")
    kind = {401: "key rejected", 402: "out of credits", 403: "key rejected", 429: "rate limited"}.get(res.status, "error")
    raise ProviderError(f"{provider} {kind} (HTTP {res.status}) {detail[:160]}".strip())


# --- providers (same calls as the store) --------------------------------------

@dataclass
class Result:
    image: Optional[bytes] = None
    ext: str = "jpg"
    seconds: float = 0.0
    error: Optional[str] = None


class Provider:
    id = "base"
    label = "Base"
    model = ""
    env = ""
    cost_per_image = 0.0

    def __init__(self, key: str, http: Http = urllib_http, sleep=time.sleep, clock=time.monotonic):
        self._key = key
        self.http = http
        self.sleep = sleep
        self.clock = clock

    def generate(self, prompt: str) -> tuple[bytes, str]:
        raise NotImplementedError

    def __repr__(self):  # never the key
        return f"{type(self).__name__}({self.model})"


class FluxProvider(Provider):
    id, label, env = "flux", "FLUX.2 [pro]", "BFL_API_KEY"
    model = "flux-2-pro"
    cost_per_image = 0.03
    base = "https://api.bfl.ai"
    deadline = 120.0

    def generate(self, prompt):
        body = json.dumps({"prompt": prompt, "width": 1024, "height": 1024,
                           "output_format": "jpeg", "safety_tolerance": 2}).encode()
        headers = {"x-key": self._key, "accept": "application/json", "Content-Type": "application/json"}
        task = _check("FLUX", self.http("POST", f"{self.base}/v1/{self.model}", headers, body))
        poll = task.get("polling_url") or f"{self.base}/v1/get_result?id={task.get('id')}"
        host = urllib.parse.urlparse(poll).hostname or ""
        if not (host == "bfl.ai" or host.endswith(".bfl.ai")):
            raise ProviderError("FLUX: unexpected polling host")
        start = self.clock()
        interval = 0.75
        while True:
            if self.clock() - start > self.deadline:
                raise ProviderError("FLUX timed out")
            self.sleep(interval)
            res = self.http("GET", poll, {"x-key": self._key, "accept": "application/json"}, None)
            if res.status == 429:
                interval = min(interval * 2, 5)
                continue
            data = _check("FLUX", res)
            status = data.get("status")
            if status == "Ready":
                img = self.http("GET", data["result"]["sample"], {}, None)
                if img.status != 200:
                    raise ProviderError(f"FLUX download failed (HTTP {img.status})")
                return img.body, "jpg"
            if status in ("Request Moderated", "Content Moderated"):
                raise ProviderError(f"FLUX refused: {status}")
            if status in ("Error", "Failed"):
                raise ProviderError(f"FLUX task {status}")
            interval = min(interval + 0.25, 2.0)


class GeminiProvider(Provider):
    id, label, env = "gemini", "Gemini 3.1 Flash Image", "GEMINI_API_KEY"
    model = "gemini-3.1-flash-image"
    cost_per_image = 0.067
    base = "https://generativelanguage.googleapis.com"

    def generate(self, prompt):
        body = json.dumps({
            "model": self.model,
            "input": [{"type": "text", "text": prompt}],
            "response_format": {"type": "image", "mime_type": "image/jpeg", "aspect_ratio": "1:1", "image_size": "1K"},
        }).encode()
        headers = {"x-goog-api-key": self._key, "accept": "application/json", "Content-Type": "application/json"}
        data = _check("Gemini", self.http("POST", f"{self.base}/v1beta/interactions", headers, body))
        for step in data.get("steps") or []:
            for block in step.get("content") or []:
                if block.get("type") == "image" and block.get("data"):
                    ext = "png" if block.get("mime_type") == "image/png" else "jpg"
                    return base64.b64decode(block["data"]), ext
        for cand in data.get("candidates") or []:
            for part in (cand.get("content") or {}).get("parts") or []:
                inline = part.get("inlineData") or part.get("inline_data")
                if inline and inline.get("data"):
                    return base64.b64decode(inline["data"]), "jpg"
        raise ProviderError(f"Gemini returned no image (status {data.get('status')}); likely a content-policy refusal")


class OpenAIProvider(Provider):
    id, label, env = "openai", "GPT Image 1.5", "OPENAI_API_KEY"
    model = "gpt-image-1.5"
    cost_per_image = 0.034
    base = "https://api.openai.com"

    def generate(self, prompt):
        body = json.dumps({"model": self.model, "prompt": prompt, "n": 1, "size": "1024x1024",
                           "quality": "medium", "output_format": "jpeg"}).encode()
        headers = {"Authorization": f"Bearer {self._key}", "accept": "application/json",
                   "Content-Type": "application/json"}
        res = self.http("POST", f"{self.base}/v1/images/generations", headers, body)
        data = res.json()
        if res.status == 400 and isinstance(data, dict) and (data.get("error") or {}).get("code") == "moderation_blocked":
            raise ProviderError("OpenAI refused (moderation_blocked)")
        data = _check("OpenAI", res)
        for d in data.get("data") or []:
            if d.get("b64_json"):
                return base64.b64decode(d["b64_json"]), "jpg"
        raise ProviderError("OpenAI returned no image")


class FakeProvider(Provider):
    """No network, no key: a flat colour square per item, for trying the script."""
    id, label, env = "fake", "Fake (no network)", ""
    model = "fake-1"
    cost_per_image = 0.0

    def generate(self, prompt):
        h = zlib.crc32(prompt.encode())
        return solid_png(64, 64, (h & 0xFF, (h >> 8) & 0xFF, (h >> 16) & 0xFF)), "png"


PROVIDERS = {p.id: p for p in (FluxProvider, GeminiProvider, OpenAIProvider)}


def solid_png(w: int, h: int, rgb: tuple[int, int, int]) -> bytes:
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


# --- keys ---------------------------------------------------------------------

def read_env(path: Path) -> dict[str, str]:
    """KEY=value lines from a .env file (comments and blanks ignored, quotes stripped)."""
    out: dict[str, str] = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip().removeprefix("export ").strip()
        v = v.strip().strip("'\"")
        if v:
            out[k] = v
    return out


def pick_providers(wanted: list[str], env: dict[str, str], http: Http = urllib_http,
                   log=print) -> list[Provider]:
    chosen: list[Provider] = []
    for pid in wanted:
        cls = PROVIDERS.get(pid)
        if cls is None:
            log(f"skip {pid}: unknown provider (choose from {', '.join(PROVIDERS)})")
            continue
        key = env.get(cls.env) or os.environ.get(cls.env)
        if not key:
            log(f"skip {pid}: no {cls.env} in .env or the environment")
            continue
        chosen.append(cls(key, http))
    return chosen


# --- the run ------------------------------------------------------------------

def run_one(provider: Provider, item: Item) -> Result:
    start = time.monotonic()
    try:
        image, ext = provider.generate(generate_prompt(item))
        return Result(image=image, ext=ext, seconds=time.monotonic() - start)
    except ProviderError as e:
        return Result(error=str(e), seconds=time.monotonic() - start)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return Result(error=f"{provider.id} unreachable: {type(e).__name__}", seconds=time.monotonic() - start)


def run(providers: list[Provider], items: list[Item], out: Path, log=print) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    results: dict[tuple[str, str], Result] = {}

    def per_provider(p: Provider):
        for item in items:
            r = run_one(p, item)
            if r.image:
                (out / f"{item.id}--{p.id}.{r.ext}").write_bytes(r.image)
            log(f"  {p.id:7} {item.id:22} " + (f"{r.seconds:5.1f}s" if not r.error else f"FAILED: {r.error}"))
            results[(item.id, p.id)] = r

    # providers side by side, each one's items in turn (gentle on rate limits)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, len(providers))) as pool:
        list(pool.map(per_provider, providers))
    (out / "index.html").write_text(contact_sheet(providers, items, results), encoding="utf-8")
    (out / "results.json").write_text(json.dumps({
        f"{i}|{p}": {"seconds": round(r.seconds, 2), "error": r.error,
                     "file": None if r.error else f"{i}--{p}.{r.ext}"}
        for (i, p), r in results.items()}, indent=2), encoding="utf-8")
    return results


def contact_sheet(providers: list[Provider], items: list[Item], results: dict) -> str:
    e = html.escape
    head = "".join(
        f"<th>{e(p.label)}<small>{e(p.model)} · ~${p.cost_per_image:.3f}/image</small></th>" for p in providers)
    rows = []
    for item in items:
        cells = []
        for p in providers:
            r = results.get((item.id, p.id))
            if r is None:
                cells.append("<td class=err>not run</td>")
            elif r.error:
                cells.append(f"<td class=err>{e(r.error)}<small>{r.seconds:.1f}s</small></td>")
            else:
                cells.append(f"<td><img src='{e(item.id)}--{e(p.id)}.{r.ext}' alt='{e(item.name)} ({e(p.label)})'>"
                             f"<small>{r.seconds:.1f}s · ~${p.cost_per_image:.3f}</small></td>")
        rows.append(f"<tr><th class=item>{e(item.name)}<small>{e(item.category)}</small></th>{''.join(cells)}</tr>")
    totals = []
    for p in providers:
        done = [r for (i, pid), r in results.items() if pid == p.id and not r.error]
        secs = [r.seconds for r in done]
        avg = f"{sum(secs) / len(secs):.1f}s avg" if secs else "—"
        totals.append(f"<td>{len(done)}/{len(items)} ok · {avg} · ~${p.cost_per_image * len(done):.2f}</td>")
    return f"""<!doctype html>
<html lang=en><head><meta charset=utf-8><meta name=viewport content="width=device-width, initial-scale=1">
<title>Menu photo bake-off</title>
<style>
:root {{ --bg:#F6F0E5; --card:#FFFCF7; --ink:#1C2733; --muted:#62574B; --line:#DCCFB9; --bad:#B3261E; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#121A23; --card:#1C2733; --ink:#F2EADC; --muted:#BFAF95; --line:#3E362D; --bad:#F2B8B5; }} }}
body {{ margin:0; padding:16px; background:var(--bg); color:var(--ink); font:14px/1.4 system-ui, sans-serif; }}
h1 {{ font-size:20px; margin:0 0 4px; }} p {{ color:var(--muted); margin:0 0 16px; }}
.wrap {{ overflow-x:auto; }}
table {{ border-collapse:collapse; background:var(--card); }}
th, td {{ border:1px solid var(--line); padding:8px; vertical-align:top; text-align:left; }}
td {{ width:240px; }} img {{ width:224px; height:224px; object-fit:cover; display:block; border-radius:6px; }}
small {{ display:block; color:var(--muted); font-weight:normal; margin-top:4px; }}
.err {{ color:var(--bad); }} th.item {{ width:160px; }}
</style></head><body>
<h1>Menu photo bake-off — Copper Lantern house style</h1>
<p>Generated {e(time.strftime('%Y-%m-%d %H:%M'))}. Costs are list-price estimates for one ~1 MP image; check each provider's dashboard for the real charge.</p>
<div class=wrap><table>
<thead><tr><th>Item</th>{head}</tr></thead>
<tbody>{''.join(rows)}</tbody>
<tfoot><tr><th>Total</th>{''.join(totals)}</tr></tfoot>
</table></div>
</body></html>
"""


def main(argv: Optional[list[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--providers", default="flux,gemini,openai", help="comma list (default: all)")
    ap.add_argument("--items", type=int, default=len(ITEMS), help="first N of the 10 items")
    ap.add_argument("--env", type=Path, default=REPO / ".env", help="where the keys are (default: repo .env)")
    ap.add_argument("--out", type=Path, default=None, help="output folder (default: .image-bakeoff/<timestamp>)")
    ap.add_argument("--dry-run", action="store_true", help="print the plan and cost estimate; call nothing")
    ap.add_argument("--fake", action="store_true", help="use a fake provider (no keys, no network)")
    args = ap.parse_args(argv)

    items = ITEMS[: max(1, min(args.items, len(ITEMS)))]
    if args.fake:
        providers: list[Provider] = [FakeProvider("")]
    else:
        wanted = [p.strip().lower() for p in args.providers.split(",") if p.strip()]
        providers = pick_providers(wanted, read_env(args.env))
    if not providers:
        print("Nothing to run: no provider has a key. Add BFL_API_KEY / GEMINI_API_KEY / OPENAI_API_KEY "
              f"to {args.env}, or try --fake.")
        return 1
    estimate = sum(p.cost_per_image for p in providers) * len(items)
    print(f"{len(items)} items × {', '.join(p.id for p in providers)} — about ${estimate:.2f} in total")
    if args.dry_run:
        for item in items:
            print(f"  {item.id}: {generate_prompt(item)[:110]}…")
        return 0
    out = args.out or DEFAULT_OUT / time.strftime("%Y%m%d-%H%M%S")
    run(providers, items, out)
    print(f"Contact sheet: {out / 'index.html'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
