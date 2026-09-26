"""Unit tests for scripts/ai-menu-photos.py against an in-memory fake store (no network).

Run: python3 -m unittest discover -s scripts/tests
"""

from __future__ import annotations

import base64
import importlib.util
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("ai_menu_photos", REPO / "scripts" / "ai-menu-photos.py")
amp = importlib.util.module_from_spec(_spec)
sys.modules["ai_menu_photos"] = amp
_spec.loader.exec_module(amp)

PIN = "4321"


class FakeStore:
    """Just enough of the store API: login, items, status, generate/choose, photos, upload."""

    def __init__(self, url, items, available=True, fail_code=None):
        self.url = url
        self.items = {i["id"]: dict(i) for i in items}
        self.photos: dict[str, tuple[bytes, str]] = {}
        self.available = available
        self.fail_code = fail_code
        self.candidates: dict[str, tuple[str, bytes]] = {}
        self.calls: list[tuple[str, str]] = []
        self.uploads: list[dict] = []
        self.n = 0

    def handle(self, method, path, headers, body):
        self.calls.append((method, path))
        if path == "/login":
            if json.loads(body)["pin"] != PIN:
                return amp.Response(401, b'{"code":"invalid_pin"}')
            return ok({"token": "tok", "role": "MANAGER"})
        if headers.get("Authorization") != "Bearer tok":
            return amp.Response(401, b"{}")
        if path == "/ai-photos/status":
            return ok({"configured": True, "available": self.available, "provider": "flux", "model": "flux-2-pro",
                       "reason": None if self.available else "image_offline", "estimatedCostPerImageUsd": 0.03})
        if path == "/items?all=true":
            return ok(list(self.items.values()))
        m = re.fullmatch(r"/items/([^/]+)/ai-photo/generate", path)
        if m:
            if self.fail_code:
                return amp.Response(402, json.dumps({"code": self.fail_code, "error": "no credits"}).encode())
            req = json.loads(body)
            assert req["managerPin"] == PIN
            cands = []
            for k in range(req["count"]):
                self.n += 1
                cid = f"c{self.n}"
                img = f"img-{m[1]}-{self.n}".encode()
                self.candidates[cid] = (m[1], img)
                cands.append({"id": cid, "contentType": "image/jpeg", "dataBase64": base64.b64encode(img).decode()})
            return ok({"source": "ai_generated", "provider": "flux", "model": "flux-2-pro",
                       "estimatedCostUsd": 0.03 * req["count"], "candidates": cands})
        m = re.fullmatch(r"/items/([^/]+)/ai-photo/choose", path)
        if m:
            item_id, img = self.candidates.pop(json.loads(body)["candidateId"])
            assert item_id == m[1]
            self._save(m[1], img, "image/jpeg", "ai_generated")
            return ok({"itemId": m[1], "photoSource": "ai_generated"}, 201)
        m = re.fullmatch(r"/photos/([^/]+)", path)
        if m:
            if m[1] not in self.photos:
                return amp.Response(404, b"")
            data, ctype = self.photos[m[1]]
            return amp.Response(200, data, {"Content-Type": ctype})
        m = re.fullmatch(r"/items/([^/]+)/photo", path)
        if m:
            text = body.decode("latin-1")
            fields = dict(re.findall(r'name="(\w+)"\r\n\r\n([^\r]*)\r\n', text))
            assert fields["managerPin"] == PIN
            data = body.split(b"\r\n\r\n")[-1].rsplit(b"\r\n--", 1)[0]
            self.uploads.append({"item": m[1], "source": fields.get("source"), "data": data})
            self._save(m[1], data, "image/jpeg", fields.get("source") or "original")
            return ok({"itemId": m[1], "photoSource": fields.get("source")}, 201)
        return amp.Response(404, b"{}")

    def _save(self, item_id, data, ctype, source):
        self.photos[item_id] = (data, ctype)
        self.items[item_id]["photoVersion"] = (self.items[item_id].get("photoVersion") or 0) + 1
        self.items[item_id]["photoSource"] = source


def ok(obj, status=200):
    return amp.Response(status, json.dumps(obj).encode())


def router(*stores):
    def http(method, url, headers, body):
        for s in stores:
            if url.startswith(s.url):
                return s.handle(method, url[len(s.url):], headers, body)
        raise AssertionError(url)
    return http


def menu():
    return [
        {"id": "lager", "nameEn": "House Lager"},
        {"id": "poutine", "nameEn": "Classic Poutine", "photoVersion": 5, "photoSource": "original"},
        {"id": "burger", "nameEn": "Burger", "photoVersion": 7, "photoSource": "ai_generated"},
    ]


class AiMenuPhotosTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name)
        self.logs: list[str] = []

    def tearDown(self):
        self.tmp.cleanup()

    def run_main(self, store_or_http, *args):
        http = store_or_http if callable(store_or_http) else router(store_or_http)
        self.logs = []
        return amp.main(["--store", "http://a:8080", "--pin", PIN, "--out", str(self.out), *args],
                        http=http, log=self.logs.append)

    def generated(self, store):
        return [p for m, p in store.calls if p.endswith("/generate")]

    def test_dry_run_plans_and_spends_nothing(self):
        store = FakeStore("http://a:8080", menu())
        self.assertEqual(0, self.run_main(store, "--dry-run", "--skip-ai"))
        self.assertEqual([], self.generated(store))
        self.assertIn("2 to do, 1 skipped, 1 candidate(s) each — about $0.06", self.logs[0])
        self.assertTrue(any("skip burger: already has an AI photo" in l for l in self.logs))
        self.assertNotIn(PIN, "\n".join(self.logs))

    def test_which_items_default_skip_ai_force_only(self):
        items = menu()
        self.assertEqual(["lager"], [i["id"] for i in amp.wanted(items, [], False, False)[0]])
        self.assertEqual(["lager", "poutine"], [i["id"] for i in amp.wanted(items, [], True, False)[0]])
        self.assertEqual(["lager", "poutine", "burger"], [i["id"] for i in amp.wanted(items, [], False, True)[0]])
        self.assertEqual(["burger"], [i["id"] for i in amp.wanted(items, ["burger"], False, True)[0]])
        self.assertEqual([], amp.wanted(items, ["burger"], True, False)[0])

    def test_count_one_saves_the_first_and_a_rerun_is_a_no_op(self):
        store = FakeStore("http://a:8080", menu())
        self.assertEqual(0, self.run_main(store, "--skip-ai"))
        self.assertEqual(["/items/lager/ai-photo/generate", "/items/poutine/ai-photo/generate"], self.generated(store))
        self.assertEqual("ai_generated", store.items["poutine"]["photoSource"])
        folder = self.out / "a-8080"
        self.assertEqual(store.photos["lager"][0], (folder / "lager.jpg").read_bytes())
        sheet = (folder / "index.html").read_text()
        self.assertIn("<title>AI menu photos</title>", sheet)
        self.assertIn("lager.jpg", sheet)
        self.assertTrue(any("2 saved, 1 skipped, 0 failed, ~$0.06" in l for l in self.logs))
        # resumable: everything asked for is done, so nothing more is generated
        self.assertEqual(0, self.run_main(store, "--skip-ai"))
        self.assertEqual(2, len(self.generated(store)))

    def test_out_of_credits_stops_the_run(self):
        store = FakeStore("http://a:8080", menu(), fail_code="image_quota")
        self.assertEqual(1, self.run_main(store, "--force"))
        self.assertEqual(1, len(self.generated(store)))
        self.assertTrue(any("Stopping: image_quota" in l for l in self.logs))

    def test_offline_store_spends_nothing(self):
        store = FakeStore("http://a:8080", menu(), available=False)
        self.assertEqual(1, self.run_main(store))
        self.assertEqual([], self.generated(store))
        self.assertTrue(any("not available on this store (image_offline)" in l for l in self.logs))

    def test_several_candidates_then_pick(self):
        store = FakeStore("http://a:8080", menu())
        self.assertEqual(0, self.run_main(store, "--only", "lager", "--count", "3"))
        self.assertNotIn("lager", store.photos, "nothing saved until picked")
        folder = self.out / "a-8080"
        self.assertTrue((folder / "lager--3.jpg").exists())
        self.assertIn("lager--2.jpg", (folder / "index.html").read_text())
        self.assertEqual(0, self.run_main(store, "--pick", "lager=2"))
        self.assertEqual(b"img-lager-2", store.photos["lager"][0])
        self.assertNotIn("pending", json.loads((folder / "state.json").read_text())["items"]["lager"])
        self.assertEqual(1, self.run_main(store, "--pick", "lager=2"), "a pick is single-use")

    def test_unknown_only_id_is_refused(self):
        store = FakeStore("http://a:8080", menu())
        self.assertEqual(2, self.run_main(store, "--only", "nope"))
        self.assertEqual([], self.generated(store))

    def test_copy_to_keeps_provenance_and_is_idempotent(self):
        a = FakeStore("http://a:8080", menu())
        a.photos["burger"] = (b"burger-ai", "image/jpeg")
        a.photos["poutine"] = (b"poutine-stock", "image/jpeg")
        b = FakeStore("http://b:8080", [{"id": "burger", "nameEn": "Burger"}, {"id": "lager", "nameEn": "House Lager"}])
        http = router(a, b)
        self.assertEqual(0, self.run_main(http, "--copy-to", "http://b:8080"))
        self.assertEqual([{"item": "burger", "source": "ai_generated", "data": b"burger-ai"}], b.uploads)
        self.assertEqual("ai_generated", b.items["burger"]["photoSource"])
        self.assertEqual([], self.generated(a), "copying generates nothing")
        # the original (non-AI) photo is not copied; a rerun copies nothing new
        self.assertEqual(0, self.run_main(http, "--copy-to", "http://b:8080"))
        self.assertEqual(1, len(b.uploads))
        self.assertTrue(any("already copied" in l for l in self.logs))
        self.assertEqual(0, self.run_main(http, "--copy-to", "http://b:8080", "--force"))
        self.assertEqual(2, len(b.uploads))

    def test_bad_pin_and_no_secrets_in_repr(self):
        store = FakeStore("http://a:8080", menu())
        self.logs = []
        code = amp.main(["--store", "http://a:8080", "--pin", "0000", "--out", str(self.out)],
                        http=router(store), log=self.logs.append)
        self.assertEqual(1, code)
        self.assertIn("Cannot sign in", self.logs[0])
        self.assertNotIn(PIN, repr(amp.Store("http://a:8080", PIN)))


if __name__ == "__main__":
    unittest.main()
