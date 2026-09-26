# AI menu photos

A paid setup add-on. Staff find items faster with photos, and most restaurants
never take them. This feature fills that gap from the menu editor, in the
client's own house style, so the whole menu looks like one photo shoot.

## What it does

Two manager actions on the item editor (tablet and desktop, **Menu → tap an
item**), under "Upload photo":

- **Generate photo.** Makes 3 pictures (1–4 through the API) from the item's
  description and category plus the client's **house style**. The
  manager taps one and chooses "Use this photo", or "Regenerate".
- **Snap and enhance.** The manager takes or picks a real photo of the dish.
  The provider's edit mode improves the lighting, background and presentation.
  The prompt tells it not to add, remove, replace or rearrange any food, so the
  dish stays recognisable. Pick one of the results the same way.

The chosen picture goes through the ordinary photo pipeline: it is stored like
an upload, shows on the POS and the guests' scan-to-order menu, and syncs to the
portal like any other photo.

**Provenance.** Each photo records where it came from: `original` (a manager's
own upload), `ai_generated` or `ai_enhanced` (`items.photo_source`, and
`photoSource` in the synced item snapshot). Menu management on the POS and the
portal's Menu page show a small **AI** badge on the two AI kinds, with a tooltip
("AI-generated photo" / "Real photo, AI-enhanced").

**Online only, and never in the way.** The buttons need the internet. When the
store is offline they are greyed out with a note ("AI photos need an internet
connection. Everything else works as usual."). Nothing else changes: selling,
the menu, other photos and sync all carry on. The provider is never contacted
at startup or on any selling path, only when a manager presses a button.

### House styles

One per brand (`server/.../aiphotos/HouseStyle.kt`):

| Client | Style |
| --- | --- |
| Copper Lantern (cpr) | Natural pub table of medium-toned wood by a large window: soft daylight, neutral white balance, true-to-life colours, no orange cast or heavy vignette, 45° angle, gentle depth of field, softly blurred pub interior. Reads as an unretouched photo by a food photographer. |
| Sage & Poppy (SP) | Clean bright studio: white backdrop fading to a very pale sage green, even diffused softbox light, neutral white balance, true-to-life colours, straight-on eye level, a crisp soft shadow |

A store can replace the scene line with `image.style=...` in its config.

The first pub style (warm tungsten light, brick and copper) gave every
picture an orange, obviously-AI cast. Both styles now ask for neutral colour.

### What the prompt says about the item

The item's name is never given to the model as a name. Asked for "a photo of
Lantern House Lager", a model prints those words on the glass as garbled
lettering, even when the prompt says "no text". So:

- The subject is the item's **description** ("Crisp, malty lager brewed in
  Montréal").
- When the description doesn't say what the thing is ("Beef, cheddar, bacon,
  onions and house sauce"), it is led by a plain phrase: the name without the
  brand and place words, plus a generic noun from a one-word category
  ("burger", "pinot noir wine", "old fashioned cocktail"). A retail product
  with no description uses its name without the brand, plus the style when the
  name doesn't give it ("hazy IPA 4-pack 16 oz cans", "cola 2 L soda").
- Every prompt also asks for plain, unbranded glassware, bottles and plates
  with no printing, labels or engraving.

The brand and place words per client are `nameWordsToDrop` in `HouseStyle.kt`.

## Providers and cost

Pick one per store with `image.provider`. Default: `off`.

| `image.provider` | API | Default model | Ballpark per image (≈1 MP) | Notes |
| --- | --- | --- | --- | --- |
| `flux` | Black Forest Labs, `POST https://api.bfl.ai/v1/{model}`, then poll `polling_url` | `flux-2-pro` | ~$0.03 to generate, ~$0.045 to edit (billed per megapixel) | Asynchronous. One image per task, so 3 candidates = 3 tasks. The result URL expires after about 10 minutes, so the store downloads it right away. |
| `gemini` | Google Gemini API, `POST https://generativelanguage.googleapis.com/v1beta/interactions` | `gemini-3.1-flash-image` | ~$0.067 per 1K image | Synchronous. One image per call. `gemini-3.1-flash-lite-image` (~$0.034) is a cheaper option through `image.model`. |
| `openai` | OpenAI, `POST https://api.openai.com/v1/images/generations` and `/v1/images/edits` | `gpt-image-1.5` (medium, 1024²) | ~$0.034 to generate, ~$0.04 to edit | Synchronous. Returns all candidates in one call. Edits use `input_fidelity=high`. |

These are list prices as of September 2026. Always check the provider's own
pricing page. A 3-candidate generate costs about 3× the figure above, and the
status endpoint returns the store's estimate. For a 60-item menu with one
regenerate per item, expect roughly $10–25 in total.

Errors come back as codes the POS translates: `image_offline` /
`image_unavailable` (no internet), `image_timeout`, `image_rate_limited`
(honours `Retry-After`), `image_quota` (out of credits), `image_refused` (the
provider's content policy declined), `image_auth` (bad key) and `image_error`.

## Enabling it for a client

The add-on is off until two switches are on, **and** the selected provider has
a key. It is all store config, with no settings UI.

```properties
image.generation=on                 # the add-on flag for this client (default off: no AI buttons at all)
image.provider=flux                 # flux | gemini | openai | off
image.bfl.apiKey=...                # or image.gemini.apiKey / image.openai.apiKey
# optional:
image.model=flux-2-pro              # the provider's model id
image.style=marble counter, soft morning window light   # replaces the brand's scene line
```

- **Tablet.** Put the key in the repo-root `.env` (`BFL_API_KEY=` /
  `GEMINI_API_KEY=` / `OPENAI_API_KEY=`), then run
  `scripts/tablet-ai-photos.sh flux` (or `gemini` / `openai`; add
  `--app sagepoppy` for the SP tablet). The script merges the lines into the
  app's `store.properties` and restarts the POS. Turn the add-on off with
  `scripts/tablet-ai-photos.sh --off`. Confirm with
  `adb logcat -s TabletStore | grep "AI photos:"`.
- **Desktop / docker store.** Set `POS_IMAGE_GENERATION=on`,
  `POS_IMAGE_PROVIDER=flux` and `BFL_API_KEY=...` (optionally `POS_IMAGE_MODEL`
  and `POS_IMAGE_STYLE`), or put the same `image.*` lines in the
  `POS_CONFIG_FILE` properties file. Each env var wins over the file, field by
  field. The startup log shows one line such as `AI photos: on (flux; BFL_API_KEY)`.

**The key stays in the store.** It is never logged (the config and providers
print a masked description only), never written to the outbox (so never synced
to the cloud), and never in an API response. `/ai-photos/status` reports the
provider name and whether it is usable, nothing more. Provider error text is
scrubbed of anything key-shaped before it is logged or returned. Only the
selected provider's key is read, and a FLUX polling URL on any host other than
BFL's is refused, so the key is never sent anywhere else.

## Store API

All of these sit behind the normal session gate. The three that spend money or
change the menu also take a manager PIN, like the photo upload.

```
GET  /ai-photos/status                 {configured, available, provider, model, online, reason, defaultCount, estimatedCostPerImageUsd}
POST /items/{id}/ai-photo/generate     {managerPin, count?}            → {source, candidates:[{id, contentType, dataBase64}], elapsedMs, estimatedCostUsd}
POST /items/{id}/ai-photo/enhance      multipart: photo, managerPin, count?   → same
POST /items/{id}/ai-photo/choose       {managerPin, candidateId}       → {itemId, photoVersion, photoSource}
POST /items/{id}/photo                 multipart: photo, managerPin, source?   → {itemId, photoVersion, photoSource}
```

`count` is 1 to 4 (default 3). The ordinary photo upload takes an optional
`source` (`original`, the default, or `ai_generated` / `ai_enhanced`), so a
picture copied from another store keeps its AI badge.

Candidates are held in memory for 30 minutes and are single-use. A candidate
can only be saved onto the item it was made for.

## Demo steps

1. Add a key to the repo-root `.env`, for example `OPENAI_API_KEY=sk-...`.
2. Tablet: run `scripts/tablet-ai-photos.sh openai`. Desktop: start the store
   with `POS_IMAGE_GENERATION=on POS_IMAGE_PROVIDER=openai` and the key in the
   environment.
3. On the POS, sign in as a manager (demo PIN 1234) and go to **Menu**. Tap
   *Classic Poutine*.
4. Tap **Generate photo** and enter the PIN. After ~10–40 s three candidates
   appear in the pub house style. Tap one, then **Use this photo**. The list
   thumbnail now shows the **AI** badge.
5. Tap *Copper Lantern Burger*, then **Snap and enhance**, then take or pick a
   plain photo of a burger. The result keeps the same burger with better light
   and a pub backdrop. Save it, and it gets the AI badge too.
6. Open the portal's **Menu** page after the next sync (a few seconds). Both
   items carry the AI badge.
7. Offline point: turn off the tablet's Wi-Fi (or unplug the Mac). Open an item.
   Both buttons are greyed out with the "needs an internet connection" note,
   and a sale still rings up normally.

## Bake-off (choosing a provider)

`scripts/image-bakeoff.py` runs 10 varied Copper Lantern items (burger,
poutine, fish & chips, a salad, a dessert, a beer, a cocktail, a wine, a
sandwich, a soup) through every provider that has a key, using the same
endpoints, models and house-style prompt as the store. It writes an HTML
contact sheet (rows are items, columns are providers, each cell with its time
and cost estimate) to the gitignored `.image-bakeoff/<timestamp>/`. Providers
without a key are skipped and named.

```bash
python3 scripts/image-bakeoff.py --dry-run                     # the plan and cost estimate, no calls
python3 scripts/image-bakeoff.py                               # every provider with a key in .env
python3 scripts/image-bakeoff.py --providers flux,openai --items 3
python3 scripts/image-bakeoff.py --providers flux --only lantern-lager,pinot-noir   # just these items
python3 scripts/image-bakeoff.py --env ~/projects/posflutter/.env   # keys from another checkout
python3 scripts/image-bakeoff.py --fake                        # no keys, no network: try the page
open .image-bakeoff/*/index.html
```

A full run with all three providers costs about $1.30. Unit tests (a fake
provider and mocked HTTP): `python3 -m unittest discover -s scripts/tests`.

## Filling a whole menu

`scripts/ai-menu-photos.py` fills a store's menu in one go, through the same
store endpoints the item editor uses. It signs in with the manager PIN
(`--pin`, or `DEMO_MANAGER_PIN`, default 1234). The store holds the key and
builds the prompt, so the script never sees a key.

```bash
python3 scripts/ai-menu-photos.py --dry-run --skip-ai          # the plan and cost estimate, no spend
python3 scripts/ai-menu-photos.py --store http://localhost:8080 --skip-ai
python3 scripts/ai-menu-photos.py --only lantern-lager,poutine --force
python3 scripts/ai-menu-photos.py --only poutine --count 3     # then: --pick poutine=2
python3 scripts/ai-menu-photos.py --copy-to http://<tablet-ip>:8080
open .ai-menu-photos/*/index.html
```

- **Which items.** By default, items with no photo. `--skip-ai` also replaces
  non-AI photos (a manager's upload, the imported stock photos) and leaves AI
  ones alone. `--force` redoes every item. `--only id,id` limits the run to
  those items.
- **Candidates.** `--count 1` (the default) saves the one picture straight
  away. With `--count 2` to `4`, the candidates go on the contact sheet and
  stay on the store for 30 minutes. Save one per item with `--pick id=N,id=N`.
- **Idempotent and resumable.** An item that already has what was asked for
  is skipped, so after an interruption, run the same command again. It stops
  at the first error that would hit every item (out of credits, bad key,
  store offline).
- **Review.** Each run prints one line per item and a total, and writes a
  contact sheet with the chosen pictures to the gitignored
  `.ai-menu-photos/<store>/` folder.
- **Copy to a second store.** `--copy-to URL2` uploads the first store's AI
  photos onto the same items at the second store (the tablet, or the other
  pub). They keep their `ai_generated` / `ai_enhanced` provenance, so both
  menus match and each picture is paid for once. Copying generates nothing.
  Items that aren't on the other menu are skipped. A photo already copied is
  skipped unless `--force` is given.

A 75-item pub menu at one FLUX candidate per item costs about $2.25.

## Verified vs. not yet verified

Every provider call is covered by mocked-HTTP tests: request shape, FLUX
polling, refusals, rate limits, timeouts and key scrubbing. The endpoints,
field names and models follow each provider's documentation as of September
2026. None of it has run against a live API yet, because no keys exist. Check
these first, with the bake-off, once keys are available:

- The exact JSON of a Gemini Interactions reply that contains an image, and
  what a safety block looks like there. The parser also accepts the older
  `generateContent` shape.
- Whether FLUX.2 [pro] accepts a raw base64 `input_image` for edits (the docs
  show URLs and base64 for Kontext).
- The status names for FLUX polling beyond `Ready` / `Pending` /
  `*Moderated` / `Error` / `Failed`.
- OpenAI `input_fidelity` on the default model, and how a `size=auto` edit
  behaves.
- Real per-image cost against the estimates above.
