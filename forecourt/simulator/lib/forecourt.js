// Forecourt model: the FDC plus its pumps, as a pure state machine.
//
// No I/O and no timers here. Time comes from an injected `now()` (ms since
// epoch), and flow is computed from elapsed time whenever the model is touched
// (every public call, plus `tick()`, which the server calls every 100 ms).
// Tests drive it with a fake clock.
//
// Units are integers everywhere:
//   volumeMilli  thousandths of a US gallon
//   priceMills   thousandths of a dollar per gallon
//   amountCents  cents
//   amount = round-half-up(volumeMilli * priceMills / 10000)

export const VERSION = '1.0.0';

// Realistic flow: 160 milli-gallons per second (about 9.6 gpm) at 1x.
// Internally we accumulate micro-gallons: 160 milli/s == 160 micro/ms.
export const FLOW_MICRO_PER_MS = 160;
export const MAX_UNPAID = 2; // FDC-style stacking limit (uncleared POSTPAY sales per pump)
export const EVENT_THROTTLE_MS = 250; // at most one flowing pump event per pump per 250 ms
const KEEP_CLEARED = 200; // cleared sales kept for GET ?state=CLEARED

export const DEFAULT_GRADES = [
  { grade: 'REG', name: 'Regular', nameEs: 'Regular', productNo: 1, priceMills: 2899 },
  { grade: 'MID', name: 'Mid-Grade', nameEs: 'Intermedia', productNo: 2, priceMills: 3299 },
  { grade: 'PRE', name: 'Premium', nameEs: 'Premium', productNo: 3, priceMills: 3699 },
  { grade: 'DSL', name: 'Diesel', nameEs: 'Diésel', productNo: 4, priceMills: 3499 },
];
// Every pump: nozzle 1..4 -> REG, MID, PRE, DSL.
const NOZZLE_GRADES = ['REG', 'MID', 'PRE', 'DSL'];

// Our state -> the matching IFSF-style fuel point state name (informational).
const FDC_STATE = {
  IDLE: 'FDC_READY',
  CALLING: 'FDC_CALLING',
  AUTHORISED: 'FDC_AUTHORISED', // FDC_STARTED once the nozzle is up (see fdcStateOf)
  FUELLING: 'FDC_FUELLING',
  SUSPENDED: 'FDC_SUSPENDED_FUELLING',
  EMERGENCY_STOP: 'FDC_EMERGENCY_STOP',
  ERROR: 'FDC_ERROR',
  OFFLINE: 'FDC_OFFLINE',
};

const STATUS_BY_CODE = {
  BAD_REQUEST: 400,
  NOT_FOUND: 404,
  PUMP_BUSY: 409,
  PUMP_OFFLINE: 409,
  PUMP_ERROR: 409,
  EMERGENCY_STOP: 409,
  NOT_AUTHORISED: 409,
  TOO_MANY_UNPAID: 409,
  TRX_LOCKED: 409,
  INVALID_STATE: 409,
};

export class FdcError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
    this.status = STATUS_BY_CODE[code] ?? 400;
  }
}

/** round-half-up(volumeMilli * priceMills / 10000), exact integer math. */
export function amountFor(volumeMilli, priceMills) {
  return Number((BigInt(volumeMilli) * BigInt(priceMills) + 5000n) / 10000n);
}

const pad6 = (n) => String(n).padStart(6, '0');

export class Forecourt {
  /**
   * @param {object} opts
   * @param {number} [opts.pumps=8]
   * @param {number} [opts.speed=1]  global flow multiplier (1..20)
   * @param {() => number} [opts.now=Date.now]  clock in ms
   */
  constructor({ pumps = 8, speed = 1, now = Date.now } = {}) {
    this.pumpCount = pumps;
    this.bootSpeed = clampSpeed(speed);
    this.now = now;
    this.listeners = new Set();
    this.reset();
  }

  // ---- lifecycle ---------------------------------------------------------

  /** Everything back to boot state (pumps, buffer, prices, totals, counters). */
  reset() {
    const t = this.now();
    this.speed = this.bootSpeed;
    this.grades = DEFAULT_GRADES.map((g) => ({ ...g }));
    this.trx = new Map(); // trxId -> trx (insertion order = completion order)
    this.authSeq = 0;
    this.trxSeq = 0;
    this.pumps = [];
    this.totals = [];
    for (let n = 1; n <= this.pumpCount; n++) {
      this.pumps.push({
        pump: n,
        state: 'IDLE',
        nozzleUp: null,
        trigger: false, // trigger held (or latched) by the customer
        flowing: false,
        authorisation: null,
        current: null,
        display: { grade: null, priceMills: 0, volumeMilli: 0, amountCents: 0 },
        error: null,
        stateSince: t,
        speed: 1, // per-pump multiplier on top of the global one
        lastFlowAt: t,
        micro: 0, // micro-gallons dispensed in the live sale
        lastEmitAt: 0,
      });
      this.totals.push(new Map(NOZZLE_GRADES.map((g) => [g, { volumeMilli: 0, amountCents: 0, count: 0 }])));
    }
    for (const p of this.pumps) this.emit('pump', this.pumpJson(p));
  }

  /** Subscribe to events: fn(type, data), type 'pump' | 'transaction'. */
  on(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  emit(type, data) {
    for (const fn of this.listeners) fn(type, data);
  }

  iso(ms = this.now()) {
    return new Date(ms).toISOString();
  }

  // ---- flow --------------------------------------------------------------

  /** Advance every flowing pump to now(); emits throttled pump events. */
  tick() {
    const t = this.now();
    for (const p of this.pumps) {
      const changed = this.advance(p, t);
      if (changed.limit) {
        this.emitPump(p);
      } else if (changed.volume && t - p.lastEmitAt >= EVENT_THROTTLE_MS) {
        this.emitPump(p);
      }
    }
  }

  advance(p, t) {
    const dt = Math.max(0, t - p.lastFlowAt);
    p.lastFlowAt = t;
    const out = { volume: false, limit: false };
    if (!(p.state === 'FUELLING' && p.flowing && p.current) || dt === 0) return out;
    const c = p.current;
    p.micro += Math.round(FLOW_MICRO_PER_MS * this.speed * p.speed * dt);
    const vol = Math.floor(p.micro / 1000);
    if (vol === c.volumeMilli) return out;
    const amt = amountFor(vol, c.priceMills);
    if (c.maxAmountCents != null && amt >= c.maxAmountCents) {
      // Prepay/preset limit: stop exactly on the amount.
      c.amountCents = c.maxAmountCents;
      c.volumeMilli = Math.floor((c.maxAmountCents * 10000) / c.priceMills);
      c.limitReached = true;
      p.flowing = false;
      out.limit = true;
    } else {
      c.volumeMilli = vol;
      c.amountCents = amt;
    }
    out.volume = true;
    p.display = { grade: c.grade, priceMills: c.priceMills, volumeMilli: c.volumeMilli, amountCents: c.amountCents };
    return out;
  }

  /** Bring every pump up to date before a command reads or changes state. */
  sync() {
    const t = this.now();
    for (const p of this.pumps) {
      if (this.advance(p, t).limit) this.emitPump(p);
    }
  }

  // ---- lookups -----------------------------------------------------------

  pumpOf(n) {
    const i = Number(n);
    if (!Number.isInteger(i) || i < 1 || i > this.pumps.length) {
      throw new FdcError('NOT_FOUND', `No pump ${n}`);
    }
    return this.pumps[i - 1];
  }

  gradeOf(code) {
    return this.grades.find((g) => g.grade === code);
  }

  unpaidPostpay(p) {
    let n = 0;
    for (const t of this.trx.values()) {
      if (t.pump === p.pump && t.mode === 'POSTPAY' && t.state !== 'CLEARED') n++;
    }
    return n;
  }

  // Common guard for POS commands on a pump.
  guardPos(p, { allowEstop = false } = {}) {
    if (p.state === 'OFFLINE') throw new FdcError('PUMP_OFFLINE', `Pump ${p.pump} is offline`);
    if (p.state === 'ERROR') throw new FdcError('PUMP_ERROR', `Pump ${p.pump} is in error: ${p.error}`);
    if (!allowEstop && p.state === 'EMERGENCY_STOP') {
      throw new FdcError('EMERGENCY_STOP', `Pump ${p.pump} is emergency stopped`);
    }
  }

  setState(p, state) {
    if (p.state !== state) {
      p.state = state;
      p.stateSince = this.now();
    }
  }

  restState(p) {
    return p.nozzleUp != null ? 'CALLING' : 'IDLE';
  }

  emitPump(p) {
    p.lastEmitAt = this.now();
    this.emit('pump', this.pumpJson(p));
  }

  // ---- POS commands (FDC side) -------------------------------------------

  authorise(n, { mode, maxAmountCents = null, posRef = null } = {}) {
    this.sync();
    const p = this.pumpOf(n);
    if (mode !== 'PREPAY' && mode !== 'POSTPAY') {
      throw new FdcError('BAD_REQUEST', 'mode must be PREPAY or POSTPAY');
    }
    if (maxAmountCents != null && !(Number.isInteger(maxAmountCents) && maxAmountCents > 0)) {
      throw new FdcError('BAD_REQUEST', 'maxAmountCents must be a positive integer (cents)');
    }
    if (mode === 'PREPAY' && maxAmountCents == null) {
      throw new FdcError('BAD_REQUEST', 'PREPAY requires maxAmountCents > 0');
    }
    if (posRef != null && typeof posRef !== 'string') {
      throw new FdcError('BAD_REQUEST', 'posRef must be a string');
    }
    this.guardPos(p);
    if (p.state === 'AUTHORISED' || p.state === 'FUELLING' || p.state === 'SUSPENDED') {
      throw new FdcError('PUMP_BUSY', `Pump ${p.pump} is ${p.state}`);
    }
    if (this.unpaidPostpay(p) >= MAX_UNPAID) {
      throw new FdcError('TOO_MANY_UNPAID', `Pump ${p.pump} already has ${MAX_UNPAID} unpaid sales`);
    }
    const authId = `A-${pad6(++this.authSeq)}`;
    p.authorisation = { authId, mode, maxAmountCents: maxAmountCents ?? null, posRef: posRef ?? null, authorisedAt: this.iso() };
    this.setState(p, 'AUTHORISED');
    // A nozzle already up with its trigger held (hold-open clip) starts at once.
    if (p.nozzleUp != null && p.trigger) this.startSale(p);
    this.emitPump(p);
    return { authId, pump: this.pumpJson(p) };
  }

  free(n) {
    this.sync();
    const p = this.pumpOf(n);
    this.guardPos(p);
    if (p.state === 'FUELLING' || p.state === 'SUSPENDED') {
      throw new FdcError('INVALID_STATE', `Pump ${p.pump} is fuelling; stop it and hang up instead`);
    }
    if (p.state !== 'AUTHORISED') throw new FdcError('NOT_AUTHORISED', `Pump ${p.pump} is not authorised`);
    p.authorisation = null;
    this.setState(p, this.restState(p));
    this.emitPump(p);
    return { pump: this.pumpJson(p) };
  }

  stop(n) {
    this.sync();
    const p = this.pumpOf(n);
    this.guardPos(p);
    if (p.state === 'FUELLING') {
      p.flowing = false;
      this.setState(p, 'SUSPENDED');
      this.emitPump(p);
    } else if (p.state === 'AUTHORISED') {
      return this.free(n);
    }
    // IDLE / CALLING / SUSPENDED: no-op.
    return { pump: this.pumpJson(p) };
  }

  resume(n) {
    this.sync();
    const p = this.pumpOf(n);
    this.guardPos(p);
    if (p.state === 'SUSPENDED') {
      this.setState(p, 'FUELLING');
      p.flowing = p.trigger && !p.current.limitReached;
      p.lastFlowAt = this.now();
      this.emitPump(p);
    } else if (p.state !== 'FUELLING') {
      throw new FdcError('INVALID_STATE', `Pump ${p.pump} is ${p.state}, not suspended`);
    }
    return { pump: this.pumpJson(p) };
  }

  emergencyStop(n) {
    this.sync();
    const p = this.pumpOf(n);
    this.guardPos(p, { allowEstop: true });
    this.estop(p);
    return { pump: this.pumpJson(p) };
  }

  /** All pumps. Offline / error pumps are skipped (they cannot take commands). */
  emergencyStopAll() {
    this.sync();
    const stopped = [];
    for (const p of this.pumps) {
      if (p.state === 'OFFLINE' || p.state === 'ERROR') continue;
      this.estop(p);
      stopped.push(p.pump);
    }
    return { stopped, pumps: this.pumps.map((p) => this.pumpJson(p)) };
  }

  estop(p) {
    if (p.state === 'EMERGENCY_STOP') return;
    if (p.current) this.completeSale(p, 'EMERGENCY_STOP');
    p.authorisation = null;
    p.flowing = false;
    this.setState(p, 'EMERGENCY_STOP');
    this.emitPump(p);
  }

  /** Leave EMERGENCY_STOP or ERROR. A no-op in normal states. */
  resetPump(n) {
    this.sync();
    const p = this.pumpOf(n);
    if (p.state === 'OFFLINE') throw new FdcError('PUMP_OFFLINE', `Pump ${p.pump} is offline`);
    if (p.state === 'EMERGENCY_STOP' || p.state === 'ERROR') {
      p.error = null;
      this.setState(p, this.restState(p));
      this.emitPump(p);
    }
    return { pump: this.pumpJson(p) };
  }

  // ---- transactions ------------------------------------------------------

  trxOf(id) {
    const t = this.trx.get(id);
    if (!t) throw new FdcError('NOT_FOUND', `No transaction ${id}`);
    return t;
  }

  listTrx({ state = null, pump = null } = {}) {
    this.sync();
    const states = state ? [state] : ['PAYABLE', 'LOCKED'];
    const out = [];
    for (const t of this.trx.values()) {
      if (!states.includes(t.state)) continue;
      if (pump != null && t.pump !== pump) continue;
      out.push({ ...t });
    }
    return out;
  }

  lock(id, { posRef = null } = {}) {
    const t = this.trxOf(id);
    if (t.state === 'LOCKED') {
      if (t.lockedBy === (posRef ?? null)) return { transaction: { ...t } };
      throw new FdcError('TRX_LOCKED', `Transaction ${id} is locked by ${t.lockedBy}`);
    }
    if (t.state !== 'PAYABLE') throw new FdcError('INVALID_STATE', `Transaction ${id} is ${t.state}`);
    t.state = 'LOCKED';
    t.lockedBy = posRef ?? null;
    this.emit('transaction', { ...t });
    return { transaction: { ...t } };
  }

  unlock(id) {
    const t = this.trxOf(id);
    if (t.state === 'CLEARED') throw new FdcError('INVALID_STATE', `Transaction ${id} is CLEARED`);
    if (t.state === 'LOCKED') {
      t.state = 'PAYABLE';
      t.lockedBy = null;
      this.emit('transaction', { ...t });
    }
    return { transaction: { ...t } };
  }

  clear(id) {
    const t = this.trxOf(id);
    if (t.state !== 'CLEARED') {
      t.state = 'CLEARED';
      t.clearedAt = this.iso();
      this.emit('transaction', { ...t });
      this.pruneCleared();
    }
    return { transaction: { ...t } };
  }

  pruneCleared() {
    let cleared = 0;
    for (const t of this.trx.values()) if (t.state === 'CLEARED') cleared++;
    for (const [id, t] of this.trx) {
      if (cleared <= KEEP_CLEARED) break;
      if (t.state === 'CLEARED') {
        this.trx.delete(id);
        cleared--;
      }
    }
  }

  // ---- prices and totals -------------------------------------------------

  gradesJson() {
    return this.grades.map((g) => ({ ...g }));
  }

  /** Applies to the next sale that starts; a live sale keeps its price. */
  changePrices(prices) {
    if (!Array.isArray(prices) || prices.length === 0) {
      throw new FdcError('BAD_REQUEST', 'prices must be a non-empty array');
    }
    for (const { grade, priceMills } of prices) {
      if (!this.gradeOf(grade)) throw new FdcError('BAD_REQUEST', `Unknown grade ${grade}`);
      if (!(Number.isInteger(priceMills) && priceMills > 0 && priceMills <= 99999)) {
        throw new FdcError('BAD_REQUEST', 'priceMills must be an integer 1..99999');
      }
    }
    this.sync();
    for (const { grade, priceMills } of prices) this.gradeOf(grade).priceMills = priceMills;
    // A lifted nozzle that has not started shows the new price.
    for (const p of this.pumps) {
      if (p.nozzleUp != null && !p.current && p.display.volumeMilli === 0 && p.display.grade) {
        p.display.priceMills = this.gradeOf(p.display.grade).priceMills;
        this.emitPump(p);
      }
    }
    return this.gradesJson();
  }

  totalsJson() {
    const grand = { volumeMilli: 0, amountCents: 0, count: 0 };
    const pumps = this.totals.map((m, i) => ({
      pump: i + 1,
      grades: [...m].map(([grade, v]) => {
        grand.volumeMilli += v.volumeMilli;
        grand.amountCents += v.amountCents;
        grand.count += v.count;
        return { grade, ...v };
      }),
    }));
    return { pumps, grandTotal: grand };
  }

  // ---- the customer at the pump (simulator only) -------------------------

  lift(n, { nozzle, grade } = {}) {
    this.sync();
    const p = this.pumpOf(n);
    let noz = nozzle;
    if (noz == null && grade != null) {
      noz = NOZZLE_GRADES.indexOf(grade) + 1;
      if (noz === 0) throw new FdcError('BAD_REQUEST', `Unknown grade ${grade}`);
    }
    if (!(Number.isInteger(noz) && noz >= 1 && noz <= NOZZLE_GRADES.length)) {
      throw new FdcError('BAD_REQUEST', 'Give nozzle (1-4) or grade');
    }
    if (p.nozzleUp != null) throw new FdcError('INVALID_STATE', `Nozzle ${p.nozzleUp} is already up; hang up first`);
    if (p.state === 'IDLE' && this.unpaidPostpay(p) >= MAX_UNPAID) {
      throw new FdcError('TOO_MANY_UNPAID', `Pump ${p.pump} has ${MAX_UNPAID} unpaid sales`);
    }
    p.nozzleUp = noz;
    p.trigger = false;
    const g = this.gradeOf(NOZZLE_GRADES[noz - 1]);
    if (p.state === 'IDLE' || p.state === 'AUTHORISED') {
      p.display = { grade: g.grade, priceMills: g.priceMills, volumeMilli: 0, amountCents: 0 };
    }
    if (p.state === 'IDLE') this.setState(p, 'CALLING');
    this.emitPump(p);
    return { pump: this.pumpJson(p) };
  }

  trigger(n, on) {
    this.sync();
    const p = this.pumpOf(n);
    if (typeof on !== 'boolean') throw new FdcError('BAD_REQUEST', 'on must be true or false');
    p.trigger = on && p.nozzleUp != null;
    if (p.state === 'AUTHORISED' && p.trigger) {
      this.startSale(p);
    } else if (p.state === 'FUELLING') {
      p.flowing = p.trigger && !p.current.limitReached;
      p.lastFlowAt = this.now();
    }
    this.emitPump(p);
    return { pump: this.pumpJson(p) };
  }

  hangup(n) {
    this.sync();
    const p = this.pumpOf(n);
    const wasUp = p.nozzleUp != null;
    p.nozzleUp = null;
    p.trigger = false;
    p.flowing = false;
    if (p.state === 'FUELLING' || p.state === 'SUSPENDED') {
      this.completeSale(p, p.current.limitReached ? 'LIMIT' : 'HANGUP');
      p.authorisation = null;
      this.setState(p, 'IDLE');
    } else if (p.state === 'CALLING') {
      this.setState(p, 'IDLE');
    }
    // AUTHORISED keeps its authorisation; ESTOP / OFFLINE / ERROR just drop the nozzle.
    if (wasUp) this.emitPump(p);
    return { pump: this.pumpJson(p) };
  }

  setOffline(n, on) {
    this.sync();
    const p = this.pumpOf(n);
    if (typeof on !== 'boolean') throw new FdcError('BAD_REQUEST', 'on must be true or false');
    if (on && p.state !== 'OFFLINE') {
      this.fault(p, 'OFFLINE', null);
    } else if (!on && p.state === 'OFFLINE') {
      this.setState(p, this.restState(p));
      this.emitPump(p);
    }
    return { pump: this.pumpJson(p) };
  }

  setError(n, on, message) {
    this.sync();
    const p = this.pumpOf(n);
    if (typeof on !== 'boolean') throw new FdcError('BAD_REQUEST', 'on must be true or false');
    if (on) {
      this.fault(p, 'ERROR', message || 'Dispenser fault');
    } else if (p.state === 'ERROR') {
      p.error = null;
      this.setState(p, this.restState(p));
      this.emitPump(p);
    }
    return { pump: this.pumpJson(p) };
  }

  fault(p, state, message) {
    if (p.current) this.completeSale(p, state);
    p.authorisation = null;
    p.flowing = false;
    p.error = message;
    this.setState(p, state);
    this.emitPump(p);
  }

  setSpeed(multiplier, pump = null) {
    if (typeof multiplier !== 'number' || !(multiplier >= 1 && multiplier <= 20)) {
      throw new FdcError('BAD_REQUEST', 'multiplier must be a number 1..20');
    }
    this.sync();
    if (pump == null) this.speed = multiplier;
    else this.pumpOf(pump).speed = multiplier;
    return { speed: this.speed, pumpSpeeds: this.pumps.map((p) => p.speed) };
  }

  // ---- sales -------------------------------------------------------------

  startSale(p) {
    const a = p.authorisation;
    const g = this.gradeOf(NOZZLE_GRADES[p.nozzleUp - 1]);
    p.current = {
      trxId: `T-${pad6(++this.trxSeq)}`,
      nozzle: p.nozzleUp,
      grade: g.grade,
      gradeName: g.name,
      priceMills: g.priceMills,
      volumeMilli: 0,
      amountCents: 0,
      maxAmountCents: a.maxAmountCents,
      limitReached: false,
      startedAt: this.iso(),
    };
    p.micro = 0;
    p.flowing = true;
    p.lastFlowAt = this.now();
    p.display = { grade: g.grade, priceMills: g.priceMills, volumeMilli: 0, amountCents: 0 };
    this.setState(p, 'FUELLING');
  }

  /** Move the live sale into the transaction buffer as PAYABLE. */
  completeSale(p, reason) {
    const c = p.current;
    const a = p.authorisation;
    const t = {
      trxId: c.trxId,
      pump: p.pump,
      nozzle: c.nozzle,
      grade: c.grade,
      gradeName: c.gradeName,
      priceMills: c.priceMills,
      volumeMilli: c.volumeMilli,
      amountCents: c.amountCents,
      state: 'PAYABLE',
      mode: a.mode,
      maxAmountCents: a.maxAmountCents,
      authId: a.authId,
      posRef: a.posRef,
      lockedBy: null,
      reason,
      startedAt: c.startedAt,
      completedAt: this.iso(),
      clearedAt: null,
    };
    this.trx.set(t.trxId, t);
    const tot = this.totals[p.pump - 1].get(t.grade);
    tot.volumeMilli += t.volumeMilli;
    tot.amountCents += t.amountCents;
    tot.count += 1;
    p.display = { grade: c.grade, priceMills: c.priceMills, volumeMilli: c.volumeMilli, amountCents: c.amountCents };
    p.current = null;
    p.flowing = false;
    p.micro = 0;
    this.emit('transaction', { ...t });
    return t;
  }

  // ---- JSON --------------------------------------------------------------

  fdcStateOf(p) {
    if (p.state === 'AUTHORISED' && p.nozzleUp != null) return 'FDC_STARTED';
    return FDC_STATE[p.state];
  }

  pumpJson(p) {
    const c = p.current;
    return {
      pump: p.pump,
      state: p.state,
      fdcState: this.fdcStateOf(p),
      nozzleUp: p.nozzleUp,
      flowing: p.flowing,
      authorisation: p.authorisation ? { ...p.authorisation } : null,
      current: c
        ? {
            trxId: c.trxId,
            nozzle: c.nozzle,
            grade: c.grade,
            gradeName: c.gradeName,
            priceMills: c.priceMills,
            volumeMilli: c.volumeMilli,
            amountCents: c.amountCents,
            maxAmountCents: c.maxAmountCents,
            limitReached: c.limitReached,
          }
        : null,
      display: { ...p.display },
      error: p.error,
      stateSince: this.iso(p.stateSince),
      nozzles: NOZZLE_GRADES.map((grade, i) => ({ nozzle: i + 1, grade })),
    };
  }

  pumpsJson() {
    this.sync();
    return this.pumps.map((p) => this.pumpJson(p));
  }

  status() {
    this.sync();
    return {
      fdcId: 'SIM-FDC-1',
      version: VERSION,
      time: this.iso(),
      speed: this.speed,
      grades: this.gradesJson(),
      pumps: this.pumps.map((p) => this.pumpJson(p)),
      transactions: this.listTrx(),
    };
  }
}

function clampSpeed(s) {
  const n = Number(s);
  if (!Number.isFinite(n)) return 1;
  return Math.min(20, Math.max(1, n));
}
