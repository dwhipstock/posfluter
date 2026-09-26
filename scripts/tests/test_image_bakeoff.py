"""Unit tests for scripts/image-bakeoff.py (no keys, no network).

Run: python3 -m unittest discover -s scripts/tests
"""

from __future__ import annotations

import base64
import importlib.util
import io
import json
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("image_bakeoff", REPO / "scripts" / "image-bakeoff.py")
bake = importlib.util.module_from_spec(_spec)
sys.modules["image_bakeoff"] = bake  # dataclasses look their module up here
_spec.loader.exec_module(bake)

JPEG = b"\xff\xd8fake-jpeg\xff\xd9"


class Recorder:
    """Scripted HTTP: handler(method, url, headers, body) -> Response; keeps every call."""

    def __init__(self, handler):
        self.handler = handler
        self.calls = []

    def __call__(self, method, url, headers, body):
        self.calls.append((method, url, dict(headers), body))
        return self.handler(method, url, headers, body)


def ok(obj, status=200):
    return bake.Response(status, json.dumps(obj).encode())


class BakeoffTest(unittest.TestCase):
    def test_ten_varied_items(self):
        self.assertEqual(10, len(bake.ITEMS))
        names = " ".join(i.name.lower() for i in bake.ITEMS)
        for word in ["burger", "poutine", "fish and chips", "salad", "cheesecake", "lager",
                     "old fashioned", "pinot noir", "reuben", "soup"]:
            self.assertIn(word, names)

    def test_house_style_matches_the_store(self):
        kotlin = (REPO / "server/src/main/kotlin/dev/dwhipstock/pos/aiphotos/HouseStyle.kt").read_text()
        # join Kotlin's `"a " +` / `"b"` line-split concatenations back into one literal
        joined = re.sub(r'"\s*\+\s*\n\s*"', "", kotlin)
        for s in (bake.CPR_SHOT, bake.CPR_SCENE, bake.CLEAN):
            self.assertIn(s, joined)
        for w in bake.CPR_DROP_WORDS:
            self.assertIn(f'"{w}"', kotlin)
        for c in bake.COURSES:
            self.assertIn(f'"{c}"', kotlin)
        p = bake.generate_prompt(bake.ITEMS[0])
        self.assertIn("The subject: burger. Beef, cheddar", p)
        self.assertNotIn("Lantern", p)
        self.assertIn("45-degree", p)

    def test_prompt_leads_with_the_description_not_the_name(self):
        lager = bake.Item("x", "Lantern House Lager", "Crisp, malty lager brewed in Montréal.", "Beer & Cider")
        self.assertEqual(
            "A professional food and drink menu photograph for a pub. "
            "The subject: Crisp, malty lager brewed in Montréal. Menu category: Beer & Cider. "
            f"House style, shared by every photo on this menu: {bake.CPR_SCENE}. "
            "One single serving is the only subject, centred and filling most of the frame, realistic, "
            "appetising and true to how it is actually served. "
            "Plain, unbranded glassware, bottles and plates with no printing, labels or engraving. "
            "No text, no captions, no logos or readable brand names, no watermark, "
            "no people or hands, no cutlery clutter.",
            bake.generate_prompt(lager))
        for item in bake.ITEMS:
            p = bake.generate_prompt(item)
            for brand in ("Copper", "Lantern"):
                self.assertNotIn(brand, p, item.id)

    def test_what_it_is_matches_the_store(self):
        """Same cases as HouseStyleTest.whatItIsWhenTheDescriptionDoesNotSay (keeps the two in step)."""
        I = bake.Item
        cases = [
            (I("a", "Maple Cheesecake", "Maple cheesecake with toasted pecans.", "Desserts"),
             "Maple cheesecake with toasted pecans"),
            (I("a", "Eastern Townships Pinot Noir", "Light red with cherry and spice.", "Wine"),
             "pinot noir wine. Light red with cherry and spice"),
            (I("a", "Copper Old Fashioned", "Canadian whisky, maple, bitters and orange.", "Cocktails"),
             "old fashioned cocktail. Canadian whisky, maple, bitters and orange"),
            (I("a", "Classic Poutine", "Fries, cheese curds and savoury gravy.", "Starters"),
             "classic poutine. Fries, cheese curds and savoury gravy"),
            (I("a", "Avocado Cucumber Maki", "Six vegetarian pieces.", "Sushi & Sake"),
             "avocado cucumber maki. Six vegetarian pieces"),
            (I("a", "North Trail IPA", "", "Beer & Cider"), "IPA"),
            (I("a", "Hazy Hills IPA 4-pack 16 oz cans", "", "Beer", "Hazy Hills", "Hazy IPA"),
             "hazy IPA 4-pack 16 oz cans"),
            (I("a", "Silver Coast Vodka 750 ml", "", "Spirits", "Silver Coast", "Vodka"), "vodka 750 ml"),
            (I("a", "Cola 2 L", "", "Mixers & Soda", "House", "Soda"), "cola 2 L soda"),
            (I("a", "Copper Lantern", "", "Cocktails"), "cocktail"),
        ]
        for item, want in cases:
            self.assertEqual(want, bake.subject(item), item.name)

    def test_only_runs_the_named_items(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "sheet"
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = bake.main(["--fake", "--out", str(out), "--only", "pinot-noir,lantern-lager"])
            self.assertEqual(0, code)
            page = (out / "index.html").read_text()
            self.assertIn("pinot-noir--fake.png", page)
            self.assertIn("lantern-lager--fake.png", page)
            self.assertEqual(2, page.count("<tr><th class=item>"))
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = bake.main(["--fake", "--out", str(out), "--only", "nope"])
        self.assertEqual(2, code)
        self.assertIn("Unknown item id(s): nope", buf.getvalue())

    def test_env_file_parsing(self):
        with tempfile.TemporaryDirectory() as d:
            env = Path(d) / ".env"
            env.write_text("# keys\nexport OPENAI_API_KEY='sk-abc'\nBFL_API_KEY=\nGEMINI_API_KEY=\"AIza1\"\nnoise\n")
            got = bake.read_env(env)
        self.assertEqual({"OPENAI_API_KEY": "sk-abc", "GEMINI_API_KEY": "AIza1"}, got)
        self.assertEqual({}, bake.read_env(Path("/nonexistent/.env")))

    def test_missing_keys_are_skipped_and_named(self):
        logged = []
        chosen = bake.pick_providers(["flux", "gemini", "openai", "dalle"], {"OPENAI_API_KEY": "sk-x"},
                                     log=logged.append)
        self.assertEqual(["openai"], [p.id for p in chosen])
        self.assertTrue(any("skip flux: no BFL_API_KEY" in m for m in logged))
        self.assertTrue(any("skip gemini: no GEMINI_API_KEY" in m for m in logged))
        self.assertTrue(any("skip dalle: unknown" in m for m in logged))
        self.assertNotIn("sk-x", repr(chosen[0]))

    def test_no_keys_exits_cleanly(self):
        with tempfile.TemporaryDirectory() as d:
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = bake.main(["--env", str(Path(d) / ".env")])
        self.assertEqual(1, code)
        self.assertIn("Nothing to run", buf.getvalue())

    def test_fake_run_writes_a_contact_sheet(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "sheet"
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = bake.main(["--fake", "--out", str(out)])
            self.assertEqual(0, code)
            page = (out / "index.html").read_text()
            self.assertIn("<title>Menu photo bake-off</title>", page)
            for item in bake.ITEMS:
                self.assertIn(f"{item.id}--fake.png", page)
                self.assertTrue((out / f"{item.id}--fake.png").read_bytes().startswith(b"\x89PNG"))
            self.assertIn("10/10 ok", page)
            results = json.loads((out / "results.json").read_text())
            self.assertEqual(10, len(results))

    def test_rows_items_columns_providers_with_errors_shown(self):
        class Boom(bake.Provider):
            id, label, model = "boom", "Boom", "b-1"

            def generate(self, prompt):
                raise bake.ProviderError("boom refused")

        with tempfile.TemporaryDirectory() as d:
            results = bake.run([bake.FakeProvider(""), Boom("k")], bake.ITEMS[:2], Path(d), log=lambda *_: None)
            page = (Path(d) / "index.html").read_text()
        self.assertEqual(4, len(results))
        self.assertIn("boom refused", page)
        self.assertEqual(2, page.count("<tr><th class=item>"))
        self.assertIn("<th>Boom<small>", page)

    def test_flux_submits_polls_and_downloads(self):
        polls = []

        def handler(method, url, headers, body):
            if method == "POST":
                return ok({"id": "t1", "polling_url": "https://api.us.bfl.ai/v1/get_result?id=t1"})
            if "get_result" in url:
                polls.append(url)
                return ok({"status": "Pending"} if len(polls) < 2 else
                          {"status": "Ready", "result": {"sample": "https://delivery-us1.bfl.ai/x.jpg"}})
            return bake.Response(200, JPEG)

        http = Recorder(handler)
        p = bake.FluxProvider("bfl-key", http, sleep=lambda s: None)
        img, ext = p.generate("a burger")
        self.assertEqual(JPEG, img)
        method, url, headers, body = http.calls[0]
        self.assertEqual("https://api.bfl.ai/v1/flux-2-pro", url)
        self.assertEqual("bfl-key", headers["x-key"])
        self.assertEqual(1024, json.loads(body)["width"])
        self.assertEqual(2, len(polls))
        self.assertEqual({}, http.calls[-1][2], "the signed image URL gets no key")

    def test_flux_moderation_is_a_refusal(self):
        http = Recorder(lambda m, u, h, b: ok({"id": "t", "polling_url": "https://api.bfl.ai/v1/get_result?id=t"})
                        if m == "POST" else ok({"status": "Content Moderated"}))
        with self.assertRaises(bake.ProviderError) as ctx:
            bake.FluxProvider("k", http, sleep=lambda s: None).generate("x")
        self.assertIn("refused", str(ctx.exception))

    def test_gemini_and_openai_request_shapes(self):
        b64 = base64.b64encode(JPEG).decode()
        g = Recorder(lambda *a: ok({"status": "completed", "steps": [
            {"type": "model_output", "content": [{"type": "image", "mime_type": "image/jpeg", "data": b64}]}]}))
        img, _ = bake.GeminiProvider("AIzaKEY", g).generate("p")
        self.assertEqual(JPEG, img)
        _, url, headers, body = g.calls[0]
        self.assertTrue(url.endswith("/v1beta/interactions"))
        self.assertEqual("AIzaKEY", headers["x-goog-api-key"])
        self.assertNotIn("AIzaKEY", url)
        self.assertEqual("1K", json.loads(body)["response_format"]["image_size"])

        o = Recorder(lambda *a: ok({"data": [{"b64_json": b64}]}))
        img, _ = bake.OpenAIProvider("sk-KEY", o).generate("p")
        self.assertEqual(JPEG, img)
        _, url, headers, body = o.calls[0]
        self.assertTrue(url.endswith("/v1/images/generations"))
        self.assertEqual("Bearer sk-KEY", headers["Authorization"])
        self.assertEqual("gpt-image-1.5", json.loads(body)["model"])

    def test_openai_refusal_and_rate_limit(self):
        refused = Recorder(lambda *a: ok({"error": {"code": "moderation_blocked", "message": "no"}}, 400))
        with self.assertRaises(bake.ProviderError) as ctx:
            bake.OpenAIProvider("sk", refused).generate("p")
        self.assertIn("refused", str(ctx.exception))
        limited = Recorder(lambda *a: ok({"error": {"message": "slow down"}}, 429))
        with self.assertRaises(bake.ProviderError) as ctx:
            bake.OpenAIProvider("sk", limited).generate("p")
        self.assertIn("rate limited", str(ctx.exception))

    def test_dry_run_calls_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            env = Path(d) / ".env"
            env.write_text("OPENAI_API_KEY=sk-dry\n")
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = bake.main(["--env", str(env), "--dry-run", "--items", "2"])
        self.assertEqual(0, code)
        self.assertIn("2 items × openai", buf.getvalue())
        self.assertNotIn("sk-dry", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
