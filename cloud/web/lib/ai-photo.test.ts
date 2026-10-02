// AI item photos: the batch runs one picture at a time, in order; a single
// picture needs no confirm; the progress line counts finished pictures; the
// error codes have messages in every language. Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  initialStates,
  MAX_BATCH,
  needsBatchConfirm,
  nextWaiting,
  photoErrorKey,
  previewSrc,
  progress,
  readyIndexes,
  update,
} from "./ai-photo";
import { messages } from "./i18n/messages";
import { es } from "./i18n/messages.es";
import { de } from "./i18n/messages.de";
import { af } from "./i18n/messages.af";
import type { AiPhotoAsk, AiPhotoPreview } from "./types";

const ask = (id: string): AiPhotoAsk => ({ id, itemId: `item-${id}`, title: `Item ${id}`, mode: "generate", hasPhoto: false });

test("one picture starts at once; several wait for a confirm; at most ten", () => {
  assert.equal(needsBatchConfirm([ask("1")]), false);
  assert.equal(needsBatchConfirm([ask("1"), ask("2")]), true);
  assert.equal(initialStates(Array.from({ length: 14 }, (_, i) => ask(String(i)))).length, MAX_BATCH);
});

test("a batch is made one at a time, in order", () => {
  let s = initialStates([ask("1"), ask("2"), ask("3")]);
  assert.equal(nextWaiting(s), 0);
  s = update(s, 0, { status: "making" });
  assert.equal(nextWaiting(s), -1, "never two at once");
  s = update(s, 0, { status: "ready" });
  assert.equal(nextWaiting(s), 1);
  s = update(s, 1, { status: "failed", error: "x" });
  assert.equal(nextWaiting(s), 2);
  assert.deepEqual(progress(s), { done: 2, total: 3 });
  s = update(s, 2, { status: "ready" });
  assert.equal(nextWaiting(s), -1);
  assert.deepEqual(readyIndexes(s), [0, 2]);
});

test("the preview is shown from its own bytes", () => {
  const p = { contentType: "image/png", dataBase64: "AAAA" } as AiPhotoPreview;
  assert.equal(previewSrc(p), "data:image/png;base64,AAAA");
  assert.equal(previewSrc({ ...p, contentType: "text/html" }), "data:image/jpeg;base64,AAAA");
});

test("every photo error has a message in every language", () => {
  for (const code of [
    "menu_ai_photo_refused",
    "menu_ai_photo_name",
    "menu_ai_photo_daily_limit",
    "menu_ai_photo_changed",
    "menu_ai_photo_expired",
    "menu_ai_photo_none",
    "menu_ai_photos_disabled",
    "menu_ai_photo_failed",
  ]) {
    const k = photoErrorKey(code);
    assert.ok(k, code);
    assert.ok(messages[k].en && messages[k].fr && es[k] && de[k] && af[k], code);
  }
  assert.equal(photoErrorKey("something_else"), null);
});
