# AI menu setup

A manager never has to type a menu in. From **Manage menu** (the sparkles
button, managers only), on the Android tablet and the Windows build:

- **Menu from photos**: take or pick up to 6 photos of a paper menu. The AI
  proposes the categories, items, sizes, prices and descriptions to add (and a
  price update for anything already on the menu at a different price).
- **Chat**: type, or dictate with the keyboard's mic, things like
  "add Caesar salad $14 under starters", "raise all burgers by a dollar",
  "rename the Reuben to Pastrami on Rye", "86 the salmon",
  "move desserts before drinks".

It is an add-on, **off by default**, online only, and manager-PIN gated.

## Always a preview first

The model never changes anything. Its reply must be one JSON object
`{summary, ops: [...]}` with these ops: `add_category`, `add_item`,
`update_item` (names, descriptions, category, on/off sale, prices per size),
`remove_item`, `rename_category`, `reorder_categories`. The store validates
it against the live menu (`MenuChangeSetParser`): an unknown op, an unknown
item / size / category id, a price that is not a whole number of minor units
of the store's currency (cents), or a missing name drops that change into a
"skipped" count; a reply that is not JSON at all is an error. Nothing is kept
from the model but the validated change set.

The tablet shows the result grouped as **New / Changed (old → new) /
Removed**, everything ticked. The manager unticks what they don't want and
taps **Apply**. Ticking a new item ticks the new category it needs.

Apply runs the ticked changes in one transaction (all or nothing) through
`CatalogOps`, the same code as the menu editor's routes: same validation,
same outbox events, so the portal gets them exactly like a hand edit
(store → portal, one way; nothing writes the portal directly).

Languages: the catalog has English and French names. A bilingual store (the
Copper Lantern pubs) gets both filled when the menu or the manager gives both; when
only one is given the other is left blank in the preview and filled with the
same text on Apply (the menu editor requires both). New items get a
two-letter tile badge from their name.

After an import that created items, if AI photos are on, the tablet offers
**Generate photos for the new items** (the AI photos flow, one item at a time).

## History and revert

Every Apply is saved as a change set (`menu_change_sets` /
`menu_change_rows`, migration 051): who applied it, the approving manager,
when, photos or chat, and the before / after state of every item, size and
category it touched. **AI history** in the dialog lists the last 20 with a
**Revert** button:

- Revert puts every before state back, newest first, through the same
  `CatalogOps` (so it syncs like any edit) and marks the set reverted.
- Removals are soft deletes (`items.deleted_at`): the item is hidden, old
  sales and receipts keep it, and reverting un-deletes it with its old on/off
  state. Categories have no archive flag; the AI never removes one, and
  reverting a category the AI created removes it only once it is empty (the
  editor's own rule). The store has no modifiers yet.
- If anything the set touched changed afterwards (a later AI update or a hand
  edit), Revert first answers `409 menu_ai_revert_conflict` with the names, and
  the tablet asks "Revert anyway?" before overwriting.

## In the manager portal ("Ask AI" on the Menu page)

The hosted portal has the same chat (typed or spoken) for one store at a time,
owners and managers only: `POST /v1/menu-ai/chat`, `/chat/voice`, `/apply`,
`/revert/{applyId}` (cloud/API.md "Menu AI"). The model, prompt and safety rails
are the store's, PORTED into `cloud/api/.../menuai/` (two separate Gradle builds;
`AiGuardParityTest` fails if the copied rules drift from `AiGuard.kt` /
`MenuChangeSet.kt` here, so change both). Apply goes through the portal's own
menu edits (cloud HLC stamps, menu feed), so the store receives AI changes made
in the portal exactly like hand edits there. Stricter confirm than the tablet:
more than 5 changes, any removal, or a price moved by half or more. Undo is
one tap. Off unless the cloud has `MENU_AI_GEMINI_API_KEY`.

## Turning it on

| Setting | Values |
|---|---|
| `menu.ai` / `POS_MENU_AI` | `on` / `off` (default off: no button) |
| `menu.ai.provider` / `POS_MENU_AI_PROVIDER` | `gemini` / `openai` / `anthropic` / `off` |
| key | `menu.ai.gemini.apiKey` / `menu.ai.openai.apiKey` / `menu.ai.anthropic.apiKey`, or `GEMINI_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` |
| `menu.ai.model` / `POS_MENU_AI_MODEL` | optional model id |
| `menu.ai.layoutModel` / `POS_MENU_AI_LAYOUT_MODEL` | optional: room from picture, object from photo, typed floor edits |
| `menu.ai.voiceModel` / `POS_MENU_AI_VOICE_MODEL` | optional: spoken requests (menu and floor plan) |

Which model answers what (Gemini):

| Request | Model |
|---|---|
| typed menu chat | `menu.ai.model`, else `gemini-3.5-flash-lite` |
| spoken menu chat | `menu.ai.voiceModel`, else `menu.ai.model`, else `gemini-3.5-flash-lite` |
| typed floor edit, room from picture | `menu.ai.layoutModel`, else `gemini-3.5-flash-lite` (medium thinking) |
| spoken floor edit | `menu.ai.voiceModel`, else `menu.ai.layoutModel`, else `gemini-3.5-flash-lite` (medium thinking) |

Voice stays on the fast lite model: measured live (Oct 1), `gemini-3.5-flash` took 11–40 s for a spoken menu edit and over 120 s for a spoken floor edit, while lite (with the verbatim-transcript instructions) got 6 of 6 German clips right, mostly in 2–3 s.

So an explicit `menu.ai.model` / `menu.ai.layoutModel` keeps applying to
voice as it always did; set `menu.ai.voiceModel` to give voice its own. With
no override, voice gets the stronger flash model: in live tests the lite model
missed about 2 in 5 German spoken floor edits (`no_change`), flash got them
all (it is slower: roughly 6–30 s). A busy (503) or out-of-quota (429) flash
call falls back to lite.

Default models: Gemini `gemini-3.5-flash-lite` (fast: 2–6 s a request; Interactions API, JSON output; a busy
503 is retried once, then tried on `gemini-3.5-flash`),
OpenAI `gpt-5.4-mini` (Chat Completions, `json_object`), Anthropic
`claude-opus-5` (Messages API, low effort). All raw HTTP through the AI photos'
transport, so nothing new goes into the APK.

- **Android tablet**: `scripts/tablet-ai-menu.sh gemini` (or `openai`,
  `anthropic`) reads the key from the repo-root `.env`, writes the `menu.ai.*`
  lines into the app's `store.properties` and restarts the POS.
  `scripts/tablet-ai-menu.sh --off` turns it off and removes the key. Check
  with `adb logcat -s TabletStore | grep "AI menu:"`.
- **Windows / desktop / docker store**: the same lines in the store's
  `store.properties` (`POS_CONFIG_FILE`), or the env vars (env wins).

The key is never logged (the startup line says only the provider), never
synced, never returned by an endpoint, and scrubbed from error text.

## Endpoints (store)

```
GET  /menu-ai/status                       {configured, available, provider, model, online, reason}
POST /menu-ai/photos                       multipart photo(s) + managerPin (+ note) → proposal
POST /menu-ai/chat                         {managerPin, text} → proposal
POST /menu-ai/apply                        {managerPin, proposalId, changeIds, confirmed?} → {applied, createdItemIds, changeSetId}
GET  /menu-ai/history                      last 20 change sets
GET  /menu-ai/requests                     the AI log: who, device, when, kind, outcome (last 100)
POST /menu-ai/history/{setId}/revert       {managerPin, force?}
```

All need a manager session; the ones that spend money or change the menu also
take a manager PIN. Offline (no route to the provider), the buttons are greyed
out with a note and nothing else in the store is affected. Errors come back as
`menu_ai_*` codes (`offline`, `timeout`, `rate_limited`, `quota`, `refused`,
`bad_reply`, `disabled`) that the tablet translates.

## AI safety

What happens if someone tries to jailbreak it: nothing interesting. The model
only ever proposes, and the store decides, with rules the model cannot talk
its way around (`server/.../aimenu/AiGuard.kt`, `MenuChangeSet.kt`).

- **Off-topic or injection requests** ("forget previous instructions", "write
  reverse Fibonacci", "what is your system prompt", "tell me a joke", role
  play) get one fixed, friendly reply, shown as a normal chat message: "I can
  only help set up and edit your menu", in French, English, Spanish and German.
  Plain cases are caught before any model call (so they cost nothing); the
  rest the model flags with its `refusal` field. A reply with no valid change
  set (prose, code, a leaked prompt, 1000 deletes) ends in the same fixed
  reply. The model's own words never reach the screen raw: only its one-line
  summary, and only when it is plain menu text.
- **Never reveals instructions or keys.** The system prompt says so; the store
  never returns the prompt, and any name or summary quoting it (or holding a
  key) is dropped.
- **Untrusted content is data.** The current menu, the manager's note, the
  names to translate and everything printed in a photo are wrapped in tagged
  blocks the prompt calls data; the manager's words go in their own block,
  with `<` and `>` neutralised so no text can close its block and pose as
  instructions. "Ignore previous instructions" printed on a menu photo can at
  worst become an odd item name, which the rules below then reject.
- **Hard limits, whatever the model says:** known operations only; ids must
  exist; prices above 0 and at most 1000 in the store currency (0 only for a
  size that is already free), so "make everything free" is rejected by the
  price rule; names up to 80 characters (categories 40, descriptions 300); no
  code, HTML, links, control characters, emoji strings or words from a small
  per-language blocklist; at most 150 new items and 25 removals per request
  (more removals and all of them are rejected). More than 10 removals or price
  changes need an extra "Apply anyway?" on the tablet, and the store refuses
  that Apply without it.
- **Rate limit and log:** 20 AI calls per 10 minutes per device and per
  manager (then `menu_ai_too_many`, "try again in a few minutes"). Every call
  is logged for the manager (`GET /menu-ai/requests`: who, device, when,
  chat / photos / translate / room_object, outcome, counts), never the key,
  the photo or the prompt.
- The same text rules cover "Add from photo" room objects and the item text
  that goes into AI photo prompts.

`AiSafetyTest` drives all of it with a fake model that misbehaves: prose,
code, an injected system prompt, huge, negative and zero prices, 1000 deletes,
HTML and script names, plus the exact requests above.

## Tested

Server tests use a fake model (`MenuAiRoutesTest`: preview, apply through the
catalog with the usual outbox events, rejected ids and prices, all-or-nothing,
revert round trip, revert of a soft delete, the conflict warning, the key never
in logs or responses) and fake HTTP for the three providers
(`MenuAiProvidersTest`). `client/test/ai_menu_test.dart` covers the dialog.
No network in CI.

## Floor plan: room objects and "Add from photo"

The floor-plan editor (manager PIN) places inert room objects: pool table,
bar front, pillar, entrance, host stand, kitchen, restrooms, stage, and
**custom** ones. A custom object is a name (French and English), an icon from
a fixed list the tablet ships (`client/lib/widgets/floor_object_icons.dart`,
same keys as `FLOOR_OBJECT_ICONS` on the store; never a generated image), and
a rect or round shape. Make one by hand ("Custom object…"), or "Add from
photo…": take or pick (file pick on Windows) one photo of the thing, and the
same provider and key as AI menu setup suggest the name, icon, shape and
size. The manager edits the suggestion before placing it; nothing is created
until then, and the photo is sent once and kept nowhere. With AI menu off or
the store offline the item is disabled with a note; hand-made custom objects
always work. `POST /floor-objects/ai-suggest` (multipart photo + managerPin).
Floor objects are not mirrored to the portal. Tested with the fake provider in
`FloorObjectKindsTest` and `client/test/room_objects_test.dart`.

## Floor plan: "Set up from picture"

In the floor-plan editor (manager PIN), the sparkle menu has "Set up from
picture…": take or pick (file pick on Windows) 1 to 4 pictures of one room: a
photo of the real room, a hand sketch on paper, or a printed or exported floor
plan. The same provider and key as AI menu setup read them and return a strict
JSON layout: tables (round, square, rect, booth, seats, position and size on
the room's 0–1000 plan, the number when one is written) and floor objects of
the existing types, plus CUSTOM with a name and icon for anything else (a
jukebox), and a short note of what it was not sure about.

The store checks everything before the tablet sees it (`RoomLayoutRules`):
known shapes and types only, everything clamped into the room, seats 1–20, at
most 80 tables and 40 objects, no table on top of another (nudged to the
nearest free spot, else dropped), and table numbers that never collide with a
live table anywhere in the store (the picture's own number when free, else the
lowest free one, with the room's label prefix). Writing in the pictures is
data; off-topic pictures, an injection attempt or an unreadable reply get the
fixed reply, never the model's words. The pictures are sent once and kept
nowhere; each call is in the AI request log (kind `room_layout`).

The tablet draws the layout as a ghost over the room with a list (N tables,
M seats, objects, what was skipped). The manager drags or removes ghost items;
when the room already has tables they choose **Replace** (clear the room
first) or **Add to room**. Tables with an open bill are never removed or
moved: a replace keeps them and the new tables avoid them. Apply is checked
again, all or nothing, and saved as a `room` change set: the snackbar offers
Revert, and "Rooms set up by AI" in the same menu lists them with Revert
(tables it removed are restored, the ones it added are removed; a table that
has an open bill by then blocks the revert). With AI menu off or the store
offline the item is disabled with a note.

```
POST /zones/{zoneId}/ai-layout         multipart 1–4 pictures + managerPin → layout to preview
POST /zones/{zoneId}/ai-layout/apply   {managerPin, proposalId, mode replace|merge, tables, objects} → change set
GET  /menu-ai/history?source=room      the floor-plan change sets (revert via /menu-ai/history/{setId}/revert)
```

Tested with a fake provider in `RoomFromPhotoTest` (a good layout, overlaps,
out of bounds, unknown types, huge counts, number collisions, an open bill
protected, revert) and `client/test/room_layout_test.dart` (the preview).

## Voice input

A mic button sits next to the menu AI chat and the floor assistant: hold to
talk and release, or tap to start and tap again (30 s at most). The tablet
and the Windows Surface both record 16 kHz mono WAV into memory (the `record`
package streams PCM on both) and send it once to `/menu-ai/chat/voice` or
`/zones/{zoneId}/ai-edit/voice`. The store passes the clip to Gemini as an
audio part with the usual instruction, so one call transcribes and interprets;
the model writes what it heard in `transcript` and the tablet shows
"Heard: …" above the answer. The transcript gets the typed text's checks, so
an injection by voice gets the fixed reply. The prompt names the store's
languages and insists the transcript is verbatim in the language spoken,
never translated (German audio once came back as a French transcript,
"Mettez table 12 ronde"). Typed or spoken, the model reports the request's
`language` and writes its summary in it; the fixed replies (no change, off
topic) and the "skipped" lines follow it too, falling back to the signed-in
user's language. The audio is never stored or
logged. OpenAI and Anthropic answer `409 menu_ai_voice_unsupported` (type
instead). Android asks for the microphone at first use (`RECORD_AUDIO`);
Windows uses its microphone privacy setting.

## Floor plan: "Ask AI"

In the floor-plan editor (manager PIN), the ✨ menu → "Ask AI…" edits the
CURRENT room by text or voice: "add four 2-tops along the window", "make
table 5 round with 6 seats", "remove the pool table", "renumber the patio
tables from 40". The model gets the room (tables with ids, numbers, shapes,
seats and geometry; objects; the walls are the plan's edges) and answers ops
(add / update / remove tables and objects). The store checks them with the
room-from-picture rules (known shapes and types, inside the room, no table on
another, numbers free in the store) and never moves, reshapes, renumbers or
removes a table with an open bill (on it or one of its sub-tables). A
sub-table link alone does not lock a table: linked tables can be reshaped,
reseated, moved and renumbered, and a table with sub-tables is only kept from
removal. The model may name a table by id, label or number ("u3", "U-12",
"Tisch 12", "12" all resolve to U-12; "L-5" in the Dining Room does not).
Made round or square with no size given, a table gets an even footprint of
about the same area, on the same centre. The result is the usual ghost
preview with a list of changes; Apply re-checks against the room as it is
then and saves a "room" change set that reverts like the others.
