import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createSimulator } from '../lib/api.js';

let sim;
let base;

before(async () => {
  sim = createSimulator({ pumps: 8, speed: 20 });
  const port = await sim.listen(0, '127.0.0.1');
  base = `http://127.0.0.1:${port}`;
});
after(() => sim.close());

async function call(method, path, body) {
  const res = await fetch(base + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, headers: res.headers, json: await res.json() };
}
const get = (p) => call('GET', p);
const post = (p, b) => call('POST', p, b ?? {});

test('healthz and CORS preflight', async () => {
  const h = await get('/healthz');
  assert.equal(h.status, 200);
  assert.deepEqual(h.json, { ok: true });
  const res = await fetch(`${base}/fdc/v1/pumps/1/authorise`, { method: 'OPTIONS' });
  assert.equal(res.status, 204);
  assert.equal(res.headers.get('access-control-allow-origin'), '*');
});

test('status snapshot shape', async () => {
  await post('/sim/v1/reset');
  const { status, json, headers } = await get('/fdc/v1/status');
  assert.equal(status, 200);
  assert.equal(headers.get('access-control-allow-origin'), '*');
  assert.equal(json.result, 'Success');
  assert.equal(json.fdcMessage, 'GetFDCStatus');
  assert.equal(json.fdcId, 'SIM-FDC-1');
  assert.equal(typeof json.version, 'string');
  assert.ok(!Number.isNaN(Date.parse(json.time)));
  assert.equal(json.speed, 20);
  assert.equal(json.grades.length, 4);
  assert.deepEqual(Object.keys(json.grades[0]).sort(), ['grade', 'name', 'nameEs', 'priceMills', 'productNo']);
  assert.equal(json.pumps.length, 8);
  assert.deepEqual(json.transactions, []);
  const p = json.pumps[2];
  assert.deepEqual(Object.keys(p).sort(), [
    'authorisation', 'current', 'display', 'error', 'fdcState', 'flowing',
    'nozzleUp', 'nozzles', 'pump', 'state', 'stateSince',
  ]);
  assert.equal(p.pump, 3);
});

test('pumps endpoints and 404s', async () => {
  const all = await get('/fdc/v1/pumps');
  assert.equal(all.json.fdcMessage, 'GetFPState');
  assert.equal(all.json.pumps.length, 8);
  const one = await get('/fdc/v1/pumps/3');
  assert.equal(one.json.pump.pump, 3);
  const missing = await get('/fdc/v1/pumps/99');
  assert.equal(missing.status, 404);
  assert.deepEqual(Object.keys(missing.json.error).sort(), ['code', 'message']);
  assert.equal(missing.json.result, 'Failure');
  assert.equal(missing.json.error.code, 'NOT_FOUND');
  assert.equal((await get('/nope')).status, 404);
});

test('postpay sale over HTTP, then lock and clear', async () => {
  await post('/sim/v1/reset');
  assert.equal((await post('/sim/v1/pumps/3/lift', { grade: 'MID' })).json.pump.state, 'CALLING');
  const auth = await post('/fdc/v1/pumps/3/authorise', { mode: 'POSTPAY', posRef: 'sale-42' });
  assert.equal(auth.status, 200);
  assert.equal(auth.json.fdcMessage, 'AuthoriseFuelPoint');
  assert.match(auth.json.authId, /^A-/);
  assert.equal(auth.json.pump.state, 'AUTHORISED');

  const busy = await post('/fdc/v1/pumps/3/authorise', { mode: 'POSTPAY' });
  assert.equal(busy.status, 409);
  assert.equal(busy.json.error.code, 'PUMP_BUSY');

  await post('/sim/v1/pumps/3/trigger', { on: true });
  await new Promise((r) => setTimeout(r, 300));
  const live = (await get('/fdc/v1/pumps/3')).json.pump;
  assert.equal(live.state, 'FUELLING');
  assert.ok(live.current.volumeMilli > 0);

  const stop = await post('/fdc/v1/pumps/3/stop');
  assert.equal(stop.json.fdcMessage, 'StopFuelPoint');
  assert.equal(stop.json.pump.state, 'SUSPENDED');
  const resume = await post('/fdc/v1/pumps/3/resume');
  assert.equal(resume.json.fdcMessage, 'StartFuelPoint');
  assert.equal(resume.json.pump.state, 'FUELLING');

  const hang = await post('/sim/v1/pumps/3/hangup');
  assert.equal(hang.json.pump.state, 'IDLE');

  const list = await get('/fdc/v1/transactions?pump=3');
  assert.equal(list.json.fdcMessage, 'GetAvailableFuelSaleTrxs');
  assert.equal(list.json.transactions.length, 1);
  const t = list.json.transactions[0];
  assert.equal(t.state, 'PAYABLE');
  assert.equal(t.posRef, 'sale-42');
  assert.ok(Number.isInteger(t.volumeMilli) && Number.isInteger(t.amountCents));

  const one = await get(`/fdc/v1/transactions/${t.trxId}`);
  assert.equal(one.json.fdcMessage, 'GetFuelSaleTrxDetails');
  assert.equal(one.json.transaction.trxId, t.trxId);

  const lock = await post(`/fdc/v1/transactions/${t.trxId}/lock`, { posRef: 'sale-42' });
  assert.equal(lock.json.fdcMessage, 'LockFuelSaleTrx');
  assert.equal(lock.json.transaction.state, 'LOCKED');
  const lock2 = await post(`/fdc/v1/transactions/${t.trxId}/lock`, { posRef: 'other' });
  assert.equal(lock2.status, 409);
  assert.equal(lock2.json.error.code, 'TRX_LOCKED');
  const unlock = await post(`/fdc/v1/transactions/${t.trxId}/unlock`);
  assert.equal(unlock.json.fdcMessage, 'UnlockFuelSaleTrx');
  assert.equal(unlock.json.transaction.state, 'PAYABLE');
  const clear = await post(`/fdc/v1/transactions/${t.trxId}/clear`);
  assert.equal(clear.json.fdcMessage, 'ClearFuelSaleTrx');
  assert.equal(clear.json.transaction.state, 'CLEARED');
  assert.equal((await get('/fdc/v1/transactions?state=CLEARED')).json.transactions.length, 1);
  assert.equal((await get('/fdc/v1/transactions?state=NOPE')).status, 400);
  assert.equal((await post('/fdc/v1/transactions/T-999999/clear')).status, 404);

  const totals = await get('/fdc/v1/totals');
  assert.equal(totals.json.fdcMessage, 'GetFuelPointTotals');
  assert.equal(totals.json.totals.grandTotal.count, 1);
  assert.equal(totals.json.totals.pumps[2].grades.find((g) => g.grade === 'MID').count, 1);
});

test('bad requests', async () => {
  await post('/sim/v1/reset');
  const r = await post('/fdc/v1/pumps/1/authorise', { mode: 'PREPAY' });
  assert.equal(r.status, 400);
  assert.equal(r.json.error.code, 'BAD_REQUEST');
  const bad = await fetch(`${base}/fdc/v1/pumps/1/authorise`, { method: 'POST', body: '{not json' });
  assert.equal(bad.status, 400);
  const free = await post('/fdc/v1/pumps/1/free');
  assert.equal(free.status, 409);
  assert.equal(free.json.error.code, 'NOT_AUTHORISED');
});

test('emergency stop all, reset, offline, error, prices', async () => {
  await post('/sim/v1/reset');
  const es = await post('/fdc/v1/emergency-stop');
  assert.equal(es.json.fdcMessage, 'EmergencyStop');
  assert.ok(es.json.pumps.every((p) => p.state === 'EMERGENCY_STOP'));
  const a = await post('/fdc/v1/pumps/1/authorise', { mode: 'POSTPAY' });
  assert.equal(a.json.error.code, 'EMERGENCY_STOP');
  const reset = await post('/fdc/v1/pumps/1/reset');
  assert.equal(reset.json.fdcMessage, 'CancelEmergencyStop');
  assert.equal(reset.json.pump.state, 'IDLE');
  const single = await post('/fdc/v1/pumps/1/emergency-stop');
  assert.equal(single.json.pump.state, 'EMERGENCY_STOP');

  await post('/sim/v1/pumps/2/offline', { on: true });
  assert.equal((await post('/fdc/v1/pumps/2/authorise', { mode: 'POSTPAY' })).json.error.code, 'PUMP_OFFLINE');
  await post('/sim/v1/pumps/3/error', { on: true, message: 'Pulser fault' });
  const e = await post('/fdc/v1/pumps/3/authorise', { mode: 'POSTPAY' });
  assert.equal(e.json.error.code, 'PUMP_ERROR');

  const prices = await post('/fdc/v1/prices', { prices: [{ grade: 'REG', priceMills: 2949 }] });
  assert.equal(prices.json.fdcMessage, 'ChangeFuelPrice');
  assert.equal(prices.json.grades[0].priceMills, 2949);
  const got = await get('/fdc/v1/prices');
  assert.equal(got.json.grades[0].priceMills, 2949);
  assert.equal((await post('/sim/v1/speed', { multiplier: 0 })).status, 400);
  assert.equal((await post('/sim/v1/speed', { multiplier: 20 })).status, 200);
});

test('panel is served', async () => {
  for (const p of ['/', '/panel', '/panel.js', '/panel.css']) {
    const res = await fetch(base + p);
    assert.equal(res.status, 200, p);
    await res.text();
  }
});

test('SSE: current pumps on connect, then live pump and transaction events', async () => {
  await post('/sim/v1/reset');
  const ctrl = new AbortController();
  const res = await fetch(`${base}/fdc/v1/events`, { signal: ctrl.signal });
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type'), /text\/event-stream/);
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  const events = [];
  const until = async (pred) => {
    const deadline = Date.now() + 3000;
    while (!pred()) {
      if (Date.now() > deadline) throw new Error('timed out waiting for SSE');
      const { value, done } = await reader.read();
      if (done) throw new Error('stream ended');
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i);
        buf = buf.slice(i + 2);
        const ev = /^event: (.+)$/m.exec(block);
        const data = /^data: (.+)$/m.exec(block);
        if (ev && data) events.push({ type: ev[1], data: JSON.parse(data[1]) });
      }
    }
  };
  await until(() => events.filter((e) => e.type === 'pump').length >= 8);
  assert.deepEqual(events.slice(0, 8).map((e) => e.data.pump), [1, 2, 3, 4, 5, 6, 7, 8]);

  await post('/sim/v1/pumps/5/lift', { nozzle: 1 });
  await until(() => events.some((e) => e.type === 'pump' && e.data.pump === 5 && e.data.state === 'CALLING'));
  await post('/fdc/v1/pumps/5/authorise', { mode: 'PREPAY', maxAmountCents: 100 });
  await post('/sim/v1/pumps/5/trigger', { on: true });
  await until(() => events.some((e) => e.type === 'pump' && e.data.pump === 5 && e.data.current?.limitReached));
  await post('/sim/v1/pumps/5/hangup');
  await until(() => events.some((e) => e.type === 'transaction' && e.data.pump === 5));
  const t = events.find((e) => e.type === 'transaction').data;
  assert.equal(t.amountCents, 100);
  assert.equal(t.reason, 'LIMIT');
  ctrl.abort();
});
