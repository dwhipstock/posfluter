#!/usr/bin/env python3
"""Populate the fictional demo menu with freely licensed Wikimedia photos.

The importer searches Wikimedia Commons, verifies machine-readable license
metadata, downloads an 800px JPEG/PNG thumbnail, and uploads it through the
store's normal manager-gated photo endpoint. Downloaded image binaries remain
in the ignored local store data; only the generated attribution report is kept.

Usage:
  STORE_URL=http://localhost:8080 python3 scripts/demo-menu-photos.py
"""

from __future__ import annotations

import html
import json
import mimetypes
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


STORE_URL = os.environ.get("STORE_URL", "http://localhost:8080").rstrip("/")
MANAGER_PIN = os.environ.get("DEMO_MANAGER_PIN", "1234")
COMMONS_API = "https://commons.wikimedia.org/w/api.php"
REPORT = Path(__file__).resolve().parents[1] / "docs" / "menu-photo-credits.md"
USER_AGENT = "CopperLanternPOSDemo/1.0 (local demo photo importer)"
SESSION_TOKEN = ""

ALLOWED_LICENSES = {
    "CC0",
    "Public domain",
    "CC BY 2.0",
    "CC BY 2.5",
    "CC BY 3.0",
    "CC BY 4.0",
    "CC BY-SA 2.0",
    "CC BY-SA 2.5",
    "CC BY-SA 3.0",
    "CC BY-SA 4.0",
}

# Fictional product names intentionally search for generic styles/dishes so no
# real producer's packaging or branding is introduced into the demo.
SEARCH_TERMS = {
    "lantern-lager": "pale lager beer pint photograph",
    "amber-ale": "amber ale beer glass photograph",
    "north-ipa": "IPA beer glass photograph",
    "maple-stout": "stout beer pint photograph",
    "wheat-beer": "wheat beer glass photograph",
    "canadian-lager": "lager beer bottle glass photograph",
    "pilsner-can": "pilsner beer can glass photograph",
    "porter-can": "porter dark beer glass photograph",
    "hazy-ipa": "hazy IPA beer glass photograph",
    "saison": "saison beer glass photograph",
    "belgian-blonde": "Belgian blonde ale glass photograph",
    "irish-stout": "dry stout pint photograph",
    "mexican-lager": "lager beer lime glass photograph",
    "dry-cider": "apple cider glass photograph",
    "berry-cider": "berry cider glass photograph",
    "na-lager": "non alcoholic beer glass photograph",
    "hop-water": "sparkling water hops photograph",
    "pinot-noir": "pinot noir red wine glass photograph",
    "cab-merlot": "cabernet merlot red wine glass photograph",
    "malbec": "malbec red wine glass photograph",
    "riesling": "riesling white wine glass photograph",
    "chardonnay": "chardonnay white wine glass photograph",
    "sauvignon-blanc": "sauvignon blanc wine glass photograph",
    "rose": "rose wine glass photograph",
    "sparkling": "sparkling wine flute photograph",
    "icewine": "ice wine dessert wine glass photograph",
    "copper-old-fashioned": "old fashioned cocktail photograph",
    "lantern-mule": "moscow mule cocktail photograph",
    "smoked-caesar": "caesar cocktail drink photograph",
    "maple-sour": "whiskey sour cocktail photograph",
    "elderflower-gin": "gin fizz cocktail photograph",
    "espresso-martini": "espresso martini cocktail photograph",
    "dark-stormy": "dark and stormy cocktail photograph",
    "zero-gimlet": "gimlet cocktail lime photograph",
    "pretzel": "soft pretzel mustard photograph",
    "wings": "chicken wings plate photograph",
    "nachos": "loaded nachos plate photograph",
    "calamari": "fried calamari plate photograph",
    "spinach-dip": "spinach artichoke dip photograph",
    "poutine": "poutine fries cheese curds gravy photograph",
    "lantern-burger": "cheeseburger bacon restaurant photograph",
    "mushroom-burger": "mushroom swiss burger photograph",
    "veggie-burger": "vegetarian burger photograph",
    "club": "grilled chicken club sandwich photograph",
    "reuben": "reuben sandwich photograph",
    "fish-sandwich": "fried fish sandwich photograph",
    "fish-chips": "fish and chips plate photograph",
    "steak-frites": "steak frites plate photograph",
    "shepherd-pie": "shepherd's pie photograph",
    "mac-cheese": "macaroni and cheese photograph",
    "salmon": "glazed salmon plate photograph",
    "chicken-pot-pie": "chicken pot pie photograph",
    "caesar-salad": "caesar salad plate photograph",
    "harvest-salad": "apple squash salad photograph",
    "falafel-bowl": "falafel grain bowl photograph",
    "cauliflower": "roasted cauliflower steak photograph",
    "sticky-pudding": "sticky toffee pudding photograph",
    "cheesecake": "maple cheesecake photograph",
    "brownie": "chocolate brownie ice cream photograph",
    "late-fries": "french fries basket photograph",
    "mini-burgers": "burger sliders plate photograph",
    "grilled-cheese": "grilled cheese sandwich photograph",
    "onion-rings": "onion rings basket photograph",
}

# Hand-reviewed replacements for names where full-text search can rank a place,
# label, painting, or other context above the actual dish. Exact Commons titles
# also make the local demo visually stable across search-index changes.
CURATED_TITLES = {
    "lantern-lager": "Lager beer p.jpg",
    "amber-ale": "Ekers Brewery Amber Ale.jpg",
    "belgian-blonde": "TeKu beerglass with Belgian craft beer.png",
    "mexican-lager": "Lager beer p.jpg",
    "irish-stout": "Murphy's Irish Stout 5.jpg",
    "hop-water": "IKEA elderflower flavor sparkling water.jpg",
    "icewine": "Peller Estates Ice Wine.jpg",
    "smoked-caesar": "Caesar cocktail.jpg",
    "espresso-martini": "Espresso Martini.jpg",
    "nachos": "Beef Nachos 01.jpg",
    "calamari": "Fried calamari.jpg",
    "fish-sandwich": "Fish sandwich.jpg",
    "salmon": "Salmon dish.jpg",
    "cauliflower": "Liat Portal for Foodie Disorder – Whole roasted cauliflower.jpg",
    "late-fries": "French fries.jpg",
}


def request(url: str, data: bytes | None = None, headers: dict[str, str] | None = None):
    merged = {"User-Agent": USER_AGENT, **(headers or {})}
    last_error = None
    for attempt in range(4):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, data=data, headers=merged), timeout=30)
        except urllib.error.HTTPError as error:
            last_error = error
            if error.code != 429 or attempt == 3:
                raise
            retry_after = int(error.headers.get("Retry-After", "3"))
            time.sleep(max(retry_after, 3) * (attempt + 1))
    raise last_error  # pragma: no cover


def get_json(url: str):
    with request(url) as response:
        return json.load(response)


def plain(value: str) -> str:
    return re.sub(r"<[^>]+>", "", html.unescape(value or "")).strip()


def metadata_value(metadata: dict, key: str) -> str:
    return metadata.get(key, {}).get("value", "")


def source_from_page(page: dict):
    info = (page.get("imageinfo") or [{}])[0]
    meta = info.get("extmetadata") or {}
    license_name = plain(metadata_value(meta, "LicenseShortName"))
    mime = info.get("thumbmime") or info.get("mime") or ""
    url = info.get("thumburl") or info.get("url")
    if license_name not in ALLOWED_LICENSES or mime not in {"image/jpeg", "image/png"} or not url:
        return None
    return {
        "title": page["title"].removeprefix("File:"),
        "page": info.get("descriptionurl") or (
            "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(page["title"].replace(" ", "_"))
        ),
        "url": url,
        "mime": mime,
        "license": license_name,
        "license_url": plain(metadata_value(meta, "LicenseUrl")),
        "artist": plain(metadata_value(meta, "Artist")) or "Unknown contributor",
        "credit": plain(metadata_value(meta, "Credit")),
    }


def curated_sources():
    params = urllib.parse.urlencode(
        {
            "action": "query",
            "format": "json",
            "formatversion": "2",
            "titles": "|".join(f"File:{title}" for title in CURATED_TITLES.values()),
            "prop": "imageinfo",
            "iiprop": "url|mime|extmetadata",
            "iiurlwidth": "800",
        }
    )
    pages = get_json(f"{COMMONS_API}?{params}").get("query", {}).get("pages", [])
    return {source["title"]: source for page in pages if (source := source_from_page(page))}


def candidates(term: str):
    params = urllib.parse.urlencode(
        {
            "action": "query",
            "format": "json",
            "formatversion": "2",
            "generator": "search",
            "gsrsearch": f'{term.removesuffix(" photograph")} filetype:bitmap',
            "gsrnamespace": "6",
            "gsrlimit": "20",
            "prop": "imageinfo",
            "iiprop": "url|mime|extmetadata",
            "iiurlwidth": "800",
        }
    )
    pages = get_json(f"{COMMONS_API}?{params}").get("query", {}).get("pages", [])
    for page in pages:
        if source := source_from_page(page):
            yield source


def multipart(fields: dict[str, str], file_field: str, filename: str, mime: str, payload: bytes):
    boundary = f"----copper-lantern-{time.time_ns()}"
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                value.encode(),
                b"\r\n",
            ]
        )
    chunks.extend(
        [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'.encode(),
            f"Content-Type: {mime}\r\n\r\n".encode(),
            payload,
            b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
    )
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def upload(item_id: str, source: dict):
    with request(source["url"]) as response:
        payload = response.read(2 * 1024 * 1024 + 1)
    if len(payload) > 2 * 1024 * 1024:
        raise ValueError("thumbnail exceeds the store's 2 MB limit")
    extension = mimetypes.guess_extension(source["mime"]) or ".jpg"
    body, content_type = multipart(
        {"managerPin": MANAGER_PIN}, "photo", f"{item_id}{extension}", source["mime"], payload
    )
    url = f"{STORE_URL}/items/{urllib.parse.quote(item_id)}/photo"
    with request(url, body, {"Content-Type": content_type, "Authorization": f"Bearer {SESSION_TOKEN}"}) as response:
        if response.status != 201:
            raise RuntimeError(f"upload returned HTTP {response.status}")


def write_report(rows: list[dict]):
    lines = [
        "# Demo menu photo credits",
        "",
        "The local Copper Lantern demo imports these images from Wikimedia Commons. "
        "Each image remains under the license shown below; no endorsement by its creator is implied. "
        "Images are resized by Wikimedia and may be center-cropped by the POS interface.",
        "",
        "| Menu item | Image | Creator/credit | License |",
        "|---|---|---|---|",
    ]
    for row in rows:
        creator = row["credit"] or row["artist"]
        creator = creator.replace("|", "\\|").replace("\n", " ")
        title = row["title"].replace("|", "\\|")
        license_link = f'[{row["license"]}]({row["license_url"]})' if row["license_url"] else row["license"]
        lines.append(f'| {row["item_name"]} | [{title}]({row["page"]}) | {creator} | {license_link} |')
    lines.extend(
        [
            "",
            "Generated by `scripts/demo-menu-photos.py` from Wikimedia Commons machine-readable metadata.",
            "",
        ]
    )
    REPORT.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    global SESSION_TOKEN
    login_body = json.dumps({"pin": MANAGER_PIN}).encode()
    with request(
        f"{STORE_URL}/login", login_body, {"Content-Type": "application/json"}
    ) as response:
        SESSION_TOKEN = json.load(response)["token"]

    items = get_json(f"{STORE_URL}/items")
    curated = curated_sources()
    used: set[str] = set()
    credits: list[dict] = []
    failures: list[str] = []

    for index, item in enumerate(items, 1):
        item_id = item["id"]
        term = SEARCH_TERMS.get(item_id, f'{item["nameEn"]} food drink photograph')
        try:
            curated_title = CURATED_TITLES.get(item_id)
            source = curated.get(curated_title) if curated_title else next(
                (candidate for candidate in candidates(term) if candidate["page"] not in used), None
            )
            if source is None:
                raise RuntimeError("no suitably licensed JPEG/PNG result")
            # A curated image may intentionally be shared by multiple fictional
            # products. Keep each credit row independent when adding item data.
            source = dict(source)
            upload(item_id, source)
            used.add(source["page"])
            source.update(item_id=item_id, item_name=item["nameEn"])
            credits.append(source)
            print(f'[{index:02}/{len(items)}] {item["nameEn"]}: {source["title"]}')
        except (OSError, ValueError, RuntimeError, urllib.error.URLError) as error:
            failures.append(f"{item_id}: {error}")
            print(f'[{index:02}/{len(items)}] {item["nameEn"]}: FAILED ({error})', file=sys.stderr)
        time.sleep(0.65)

    write_report(credits)
    print(f"\nUploaded {len(credits)}/{len(items)} photos; credits: {REPORT}")
    if failures:
        print("Failures:\n  " + "\n  ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
