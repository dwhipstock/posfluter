# Forecourt: pumps, the FDC, and our simulator

Background for the Pronghorn Fuel & Market client (a Texas gas station with a
convenience store): how a POS drives fuel pumps in the real world, which
standards describe that, and how our forecourt simulator
(`forecourt/simulator/`) mirrors it.

## What an FDC is

The POS never talks to a pump directly. Between them sits a **Forecourt Device
Controller (FDC)**: software (often on its own box, sometimes inside the POS
server) that speaks each dispenser brand's wire protocol on one side and a
standard, logical interface on the other. The POS asks the FDC to authorise
pump 3; the FDC works out the dispenser, the protocol and the timing, and
reports back what the pump is doing.

Devices an FDC typically manages:

| device | what it is |
|---|---|
| **Fuel point / dispenser** | one side of a pump island that serves one car at a time. Standards call it a *fuel point* (FP); a physical dispenser can hold two. |
| **Nozzle** | the hose and handle for one grade (product). Lifting it is what the FDC sees as "the customer wants fuel". |
| **Tank level gauge / probes** | report product level, water and temperature per tank. |
| **Price pole / price sign** | the roadside sign; updated with price changes. |
| **OPT / pay-at-pump** | the card terminal on the dispenser (outdoor payment terminal), so a customer can pay without going inside. |
| car wash, air, etc. | other forecourt devices some FDCs also handle. |

## The standards

- **IFSF (International Forecourt Standards Forum)**, founded in 1992 by
  European oil companies and equipment makers, publishes device-level protocols
  (dispensers, tank gauges, price signs, car wash, payment terminals; originally
  over LonWorks, later also TCP/IP) and interface standards between the site
  systems. Two matter here:
  - the **FDC–POS interface** (IFSF "Part 3-27, FDC-POS Standard Interface"):
    XML messages over TCP/IP between one FDC and one or more POS terminals;
  - the **POS–EPS** payment interface, the ancestor of the widely used OPI
    interface (not needed for the simulator).
- **Conexxus** (the North American convenience-retail standards body) publishes
  the **Forecourt Device Controller (FDC) Specification**. By Conexxus's own
  description it is built on the earlier IFSF FDC standard, adapted for North
  America and with a simplified schema. It covers logical device states, device
  configuration (fuelling limits, service modes, prices), device control
  (reserve or authorise a dispenser, obtain a car wash code), data reporting
  (such as tank levels) and unsolicited messages for state changes and alarms.
- **IFSF + Conexxus joint work**: the two bodies align their standards and
  publish joint documents, including a joint "POS-FDC Interface Standard"
  (IFSF Part 3-70), and have started a shared set of REST/JSON API definitions
  (the "Open Retailing" API work) with a public repository of samples and
  design documents.
- **PACE OpenFSC** (Open Fueling Site Connect, github.com/pace/openfsc-spec):
  an open protocol, MIT licensed, for connecting a site to a connected-fuelling
  (mobile payment) platform. It is a line-based text protocol (CRLF-terminated
  commands) over a TLS WebSocket, or plain TCP inside a VPN; the *site*
  connects out to the platform. Its vocabulary is small and readable: `PUMPS`,
  `PUMP`, `PUMPSTATUS`, `PRICE`/`PRICES`, `TRANSACTIONS`/`TRANSACTION`,
  `LOCKPUMP`/`UNLOCKPUMP`, `CLEAR`, `HEARTBEAT`/`BEAT`, with pump statuses
  `free`, `in-use`, `in-transaction`, `ready-to-pay`, `locked`, `out-of-order`.
  PACE also describes a browser **Fueling Simulator** that plays a gas station
  for development and demos; PACE provides access to it during an integration
  project, it is not a public download. It inspired our pump panel.

### Public vs member-only

| document | availability |
|---|---|
| IFSF standards (device protocols, Part 3-27 FDC-POS, POS-EPS) | **members only** (IFSF participants: members, technical associates, partner organisations). |
| IFSF–Conexxus joint POS-FDC standard (Part 3-70) | listed on the IFSF site; download needs an IFSF login. |
| Conexxus FDC Specification | Conexxus standards are member-first; Conexxus now publishes a set of standards free on its "Public Standards" page. Check that page for the current FDC version before assuming either way. |
| IFSF/Conexxus Open Retailing API repository | partly public (samples, design documents, data dictionary); some sections need a login. |
| PACE OpenFSC spec | **public**, MIT licence, on GitHub. |
| PACE Fueling Simulator | available from PACE to integration partners. |

We copy no text from any member-only document. This page paraphrases public
descriptions and uses message *names* only, so a reader knows what to look for
in the real spec.

## Concepts we mirror

- **Fuel point states.** The IFSF/Conexxus model names them `FDC_READY`,
  `FDC_CALLING`, `FDC_AUTHORISED`, `FDC_STARTED` (authorised, nozzle out, no
  flow yet), `FDC_FUELLING`, `FDC_SUSPENDED_FUELLING`, `FDC_ERROR`,
  `FDC_OFFLINE`, plus closed / disabled / out-of-order variants (approx. — the
  exact list is in the member spec). We use short names (`IDLE`, `CALLING`,
  `AUTHORISED`, `FUELLING`, `SUSPENDED`, `EMERGENCY_STOP`, `ERROR`, `OFFLINE`)
  and report the FDC-style name alongside as `fdcState`. `FDC_EMERGENCY_STOP`
  is our own label (approx.): a real FDC reports an emergency stop through its
  device state and alarm messages.
- **Nozzles and products.** Each nozzle carries one product (grade) with a
  product number and a price. Ours: REG 1, MID 2, PRE 3, DSL 4.
- **Authorise / reserve.** The POS authorises a fuel point, optionally with a
  **preset limit** (money or volume). Reserve (claim a pump for one POS before
  authorising) exists in the real spec; we do not model it.
- **Prepay vs postpay.** Prepay: the customer pays first, the POS authorises
  with the paid amount as the limit, the pump stops exactly on it, and the POS
  refunds any difference. Postpay: authorise with no limit, the customer pays
  inside afterwards.
- **Fuel sale transactions.** When fuelling ends the FDC keeps the sale in a
  buffer as **payable**. A POS **locks** it while taking payment (so a second
  till cannot take it too), then **clears** it once paid; unlocking returns it
  to payable. FDCs limit how many unpaid sales a pump may stack (commonly 2)
  before it will not authorise again.
- **Prices.** A price change applies to the next sale; a sale in progress keeps
  the price it started at.
- **Totals.** Dispensers keep cumulative, never-reset totalisers per nozzle
  (volume and money); the FDC reports them for shift and day reconciliation.
- **Errors.** Offline (no communication with the dispenser), device error,
  emergency stop (all pumps or one) — the POS must show these and refuse to
  authorise.

## Transport: real FDC vs our simulator

A real IFSF/Conexxus FDC speaks **XML over TCP sockets**, normally on two
channels (approx.):

1. a **request/response channel**: the POS logs on, then sends requests
   (`ServiceRequest` with a request type such as `AuthoriseFuelPoint`) and gets
   a matching `ServiceResponse` with an overall result and per-device results;
2. an **unsolicited-message channel**: the FDC pushes `FDCMessage`s such as
   fuel point state changes and completed sales, plus heartbeats so each side
   knows the other is alive.

Our simulator keeps the same concepts but uses **JSON over HTTP** for requests
and **Server-Sent Events** (`GET /fdc/v1/events`) for the unsolicited channel.
Every success response carries `fdcMessage` naming the FDC request it stands
for. A production adapter would:

- hold the TCP connections, log on, and send/answer heartbeats;
- turn each adapter call into the XML request, and map the response's result
  and error codes to ours (busy, offline, error, not authorised, locked...);
- translate unsolicited state-change and sale messages into the same pump and
  transaction objects our SSE stream carries;
- convert units: FDCs send decimal volumes, prices and amounts; we carry
  integers (milli-gallons, mills per gallon, cents).

The POS side only sees the adapter interface, so swapping the simulator for a
real FDC changes one class, not the checkout.

## Mapping: our endpoints to FDC messages

Names marked approx. are our best reading of public material; confirm against
the member spec before building a real adapter.

| our endpoint | FDC message | note |
|---|---|---|
| `GET /fdc/v1/status` | GetFDCStatus + GetFPState (all) + GetAvailableFuelSaleTrxs | approx.; one call instead of three |
| `GET /fdc/v1/pumps[/{n}]` | GetFPState | |
| `POST …/pumps/{n}/authorise` | AuthoriseFuelPoint | preset amount = `maxAmountCents` |
| `POST …/pumps/{n}/free` | FreeFuelPoint | cancel before flow |
| `POST …/pumps/{n}/stop` | StopFuelPoint | approx.; some implementations name it SuspendFuelPoint |
| `POST …/pumps/{n}/resume` | StartFuelPoint | approx.; some name it ResumeFuelPoint |
| `POST …/pumps/{n}/emergency-stop`, `POST /fdc/v1/emergency-stop` | EmergencyStop | approx. |
| `POST …/pumps/{n}/reset` | CancelEmergencyStop | approx.; also clears a simulated error |
| `GET /fdc/v1/transactions` | GetAvailableFuelSaleTrxs | approx. |
| `GET /fdc/v1/transactions/{id}` | GetFuelSaleTrxDetails | |
| `POST …/transactions/{id}/lock` | LockFuelSaleTrx | |
| `POST …/transactions/{id}/unlock` | UnlockFuelSaleTrx | |
| `POST …/transactions/{id}/clear` | ClearFuelSaleTrx | |
| `GET /fdc/v1/prices` | GetProductTable | approx. |
| `POST /fdc/v1/prices` | ChangeFuelPrice | |
| `GET /fdc/v1/totals` | GetFuelPointTotals | approx.; dispenser-level variant GetDSPTotals |
| SSE `event: pump` | unsolicited FPStateChange | approx. |
| SSE `event: transaction` | unsolicited FuelSaleTrx | approx. |
| SSE `event: heartbeat` | Heartbeat | |
| `/sim/v1/*` | none | the customer's hands: lift, trigger, hang up, faults |

## Sources

- IFSF: <https://ifsf.org/> (standards list; joint Part 3-70 page:
  <https://ifsf.org/document/ifsf-conexxus-joint-standard-part-3-70-pos-fdc-interface-standard/>)
- Conexxus FDC Specification: <https://www.conexxus.org/ourwork/fdc-specification>;
  public standards: <https://www.conexxus.org/public-standards>
- IFSF overview: <https://en.wikipedia.org/wiki/International_Forecourt_Standards_Forum>
- PACE OpenFSC: <https://github.com/pace/openfsc-spec>

## The POS side

The store server (Kotlin) talks to the forecourt; the counter (Flutter) only
ever talks to its store. `server/.../forecourt/`:

- **`ForecourtAdapter`** — the store's interface to a forecourt controller:
  `snapshot()` (every pump and every uncleared transaction), `authorise`
  (postpay, or prepay with a `maxAmountCents` limit and a `posRef`), `free`,
  `stop` / `resume`, `emergencyStop` (one pump or all), `reset`, `lock` /
  `unlock` / `clear` a transaction, `setPrices`. Failures are typed:
  `ForecourtUnavailable` (no answer: pumps show offline) and
  `ForecourtRefused` (the controller's reason code: `PUMP_BUSY`,
  `EMERGENCY_STOP`, `TRX_LOCKED`…). Sub-second timeouts; nothing on a
  selling path waits for it.
- **`SimulatorAdapter`** — the implementation for this simulator
  (`FORECOURT_URL`, default `http://127.0.0.1:8086`). A real FDC adapter is
  a second implementation of the same interface: it would open the IFSF
  POS-to-FDC channels (request/response and unsolicited), send the same
  messages as XML and map the FDC's device and transaction states onto
  `PumpState` / `TrxState`. Nothing else in the store changes.
- **`ForecourtService`** — polls the adapter every 300 ms (`FORECOURT_POLL_MS`)
  for the pump grid and keeps the store's own record, `fuel_sales` (store
  migration 046), in step with the controller:
  - **postpay**: the cashier authorises the pump; the customer fills up and
    hangs up; the payable transaction is **locked** onto the sale as a fuel
    line (pump, grade, gallons, price per gallon; `posRef = sale-<id>`), and
    **cleared** when the sale closes. Taken off the sale → unlocked.
  - **prepay**: "$40 on pump 3" is a prepay line on the sale. When the sale is
    paid the pump is authorised with a $40 limit (`posRef = prepay-<id>`).
    When the pump finishes the store refunds the unused amount on that sale
    (same tender, cash to the nickel), the tile shows **CHANGE $x.xx** until
    the cashier taps "change given", and the transaction is cleared.
  - Offline: pumps show offline, pump commands and new prepays are refused
    (`forecourt_offline`), **in-store sales go on**. A prepay paid while the
    controller is down is authorised when it answers again; a settled sale is
    cleared at the controller later. The store's rows are the truth for
    money, the controller's for what the pump dispensed.
- Fuel lines carry **no added sales tax** (Texas fuel taxes are in the pump
  price); in-store taxable goods get the store's sales tax. Receipts show
  `Pump 3 · 10.052 gal @ 3.299/gal`.
- Sync (`cloud/CONTRACT.md` §2, Fuel): `check.closed` fuel lines carry a
  `fuel` object; each settled fuelling sends a `fuel.sale` event (grade,
  gallons, price, amount, cost, prepay and refund).
- Store API for the counter: `GET /forecourt`, `POST /forecourt/pumps/{n}/
  authorise|stop|resume|reset|emergency-stop`, `POST /forecourt/emergency-stop`,
  `POST /forecourt/prepays/{id}/cancel|change-given`,
  `POST /retail/sales/{id}/fuel {trxId}`, `POST /retail/sales/{id}/prepay
  {pump, amountCents}`.

Tests: the pump state machine lives in `forecourt/simulator/test`; the
store's side (prepay refunds, postpay locking, offline handling, fuel tax,
sync) in `server/src/test/.../GasStationTest.kt` against an in-memory
`FakeForecourt`, and the adapter's wire format in `SimulatorAdapterTest.kt`.
