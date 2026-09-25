#!/usr/bin/env python3
"""Populate the fictional demo menu with freely licensed Wikimedia photos.

The importer searches Wikimedia Commons, verifies machine-readable license
metadata, downloads an 800px JPEG/PNG thumbnail, and uploads it through the
store's normal manager-gated photo endpoint. Downloaded image binaries remain
in the ignored local store data; only the generated attribution report is kept.

Items that already have a photo are skipped, so re-runs only fill gaps; pass
--force to replace every photo with the curated set.

Usage:
  STORE_URL=http://localhost:8080 python3 scripts/demo-menu-photos.py [--force]
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
    # Plateau-only: sushi & sake, then the Plateau specials.
    "salmon-maki": "salmon maki sushi photograph",
    "spicy-tuna-maki": "spicy tuna roll photograph",
    "avocado-maki": "avocado maki photograph",
    "salmon-nigiri": "salmon nigiri photograph",
    "tuna-nigiri": "tuna nigiri photograph",
    "scallop-nigiri": "scallop nigiri photograph",
    "junmai-sake": "sake tokkuri cup photograph",
    "sparkling-sake": "sparkling sake glass photograph",
    "smoked-meat-poutine": "poutine meat gravy photograph",
    "maple-miso-bowl": "salmon rice bowl photograph",
    "bagel-board": "bagel lox cream cheese photograph",
    "yuzu-sour": "sour cocktail egg white photograph",
}

# Hand-reviewed exact Commons titles for every menu item. Each was eyeballed:
# a generic dish or drink with no brand label, logo, packaging, signage or
# faces, and no business named in the file title. Pinning every item keeps the
# demo stable across search-index changes; search is only a fallback for items
# added to the menu later.
CURATED_TITLES = {
    "lantern-lager": "Pilstulpe.jpg",
    "amber-ale": "British dimpled glass pint jug with ale.jpg",
    "north-ipa": "IPA in a pint glass.jpeg",
    "maple-stout": "Stout.jpg",
    "wheat-beer": "Weizenbier.jpg",
    "canadian-lager": "Lager.jpg",
    "pilsner-can": "Vaso de cerveza.jpg",
    "porter-can": "Porter (141611127).jpeg",
    "hazy-ipa": "A glass of cloudy unfiltered beer- fascinating! (27798855532).jpg",
    "saison": "Beer on the outdoor table - New Orleans May 2021.jpg",
    "belgian-blonde": "Belgian beer glass.jpg",
    "irish-stout": "Dark beer and light beer.jpg",
    "mexican-lager": "Clara (Cerveza con limón) - Frutos secos.jpg",
    "dry-cider": "Apple wine in a glass.jpg",
    "berry-cider": "Raspberryade.jpg",
    "na-lager": "Non-alcoholic beer in Haukilahti.jpg",
    "hop-water": "Ginger beer & lime (5671445169).jpg",
    "pinot-noir": "Pommard glass p1150456.jpg",
    "cab-merlot": "Glass of red wine.jpg",
    "malbec": "Red wine in glass.jpg",
    "riesling": "Glass of white wine.jpg",
    "chardonnay": "White Wine Glas.jpg",
    "sauvignon-blanc": "Unidentified white wine in glass.jpg",
    "rose": "Wine glass closeup.jpg",
    "sparkling": "Sparkling wine in a flute.jpg",
    "icewine": "A glass of Tokaji.jpg",
    "copper-old-fashioned": "Old Fashioned.jpg",
    "lantern-mule": "Moscow mule Cocktail im Kupferbecher.jpg",
    "smoked-caesar": "Bloody Caesar.jpg",
    "maple-sour": "Whiskey Sour.jpg",
    "elderflower-gin": "Ramos Fizz.jpg",
    "espresso-martini": "Espresso Martini.jpg",
    "dark-stormy": "Dark n Stormy.jpg",
    "zero-gimlet": "Gimlet cocktail.jpg",
    "pretzel": "Broccoli cheddar soup with a pretzel and mustard.jpg",
    "wings": "Buffalo wings-01.jpg",
    "nachos": "Beef Nachos 01.jpg",
    "calamari": "Fried calamari.jpg",
    "spinach-dip": "Spinach & artichoke dip.jpg",
    "poutine": "Poutine! (422692736).jpg",
    "lantern-burger": "Bacon Cheeseburger on plate.JPG",
    "mushroom-burger": "Mmm...Swiss cheese burger with mushrooms (5305019054).jpg",
    "veggie-burger": "Veggie burger (1).jpg",
    "club": "Club-sandwich.jpg",
    "reuben": "ReubenSandwichHalves.jpg",
    "fish-sandwich": "Fish sandwich.jpg",
    "fish-chips": "Fish and chips in Helsinki.jpg",
    "steak-frites": "Entrecôte.JPG",
    "shepherd-pie": "ShepherdsPie.jpg",
    "mac-cheese": "Original Mac n Cheese .jpg",
    "salmon": "Salmon dish.jpg",
    "chicken-pot-pie": "Chicken Pot Pie.jpg",
    "caesar-salad": "Caesar salad (2).jpg",
    "harvest-salad": "Rocket lettuce, Butternut squash, Beetroot, Green beans, whipped cream salad.jpg",
    "falafel-bowl": "Bowl of falafel.jpg",
    "cauliflower": "Plated roasted cauliflower 10.jpg",
    "sticky-pudding": "StickyToffeePudding.jpg",
    "cheesecake": "Plain cheesecake slice.jpg",
    "brownie": "Brownie with ice cream.jpg",
    "late-fries": "French fries.jpg",
    "mini-burgers": "Sliders and French fries.jpg",
    "grilled-cheese": "Grilled cheese sandwich with roasted tomato soup.jpg",
    "onion-rings": "OnionRings.JPG",
    # Plateau-only
    "salmon-maki": "Salmon sushi.jpg",
    "spicy-tuna-maki": "Crunchy Spicy Tuna, big eye tuna, shiso panko, togarashi ($21) (32807112472).jpg",
    "avocado-maki": "Avocado maki rolls - Makis de aguacate (5123466252).jpg",
    "salmon-nigiri": "Salmon nigiri sushi.jpg",
    "tuna-nigiri": "Tuna nigiri.png",
    "scallop-nigiri": "Hokkaido hotatekai nigiri.jpg",
    "junmai-sake": "Tokkuri sake and takowasa.JPG",
    "sparkling-sake": "Verre Champagne.jpg",
    "smoked-meat-poutine": "Poutine with pulled pork makes everything better (8515637447).jpg",
    "maple-miso-bowl": "Poke Bowl in Loviisa.jpg",
    "bagel-board": "Montréal bagel with lox.jpg",
    "yuzu-sour": "Pisco Sour 2.jpg",
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
    # Commons caps a titles query at 50 and long lists overflow the URL, so
    # look the curated titles up in small batches.
    titles = sorted(set(CURATED_TITLES.values()))
    sources = {}
    for start in range(0, len(titles), 15):
        params = urllib.parse.urlencode(
            {
                "action": "query",
                "format": "json",
                "formatversion": "2",
                "titles": "|".join(f"File:{title}" for title in titles[start:start + 15]),
                "prop": "imageinfo",
                "iiprop": "url|mime|extmetadata",
                "iiurlwidth": "800",
            }
        )
        pages = get_json(f"{COMMONS_API}?{params}").get("query", {}).get("pages", [])
        sources.update({source["title"]: source for page in pages if (source := source_from_page(page))})
    return sources


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


def credit_line(row: dict) -> str:
    creator = row["credit"] or row["artist"]
    creator = creator.replace("|", "\\|").replace("\n", " ")
    title = row["title"].replace("|", "\\|")
    license_link = f'[{row["license"]}]({row["license_url"]})' if row["license_url"] else row["license"]
    return f'| {row["item_name"]} | [{title}]({row["page"]}) | {creator} | {license_link} |'


def existing_credit_lines() -> dict[str, str]:
    """Credit rows already in the report, keyed by menu item name."""
    if not REPORT.exists():
        return {}
    rows = {}
    for line in REPORT.read_text(encoding="utf-8").splitlines():
        if match := re.match(r"\| (.+?) \| \[", line):
            rows[match.group(1)] = line
    return rows


def write_report(lines_by_item: dict[str, str]):
    lines = [
        "# Demo menu photo credits",
        "",
        "The local Copper Lantern demo imports these images from Wikimedia Commons. "
        "Each image remains under the license shown below; no endorsement by its creator is implied. "
        "Images are resized by Wikimedia and may be center-cropped by the POS interface.",
        "",
        "| Menu item | Image | Creator/credit | License |",
        "|---|---|---|---|",
        *lines_by_item.values(),
        "",
        "Generated by `scripts/demo-menu-photos.py` from Wikimedia Commons machine-readable metadata.",
        "",
    ]
    REPORT.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    global SESSION_TOKEN
    force = "--force" in sys.argv[1:]
    login_body = json.dumps({"pin": MANAGER_PIN}).encode()
    with request(
        f"{STORE_URL}/login", login_body, {"Content-Type": "application/json"}
    ) as response:
        SESSION_TOKEN = json.load(response)["token"]

    items = get_json(f"{STORE_URL}/items")
    curated = curated_sources()
    used: set[str] = set()
    previous = existing_credit_lines()
    # This store's menu first, in menu order; rows for items only on another
    # store's menu are kept after it so one report covers every store.
    report: dict[str, str] = {}
    uploaded = skipped = 0
    failures: list[str] = []

    for index, item in enumerate(items, 1):
        item_id = item["id"]
        if item.get("photoVersion") is not None and not force:
            skipped += 1
            if item["nameEn"] in previous:
                report[item["nameEn"]] = previous[item["nameEn"]]
            print(f'[{index:02}/{len(items)}] {item["nameEn"]}: already has a photo, skipped (--force replaces it)')
            continue
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
            report[item["nameEn"]] = credit_line(source)
            uploaded += 1
            print(f'[{index:02}/{len(items)}] {item["nameEn"]}: {source["title"]}')
        except (OSError, ValueError, RuntimeError, urllib.error.URLError) as error:
            failures.append(f"{item_id}: {error}")
            print(f'[{index:02}/{len(items)}] {item["nameEn"]}: FAILED ({error})', file=sys.stderr)
        time.sleep(0.65)

    for name, line in previous.items():
        report.setdefault(name, line)
    write_report(report)
    print(f"\nUploaded {uploaded}, skipped {skipped} that had photos, of {len(items)} items; credits: {REPORT}")
    if failures:
        print("Failures:\n  " + "\n  ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
