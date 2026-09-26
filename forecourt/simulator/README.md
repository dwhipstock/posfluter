# Forecourt simulator

A small stand-in for a gas station's **Forecourt Device Controller (FDC)** plus
its pumps, for the Pronghorn Fuel & Market demo. The POS talks to it the way it
would talk to a real FDC (authorise a pump, watch it fuel, lock and clear the
sale); a browser **pump panel** plays the customer at the pump.

- Node 22 or newer, built-ins only. No `npm install`, no internet.
- Background and the mapping to the real FDC messages: [`docs/forecourt.md`](../../docs/forecourt.md).

## Run

```sh
cd forecourt/simulator
npm start            # or: node server.js
# panel:   http://localhost:8086/
# FDC API: http://localhost:8086/fdc/v1/status
npm test             # model + HTTP + SSE tests (node:test)
```

| env | default | meaning |
|---|---|---|
| `FDC_PORT` | `8086` | HTTP port |
| `FDC_HOST` | `0.0.0.0` | bind address (LAN reachable) |
| `FDC_PUMPS` | `8` | pumps 1..N, each with nozzles 1-4 = REG, MID, PRE, DSL |
| `FDC_SPEED` | `1` | flow multiplier 1-20 (1 = 160 milli-gal/s, about 9.6 gpm) |

## Units

Integers everywhere. `volumeMilli` = thousandths of a gallon, `priceMills` =
thousandths of a dollar per gallon, `amountCents` = cents.
`amountCents = round-half-up(volumeMilli × priceMills / 10000)`. Timestamps are
ISO-8601 UTC.

## API

Every POS-facing success is `{"result":"Success","fdcMessage":"<FDC name>", ...}`.
Errors are HTTP 4xx `{"result":"Failure","error":{"code","message"}}` with codes
`BAD_REQUEST` (400), `NOT_FOUND` (404), and 409s: `PUMP_BUSY`, `PUMP_OFFLINE`,
`PUMP_ERROR`, `EMERGENCY_STOP`, `NOT_AUTHORISED`, `TOO_MANY_UNPAID`,
`TRX_LOCKED`, `INVALID_STATE`. CORS is open (`*`) with OPTIONS preflight.

### POS-facing (`/fdc/v1`)

| method | path | fdcMessage | notes |
|---|---|---|---|
| GET | `/fdc/v1/status` | GetFDCStatus | one-call snapshot: grades, pumps, unpaid (PAYABLE + LOCKED) sales |
| GET | `/fdc/v1/pumps` | GetFPState | `pumps` |
| GET | `/fdc/v1/pumps/{n}` | GetFPState | `pump` |
| POST | `/fdc/v1/pumps/{n}/authorise` | AuthoriseFuelPoint | `{"mode":"PREPAY"\|"POSTPAY","maxAmountCents":4000,"posRef":"sale-42"}` → `authId`, `pump` |
| POST | `/fdc/v1/pumps/{n}/free` | FreeFuelPoint | cancel an authorisation before flow |
| POST | `/fdc/v1/pumps/{n}/stop` | StopFuelPoint | FUELLING → SUSPENDED; AUTHORISED → freed |
| POST | `/fdc/v1/pumps/{n}/resume` | StartFuelPoint | SUSPENDED → FUELLING |
| POST | `/fdc/v1/pumps/{n}/emergency-stop` | EmergencyStop | one pump |
| POST | `/fdc/v1/emergency-stop` | EmergencyStop | all pumps (`stopped`, `pumps`) |
| POST | `/fdc/v1/pumps/{n}/reset` | CancelEmergencyStop | leave EMERGENCY_STOP / ERROR |
| GET | `/fdc/v1/transactions?state=&pump=` | GetAvailableFuelSaleTrxs | default PAYABLE + LOCKED |
| GET | `/fdc/v1/transactions/{id}` | GetFuelSaleTrxDetails | |
| POST | `/fdc/v1/transactions/{id}/lock` | LockFuelSaleTrx | `{"posRef":"sale-42"}` |
| POST | `/fdc/v1/transactions/{id}/unlock` | UnlockFuelSaleTrx | |
| POST | `/fdc/v1/transactions/{id}/clear` | ClearFuelSaleTrx | paid: PAYABLE/LOCKED → CLEARED |
| GET | `/fdc/v1/prices` | GetProductTable | `grades` |
| POST | `/fdc/v1/prices` | ChangeFuelPrice | `{"prices":[{"grade":"REG","priceMills":2949}]}`; next sale on |
| GET | `/fdc/v1/totals` | GetFuelPointTotals | per pump per grade + `grandTotal`; never reset except by `/sim/v1/reset` |
| GET | `/fdc/v1/events` | (unsolicited) | SSE: `pump`, `transaction`, `heartbeat` (5 s); all pumps sent on connect |

### Simulator only (`/sim/v1`) — the customer at the pump

| method | path | body |
|---|---|---|
| POST | `/sim/v1/pumps/{n}/lift` | `{"nozzle":2}` or `{"grade":"MID"}` |
| POST | `/sim/v1/pumps/{n}/trigger` | `{"on":true\|false}` |
| POST | `/sim/v1/pumps/{n}/hangup` | |
| POST | `/sim/v1/pumps/{n}/offline` | `{"on":true\|false}` |
| POST | `/sim/v1/pumps/{n}/error` | `{"on":true\|false,"message":"..."}` |
| POST | `/sim/v1/speed` | `{"multiplier":5}` (1-20), optional `"pump":3` for one pump |
| POST | `/sim/v1/reset` | everything back to boot state (returns the status snapshot) |

`GET /` or `/panel` serves the panel; `GET /healthz` → `{"ok":true}`.

### Pump states

`IDLE` → lift → `CALLING` → authorise → `AUTHORISED` → trigger → `FUELLING`
(→ POS stop → `SUSPENDED` → resume) → hang up → sale is `PAYABLE`, pump `IDLE`.
A prepay can be authorised with the nozzle down (IDLE → AUTHORISED). Plus
`EMERGENCY_STOP`, `OFFLINE`, `ERROR`. A pump may hold at most 2 uncleared
POSTPAY sales; beyond that authorise (and a fresh lift) answer `TOO_MANY_UNPAID`.

## The panel

`http://<host>:8086/` shows every pump as a dispenser face (TOTAL SALE $,
GALLONS, PRICE/GAL), a status light, and the authorisation. Per pump:

- **Grade buttons** (87 / 89 / 93 / Diesel) lift that nozzle.
- **HOLD TO PUMP**: press and hold (mouse, touch, or the space bar for the
  selected pump). **LATCH** is the hold-open clip: pumping continues hands-free.
- **HANG UP** returns the nozzle and completes the sale.
- The **•••** menu: go offline, raise an error, reset, emergency stop, and quick
  POS test buttons (authorise postpay, prepay $20) for demos without the POS.
- **Unpaid** lists the pump's PAYABLE / LOCKED sales.

Top bar: speed 1× / 5× / 10×, **EMERGENCY STOP ALL**, reset all. Updates arrive
over SSE; if the stream drops the panel polls `/fdc/v1/status` every 500 ms.

## Code

- `lib/forecourt.js` — the model: a pure state machine with an injected clock
  (flow is computed from elapsed time, so tests use a fake clock).
- `lib/api.js` — HTTP routes, SSE, static panel files; 100 ms tick.
- `server.js` — reads env, starts listening.
- `public/` — the panel (`panel.html`, `panel.css`, `panel.js`).
