# AI menu setup

A manager never has to type a menu in. From **Manage menu** (the sparkles
button, managers only), on the Android tablet and the Windows build:

- **Menu from photos**: take or pick up to 6 photos of a paper menu. The AI
  proposes the categories, items, sizes, prices and descriptions to add (and a
  price update for anything already on the menu at a different price).
- **Chat**: type, or dictate with the keyboard's mic, things like
  "add Caesar salad $14 under starters", "raise all burgers by a dollar",
  "rename the Reuben to Montreal Smoked Meat", "86 the salmon",
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
Québec pubs) gets both filled when the menu or the manager gives both; when
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

## Turning it on

| Setting | Values |
|---|---|
| `menu.ai` / `POS_MENU_AI` | `on` / `off` (default off: no button) |
| `menu.ai.provider` / `POS_MENU_AI_PROVIDER` | `gemini` / `openai` / `anthropic` / `off` |
| key | `menu.ai.gemini.apiKey` / `menu.ai.openai.apiKey` / `menu.ai.anthropic.apiKey`, or `GEMINI_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` |
| `menu.ai.model` / `POS_MENU_AI_MODEL` | optional model id |

Default models: Gemini `gemini-2.5-flash` (`generateContent`, JSON mode),
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
