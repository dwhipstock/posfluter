import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Forecourt, amountFor, FdcError } from '../lib/forecourt.js';

// A fake clock: the model only advances when we move time.
function setup(opts = {}) {
  const clock = { t: Date.parse('2026-09-26T15:00:00.000Z') };
  const events = [];
  const fc = new Forecourt({ pumps: 4, now: () => clock.t, ...opts });
  fc.on((type, data) => events.push({ type, data }));
  const advance = (ms) => {
    clock.t += ms;
    fc.tick();
  };
  const pump = (n) => fc.pumpsJson()[n - 1];
  return { fc, clock, events, advance, pump };
}

function throwsCode(fn, code) {
  assert.throws(fn, (e) => e instanceof FdcError && e.code === code);
}

test('amount is round-half-up of volume x price / 10000', () => {
  assert.equal(amountFor(10052, 3299), 3316); // 33.161548 -> 3316
  assert.equal(amountFor(0, 2899), 0);
  assert.equal(amountFor(1, 5000), 1); // 0.5 -> 1 (half up)
  assert.equal(amountFor(1, 4999), 0);
  assert.equal(amountFor(1000, 2899), 290); // 1 gal at 2.899 -> 289.9 -> 290
});

test('boot state: pumps idle, display blank, nozzles mapped', () => {
  const { pump, fc } = setup();
  const p = pump(1);
  assert.equal(p.state, 'IDLE');
  assert.equal(p.fdcState, 'FDC_READY');
  assert.deepEqual(p.display, { grade: null, priceMills: 0, volumeMilli: 0, amountCents: 0 });
  assert.deepEqual(p.nozzles.map((z) => z.grade), ['REG', 'MID', 'PRE', 'DSL']);
  assert.equal(fc.status().grades.find((g) => g.grade === 'MID').priceMills, 3299);
});

test('full postpay cycle: idle -> calling -> authorised -> fuelling -> payable -> cleared', () => {
  const { fc, pump, advance } = setup();
  fc.lift(1, { nozzle: 2 });
  assert.equal(pump(1).state, 'CALLING');
  assert.equal(pump(1).nozzleUp, 2);
  assert.deepEqual(pump(1).display, { grade: 'MID', priceMills: 3299, volumeMilli: 0, amountCents: 0 });

  const { authId } = fc.authorise(1, { mode: 'POSTPAY', posRef: 'sale-1' });
  assert.match(authId, /^A-\d{6}$/);
  assert.equal(pump(1).state, 'AUTHORISED');
  assert.equal(pump(1).fdcState, 'FDC_STARTED');
  assert.equal(pump(1).authorisation.mode, 'POSTPAY');
  assert.equal(pump(1).authorisation.maxAmountCents, null);

  fc.trigger(1, true);
  assert.equal(pump(1).state, 'FUELLING');
  assert.equal(pump(1).flowing, true);
  const trxId = pump(1).current.trxId;
  assert.match(trxId, /^T-\d{6}$/);

  advance(10_000); // 10 s at 160 milli/s
  assert.equal(pump(1).current.volumeMilli, 1600);
  assert.equal(pump(1).current.amountCents, amountFor(1600, 3299));
  assert.equal(pump(1).display.volumeMilli, 1600);

  fc.trigger(1, false);
  assert.equal(pump(1).flowing, false);
  advance(5000);
  assert.equal(pump(1).current.volumeMilli, 1600, 'no flow with trigger released');
  fc.trigger(1, true);
  advance(5000);
  assert.equal(pump(1).current.volumeMilli, 2400);

  fc.hangup(1);
  const p = pump(1);
  assert.equal(p.state, 'IDLE');
  assert.equal(p.authorisation, null);
  assert.equal(p.current, null);
  assert.equal(p.display.volumeMilli, 2400, 'display keeps the completed sale');

  const [t] = fc.listTrx();
  assert.equal(t.trxId, trxId);
  assert.equal(t.state, 'PAYABLE');
  assert.equal(t.mode, 'POSTPAY');
  assert.equal(t.reason, 'HANGUP');
  assert.equal(t.volumeMilli, 2400);
  assert.equal(t.amountCents, amountFor(2400, 3299));
  assert.equal(t.authId, authId);
  assert.equal(t.posRef, 'sale-1');
  assert.equal(t.gradeName, 'Mid-Grade');

  fc.clear(trxId);
  assert.equal(fc.listTrx().length, 0);
  const cleared = fc.listTrx({ state: 'CLEARED' });
  assert.equal(cleared[0].state, 'CLEARED');
  assert.ok(cleared[0].clearedAt);

  // Next lift resets the display.
  fc.lift(1, { grade: 'REG' });
  assert.deepEqual(pump(1).display, { grade: 'REG', priceMills: 2899, volumeMilli: 0, amountCents: 0 });
});

test('flow is by elapsed time, not tick count', () => {
  const { fc, clock, pump } = setup();
  fc.lift(1, { nozzle: 1 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  clock.t += 3333; // one long gap, no ticks in between
  assert.equal(pump(1).current.volumeMilli, 533); // 3333 * 0.16 = 533.28
});

test('prepay: authorise with nozzle down, then stops exactly on the limit', () => {
  const { fc, pump, advance } = setup();
  fc.authorise(1, { mode: 'PREPAY', maxAmountCents: 4000, posRef: 'sale-42' });
  assert.equal(pump(1).state, 'AUTHORISED');
  assert.equal(pump(1).nozzleUp, null);
  assert.equal(pump(1).fdcState, 'FDC_AUTHORISED');

  fc.lift(1, { grade: 'REG' });
  assert.equal(pump(1).state, 'AUTHORISED');
  fc.trigger(1, true);
  advance(120_000); // 19.2 gal, well over $40
  const c = pump(1).current;
  assert.equal(c.amountCents, 4000);
  assert.equal(c.volumeMilli, Math.floor((4000 * 10000) / 2899)); // 13797
  assert.equal(c.limitReached, true);
  assert.equal(pump(1).flowing, false);

  fc.trigger(1, false);
  fc.trigger(1, true);
  assert.equal(pump(1).flowing, false, 'trigger does nothing after the limit');
  advance(5000);
  assert.equal(pump(1).current.amountCents, 4000);

  fc.hangup(1);
  const [t] = fc.listTrx();
  assert.equal(t.reason, 'LIMIT');
  assert.equal(t.mode, 'PREPAY');
  assert.equal(t.amountCents, 4000);
  assert.equal(t.maxAmountCents, 4000);
});

test('prepay hangup before flow keeps the authorisation; free cancels it', () => {
  const { fc, pump } = setup();
  fc.authorise(2, { mode: 'PREPAY', maxAmountCents: 2000 });
  fc.lift(2, { nozzle: 1 });
  fc.hangup(2);
  assert.equal(pump(2).state, 'AUTHORISED');
  assert.ok(pump(2).authorisation);
  fc.free(2);
  assert.equal(pump(2).state, 'IDLE');
  assert.equal(pump(2).authorisation, null);
  assert.equal(fc.listTrx().length, 0);
  throwsCode(() => fc.free(2), 'NOT_AUTHORISED');
});

test('free with nozzle up goes back to CALLING', () => {
  const { fc, pump } = setup();
  fc.lift(1, { nozzle: 1 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.free(1);
  assert.equal(pump(1).state, 'CALLING');
});

test('zero-volume sale still completes (prepay refundable in full)', () => {
  const { fc } = setup();
  fc.authorise(1, { mode: 'PREPAY', maxAmountCents: 3000 });
  fc.lift(1, { nozzle: 1 });
  fc.trigger(1, true);
  fc.trigger(1, false);
  fc.hangup(1);
  const [t] = fc.listTrx();
  assert.equal(t.volumeMilli, 0);
  assert.equal(t.amountCents, 0);
  assert.equal(t.reason, 'HANGUP');
});

test('calling hangup returns to idle', () => {
  const { fc, pump } = setup();
  fc.lift(1, { nozzle: 3 });
  fc.hangup(1);
  assert.equal(pump(1).state, 'IDLE');
  assert.equal(pump(1).nozzleUp, null);
});

test('trigger held before authorisation starts flow when authorised (hold-open clip)', () => {
  const { fc, pump } = setup();
  fc.lift(1, { nozzle: 1 });
  fc.trigger(1, true);
  assert.equal(pump(1).state, 'CALLING');
  fc.authorise(1, { mode: 'POSTPAY' });
  assert.equal(pump(1).state, 'FUELLING');
  assert.equal(pump(1).flowing, true);
});

test('stop / resume', () => {
  const { fc, pump, advance } = setup();
  fc.lift(1, { nozzle: 1 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  advance(1000);
  fc.stop(1);
  assert.equal(pump(1).state, 'SUSPENDED');
  assert.equal(pump(1).fdcState, 'FDC_SUSPENDED_FUELLING');
  assert.equal(pump(1).flowing, false);
  advance(5000);
  assert.equal(pump(1).current.volumeMilli, 160);
  fc.stop(1); // idempotent
  fc.resume(1);
  assert.equal(pump(1).state, 'FUELLING');
  assert.equal(pump(1).flowing, true);
  advance(1000);
  assert.equal(pump(1).current.volumeMilli, 320);
  fc.resume(1); // no-op while fuelling
  fc.stop(1);
  fc.hangup(1); // hangup in SUSPENDED completes
  assert.equal(pump(1).state, 'IDLE');
  assert.equal(fc.listTrx()[0].volumeMilli, 320);
});

test('stop on AUTHORISED frees; on IDLE/CALLING is a no-op; resume elsewhere is INVALID_STATE', () => {
  const { fc, pump } = setup();
  fc.stop(1);
  assert.equal(pump(1).state, 'IDLE');
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.stop(1);
  assert.equal(pump(1).state, 'IDLE');
  assert.equal(pump(1).authorisation, null);
  throwsCode(() => fc.resume(1), 'INVALID_STATE');
});

test('free while fuelling is INVALID_STATE', () => {
  const { fc } = setup();
  fc.lift(1, { nozzle: 1 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  throwsCode(() => fc.free(1), 'INVALID_STATE');
});

test('authorise validation and busy rules', () => {
  const { fc } = setup();
  throwsCode(() => fc.authorise(1, { mode: 'CASH' }), 'BAD_REQUEST');
  throwsCode(() => fc.authorise(1, { mode: 'PREPAY' }), 'BAD_REQUEST');
  throwsCode(() => fc.authorise(1, { mode: 'PREPAY', maxAmountCents: 0 }), 'BAD_REQUEST');
  throwsCode(() => fc.authorise(1, { mode: 'PREPAY', maxAmountCents: 10.5 }), 'BAD_REQUEST');
  throwsCode(() => fc.authorise(99, { mode: 'POSTPAY' }), 'NOT_FOUND');
  fc.authorise(1, { mode: 'POSTPAY' });
  throwsCode(() => fc.authorise(1, { mode: 'POSTPAY' }), 'PUMP_BUSY');
  fc.lift(1, { nozzle: 1 });
  fc.trigger(1, true);
  throwsCode(() => fc.authorise(1, { mode: 'POSTPAY' }), 'PUMP_BUSY');
  fc.stop(1);
  throwsCode(() => fc.authorise(1, { mode: 'POSTPAY' }), 'PUMP_BUSY');
});

function postpaySale(fc, advance, n, ms = 1000) {
  fc.lift(n, { nozzle: 1 });
  fc.authorise(n, { mode: 'POSTPAY' });
  fc.trigger(n, true);
  advance(ms);
  fc.hangup(n);
}

test('stacking: at most 2 unpaid postpay sales per pump', () => {
  const { fc, advance, pump } = setup();
  postpaySale(fc, advance, 1);
  postpaySale(fc, advance, 1);
  assert.equal(fc.listTrx({ pump: 1 }).length, 2);
  throwsCode(() => fc.authorise(1, { mode: 'POSTPAY' }), 'TOO_MANY_UNPAID');
  throwsCode(() => fc.lift(1, { nozzle: 1 }), 'TOO_MANY_UNPAID');
  // Other pumps unaffected.
  fc.authorise(2, { mode: 'POSTPAY' });
  // Pay one: stacking frees up.
  fc.clear(fc.listTrx({ pump: 1 })[0].trxId);
  fc.lift(1, { nozzle: 1 });
  assert.equal(pump(1).state, 'CALLING');
  fc.authorise(1, { mode: 'POSTPAY' });
});

test('lock / unlock / clear rules', () => {
  const { fc, advance } = setup();
  postpaySale(fc, advance, 1);
  const id = fc.listTrx()[0].trxId;
  assert.equal(fc.lock(id, { posRef: 'till-1' }).transaction.state, 'LOCKED');
  assert.equal(fc.lock(id, { posRef: 'till-1' }).transaction.lockedBy, 'till-1'); // idempotent
  throwsCode(() => fc.lock(id, { posRef: 'till-2' }), 'TRX_LOCKED');
  assert.equal(fc.listTrx()[0].state, 'LOCKED', 'LOCKED still listed by default');
  assert.equal(fc.unlock(id).transaction.state, 'PAYABLE');
  assert.equal(fc.unlock(id).transaction.state, 'PAYABLE'); // idempotent
  fc.lock(id, { posRef: 'till-2' });
  const c = fc.clear(id).transaction;
  assert.equal(c.state, 'CLEARED');
  assert.equal(fc.clear(id).transaction.clearedAt, c.clearedAt); // idempotent
  throwsCode(() => fc.lock(id, { posRef: 'x' }), 'INVALID_STATE');
  throwsCode(() => fc.unlock(id), 'INVALID_STATE');
  throwsCode(() => fc.lock('T-999999', {}), 'NOT_FOUND');
  throwsCode(() => fc.clear('nope'), 'NOT_FOUND');
});

test('emergency stop on one pump completes the live sale and blocks until reset', () => {
  const { fc, pump, advance } = setup();
  fc.lift(1, { nozzle: 1 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  advance(2000);
  fc.emergencyStop(1);
  const p = pump(1);
  assert.equal(p.state, 'EMERGENCY_STOP');
  assert.equal(p.authorisation, null);
  assert.equal(p.nozzleUp, 1, 'nozzle state kept');
  assert.equal(p.flowing, false);
  const [t] = fc.listTrx();
  assert.equal(t.reason, 'EMERGENCY_STOP');
  assert.equal(t.volumeMilli, 320);
  advance(5000);
  throwsCode(() => fc.authorise(1, { mode: 'POSTPAY' }), 'EMERGENCY_STOP');
  throwsCode(() => fc.trigger(1, 'yes'), 'BAD_REQUEST');
  fc.trigger(1, true);
  assert.equal(pump(1).state, 'EMERGENCY_STOP');
  fc.resetPump(1);
  assert.equal(pump(1).state, 'CALLING', 'nozzle still up');
  fc.hangup(1);
  fc.emergencyStop(1);
  fc.resetPump(1);
  assert.equal(pump(1).state, 'IDLE');
});

test('emergency stop all', () => {
  const { fc, pump, advance } = setup();
  fc.lift(1, { nozzle: 1 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  fc.authorise(2, { mode: 'PREPAY', maxAmountCents: 1000 });
  fc.setOffline(4, true);
  advance(1000);
  const r = fc.emergencyStopAll();
  assert.deepEqual(r.stopped, [1, 2, 3]);
  for (const n of [1, 2, 3]) assert.equal(pump(n).state, 'EMERGENCY_STOP');
  assert.equal(pump(4).state, 'OFFLINE');
  assert.equal(fc.listTrx().length, 1);
  assert.equal(pump(2).authorisation, null);
});

test('offline: completes live sale, blocks POS commands, toggle off returns to idle', () => {
  const { fc, pump, advance } = setup();
  fc.lift(1, { nozzle: 4 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  advance(1000);
  fc.setOffline(1, true);
  assert.equal(pump(1).state, 'OFFLINE');
  assert.equal(pump(1).fdcState, 'FDC_OFFLINE');
  const [t] = fc.listTrx();
  assert.equal(t.reason, 'OFFLINE');
  assert.equal(t.grade, 'DSL');
  for (const cmd of [
    () => fc.authorise(1, { mode: 'POSTPAY' }),
    () => fc.free(1),
    () => fc.stop(1),
    () => fc.resume(1),
    () => fc.emergencyStop(1),
    () => fc.resetPump(1),
  ]) throwsCode(cmd, 'PUMP_OFFLINE');
  fc.hangup(1);
  fc.setOffline(1, false);
  assert.equal(pump(1).state, 'IDLE');
});

test('error: like offline with a message; reset clears it', () => {
  const { fc, pump } = setup();
  fc.setError(2, true, 'Pulser fault');
  assert.equal(pump(2).state, 'ERROR');
  assert.equal(pump(2).error, 'Pulser fault');
  throwsCode(() => fc.authorise(2, { mode: 'POSTPAY' }), 'PUMP_ERROR');
  fc.resetPump(2);
  assert.equal(pump(2).state, 'IDLE');
  assert.equal(pump(2).error, null);
  fc.setError(2, true, 'x');
  fc.setError(2, false);
  assert.equal(pump(2).state, 'IDLE');
});

test('price change applies to the next sale, not the live one', () => {
  const { fc, pump, advance } = setup();
  fc.lift(1, { nozzle: 1 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  advance(1000);
  const grades = fc.changePrices([{ grade: 'REG', priceMills: 2949 }]);
  assert.equal(grades.find((g) => g.grade === 'REG').priceMills, 2949);
  advance(1000);
  assert.equal(pump(1).current.priceMills, 2899);
  fc.hangup(1);
  assert.equal(fc.listTrx()[0].priceMills, 2899);
  fc.lift(1, { nozzle: 1 });
  assert.equal(pump(1).display.priceMills, 2949);
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  assert.equal(pump(1).current.priceMills, 2949);
  throwsCode(() => fc.changePrices([{ grade: 'XYZ', priceMills: 1 }]), 'BAD_REQUEST');
  throwsCode(() => fc.changePrices([{ grade: 'REG', priceMills: 2.5 }]), 'BAD_REQUEST');
  throwsCode(() => fc.changePrices([]), 'BAD_REQUEST');
});

test('totals are cumulative per pump per grade and survive clearing', () => {
  const { fc, advance } = setup();
  postpaySale(fc, advance, 1, 1000); // 160 REG
  postpaySale(fc, advance, 1, 2000); // 320 REG
  for (const t of fc.listTrx()) fc.clear(t.trxId);
  postpaySale(fc, advance, 2, 1000);
  const tot = fc.totalsJson();
  const reg1 = tot.pumps[0].grades.find((g) => g.grade === 'REG');
  assert.deepEqual(reg1, { grade: 'REG', volumeMilli: 480, amountCents: amountFor(160, 2899) + amountFor(320, 2899), count: 2 });
  assert.equal(tot.pumps[0].grades.length, 4);
  assert.equal(tot.grandTotal.count, 3);
  assert.equal(tot.grandTotal.volumeMilli, 640);
});

test('speed multiplier: global and per pump', () => {
  const { fc, pump, advance } = setup({ speed: 5 });
  fc.lift(1, { nozzle: 1 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  advance(1000);
  assert.equal(pump(1).current.volumeMilli, 800);
  fc.setSpeed(2, 1); // pump 1 twice as fast again
  advance(1000);
  assert.equal(pump(1).current.volumeMilli, 800 + 1600);
  throwsCode(() => fc.setSpeed(50), 'BAD_REQUEST');
});

test('events: state changes emit pump events, flowing is throttled to 250 ms', () => {
  const { fc, events, advance } = setup();
  fc.lift(1, { nozzle: 1 });
  fc.authorise(1, { mode: 'POSTPAY' });
  fc.trigger(1, true);
  events.length = 0;
  for (let i = 0; i < 10; i++) advance(100); // 1 s of 100 ms ticks
  const pumpEvents = events.filter((e) => e.type === 'pump');
  assert.ok(pumpEvents.length >= 3 && pumpEvents.length <= 4, `got ${pumpEvents.length}`);
  events.length = 0;
  fc.hangup(1);
  assert.ok(events.some((e) => e.type === 'transaction' && e.data.state === 'PAYABLE'));
  assert.ok(events.some((e) => e.type === 'pump' && e.data.state === 'IDLE'));
});

test('lift validation', () => {
  const { fc } = setup();
  throwsCode(() => fc.lift(1, {}), 'BAD_REQUEST');
  throwsCode(() => fc.lift(1, { nozzle: 9 }), 'BAD_REQUEST');
  throwsCode(() => fc.lift(1, { grade: 'E85' }), 'BAD_REQUEST');
  fc.lift(1, { nozzle: 1 });
  throwsCode(() => fc.lift(1, { nozzle: 2 }), 'INVALID_STATE');
});

test('reset returns everything to boot state', () => {
  const { fc, advance, pump } = setup();
  postpaySale(fc, advance, 1);
  fc.changePrices([{ grade: 'REG', priceMills: 3000 }]);
  fc.setSpeed(10);
  fc.reset();
  assert.equal(fc.listTrx().length, 0);
  assert.equal(fc.totalsJson().grandTotal.count, 0);
  assert.equal(fc.gradesJson()[0].priceMills, 2899);
  assert.equal(fc.speed, 1);
  assert.equal(pump(1).display.grade, null);
  assert.equal(fc.authorise(1, { mode: 'POSTPAY' }).authId, 'A-000001');
});
