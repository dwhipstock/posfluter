# FIX PROMPT — intermittent "Something went wrong — try again" = SQLITE_BUSY on the per-request session-touch

**Status:** open, root cause CONFIRMED 2026-07-30. Reproduced live during the
two-POS demo. A fix for this already exists on `perf/store-client-speedups`
(never merged) — evaluate cherry-picking it before re-implementing.

## Symptom

Intermittent full-screen **"Something went wrong — try again" / Retry** (and the
zones "Cannot reach server" variant) on **all screens**, on **both** the tablet
and the emulator, **only under concurrent load** (two POS terminals + cloud
sync). Tapping Retry fixes it immediately. It is NOT slowness — see below.

## Root cause (confirmed from store logs)

Every authenticated request resolves the bearer token via
`AuthService.me(token)` (`server/src/main/kotlin/dev/dwhipstock/pos/base/AuthService.kt`,
~L231–250), which does a **write inside the read transaction**:

```
L249:  Sessions.update({ Sessions.token eq token }) { it[lastUsedAt] = now }   // touch on EVERY request
```

(plus the idle-revoke write at L246, rare). With two POS polling `/zones` every
5s **plus** the cloud-sync writer, these writes collide on the single SQLite
file and throw `SQLITE_BUSY`, which propagates out as a 500. Live log:

```
SQLITE_BUSY: The database file is locked (database is locked)
  Statement(s): UPDATE sessions SET last_used_at=? WHERE sessions.token = ?
  Exposed - Transaction attempt #2 failed
500 Internal Server Error: GET - /settings in 5ms
500 Internal Server Error: GET - /zones   in 5ms
```

**It fails in ~5ms, not on a timeout** — so "wait longer for a response" does
NOT help; the response is an instant 500. A client retry helps (the lock is
momentary), but the correct fix is server-side. This is the known trap in memory
`pos-sqlite-upgrade-busy` ("SELECT-then-UPDATE in one txn fails SQLITE_BUSY
instantly; busy_timeout is ignored on write-write upgrades; ~27% of concurrent
GETs 500'd"). It only surfaced now because this is the **first time two POS hit
one store concurrently**.

## The fix (known-correct; a version already shipped on the perf branch)

Prescription from `pos-sqlite-upgrade-busy` + `pos-perf-session` ("session-touch
fix"): **keep the hot-path auth lookup read-only; move the `last_used_at` touch to
a separate best-effort transaction, and THROTTLE it.**

1. In `me(token)`: do the SELECT + expiry/idle check **read-only**. Return the
   AuthUser without writing on the happy path.
2. Do the `lastUsedAt` touch in a **separate, best-effort** transaction that
   **catches/swallows `SQLITE_BUSY`** (never fails the request) and is
   **throttled** — only touch if `lastUsedAt` is older than ~60s. Touching once
   per minute per session is plenty for the idle-timeout window and removes ~all
   the write contention. (Note `CloudSync.kt:107` already references a "~1/min
   touch throttle" concept — reuse the pattern.)
3. The idle-revoke write (L246) is fine to keep (rare); only the per-request
   happy-path touch (L249) needs to move off the read transaction.
4. `logout(token)` calls `me()` — re-check it still behaves after the refactor.
5. `busy_timeout`/WAL alone will NOT fix this (busy_timeout is ignored on the
   write-write upgrade inside a read txn) — reducing writes on the read path is
   the actual fix. WAL is presumably already on (the DR/Litestream setup implies
   it); confirm.

**FIRST STEP:** diff `perf/store-client-speedups` for its session-touch fix and
cherry-pick/adapt it rather than reinventing — it's already written and was the
basis of the `pos-perf-session` memory. Verify it matches the throttled-touch
design above.

## Optional secondary (client band-aid, not a substitute)

The perf branch also added a **GET-only bounded auto-retry** in `Api` (retry
idempotent reads once on transport/5xx failure before surfacing the error). Worth
having as defense-in-depth so a single transient blip never shows the Retry
screen — but retry ONLY GETs (never POST/PATCH: double-order risk). Do the
server fix first; this just makes the UI more forgiving.

## Reproduce / verify

- Reproduce: two POS logged into one local store (Manager 1234 / Server 9999),
  both polling the floor every 5s, cloud sync running → watch
  `docker logs pos-local-store-1` for `SQLITE_BUSY` + `500 ... /zones|/settings`.
- After the fix: run the same concurrent load for several minutes → **zero**
  `SQLITE_BUSY` and zero 500s on reads; the Retry screen never appears.

## Quick demo-day mitigation (no code, if needed before the fix lands)

The extra writers amplify the collision. To cut frequency during a live demo
(reversible): pause the local cloud-sync writer (it writes every 10s) and/or run
a single POS. This reduces — not eliminates — the BUSY window. The real fix is
above.

## Files

- `server/.../base/AuthService.kt` — `me()` (L231–250, the touch at L249),
  `logout()` (L253).
- The auth plugin that calls `me()` on every request (find via `me(` /
  Authentication).
- `server/.../base/Schema.kt` — `Sessions` (lastUsedAt column).
- Reference fix: branch `perf/store-client-speedups`.

See memory `pos-sqlite-upgrade-busy`, `pos-perf-session`, `pos-two-pos-same-store`.
