// HTTP routing for the forecourt simulator: the POS-facing FDC API (/fdc/v1),
// the simulator-only customer API (/sim/v1), the SSE stream and the panel.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { Forecourt, FdcError } from './forecourt.js';

const PUBLIC_DIR = fileURLToPath(new URL('../public/', import.meta.url));
const STATIC = {
  '/': ['panel.html', 'text/html; charset=utf-8'],
  '/panel': ['panel.html', 'text/html; charset=utf-8'],
  '/panel.css': ['panel.css', 'text/css; charset=utf-8'],
  '/panel.js': ['panel.js', 'text/javascript; charset=utf-8'],
};
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Max-Age': '600',
};
const TICK_MS = 100;
const HEARTBEAT_MS = 5000;
const MAX_BODY = 64 * 1024;

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}
const ok = (res, fdcMessage, payload) => send(res, 200, { result: 'Success', fdcMessage, ...payload });
const fail = (res, status, code, message) => send(res, status, { result: 'Failure', error: { code, message } });

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const c of req) {
    size += c.length;
    if (size > MAX_BODY) throw new FdcError('BAD_REQUEST', 'Body too large');
    chunks.push(c);
  }
  if (size === 0) return {};
  try {
    const v = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error();
    return v;
  } catch {
    throw new FdcError('BAD_REQUEST', 'Body must be a JSON object');
  }
}

/**
 * Build the routes table: [method, regex, fdcMessage|null, handler(model, match, body, url)].
 * A handler returns the payload object merged into the success response.
 */
function routes() {
  const P = '/fdc/v1/pumps/(\\d+)';
  const T = '/fdc/v1/transactions/([^/]+)';
  const S = '/sim/v1/pumps/(\\d+)';
  return [
    // ---- POS-facing (FDC) ----
    ['GET', '/fdc/v1/status', 'GetFDCStatus', (m) => m.status()],
    ['GET', '/fdc/v1/pumps', 'GetFPState', (m) => ({ pumps: m.pumpsJson() })],
    ['GET', P, 'GetFPState', (m, [n]) => (m.pumpOf(n), { pump: m.pumpsJson()[n - 1] })],
    ['POST', `${P}/authorise`, 'AuthoriseFuelPoint', (m, [n], b) => m.authorise(n, b)],
    ['POST', `${P}/free`, 'FreeFuelPoint', (m, [n]) => m.free(n)],
    ['POST', `${P}/stop`, 'StopFuelPoint', (m, [n]) => m.stop(n)],
    ['POST', `${P}/resume`, 'StartFuelPoint', (m, [n]) => m.resume(n)],
    ['POST', `${P}/emergency-stop`, 'EmergencyStop', (m, [n]) => m.emergencyStop(n)],
    ['POST', '/fdc/v1/emergency-stop', 'EmergencyStop', (m) => m.emergencyStopAll()],
    ['POST', `${P}/reset`, 'CancelEmergencyStop', (m, [n]) => m.resetPump(n)],
    ['GET', '/fdc/v1/transactions', 'GetAvailableFuelSaleTrxs', (m, _, __, url) => {
      const state = url.searchParams.get('state');
      const pump = url.searchParams.get('pump');
      if (state && !['PAYABLE', 'LOCKED', 'CLEARED'].includes(state)) {
        throw new FdcError('BAD_REQUEST', 'state must be PAYABLE, LOCKED or CLEARED');
      }
      if (pump != null) m.pumpOf(pump);
      return { transactions: m.listTrx({ state, pump: pump == null ? null : Number(pump) }) };
    }],
    ['GET', T, 'GetFuelSaleTrxDetails', (m, [id]) => ({ transaction: { ...m.trxOf(id) } })],
    ['POST', `${T}/lock`, 'LockFuelSaleTrx', (m, [id], b) => m.lock(id, b)],
    ['POST', `${T}/unlock`, 'UnlockFuelSaleTrx', (m, [id]) => m.unlock(id)],
    ['POST', `${T}/clear`, 'ClearFuelSaleTrx', (m, [id]) => m.clear(id)],
    ['GET', '/fdc/v1/prices', 'GetProductTable', (m) => ({ grades: m.gradesJson() })],
    ['POST', '/fdc/v1/prices', 'ChangeFuelPrice', (m, _, b) => ({ grades: m.changePrices(b.prices) })],
    ['GET', '/fdc/v1/totals', 'GetFuelPointTotals', (m) => (m.sync(), { totals: m.totalsJson() })],
    // ---- simulator-only (the customer at the pump) ----
    ['POST', `${S}/lift`, null, (m, [n], b) => m.lift(n, b)],
    ['POST', `${S}/trigger`, null, (m, [n], b) => m.trigger(n, b.on)],
    ['POST', `${S}/hangup`, null, (m, [n]) => m.hangup(n)],
    ['POST', `${S}/offline`, null, (m, [n], b) => m.setOffline(n, b.on)],
    ['POST', `${S}/error`, null, (m, [n], b) => m.setError(n, b.on, b.message)],
    ['POST', '/sim/v1/speed', null, (m, _, b) => m.setSpeed(b.multiplier, b.pump ?? null)],
    ['POST', '/sim/v1/reset', null, (m) => (m.reset(), m.status())],
  ].map(([method, pat, msg, fn]) => [method, new RegExp(`^${pat}$`), msg, fn]);
}

/**
 * Create the simulator server (not yet listening).
 * @returns {{ server: http.Server, model: Forecourt, listen: (port, host) => Promise<number>, close: () => Promise<void> }}
 */
export function createSimulator({ pumps = 8, speed = 1, now = Date.now, tickMs = TICK_MS, heartbeatMs = HEARTBEAT_MS } = {}) {
  const model = new Forecourt({ pumps, speed, now });
  const table = routes();
  const clients = new Set();

  const sse = (res, event, data) => res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  model.on((type, data) => {
    for (const res of clients) sse(res, type, data);
  });

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://sim');
    const p = url.pathname.length > 1 ? url.pathname.replace(/\/+$/, '') : url.pathname;
    try {
      if (req.method === 'OPTIONS') {
        res.writeHead(204, CORS);
        return res.end();
      }
      if (req.method === 'GET' && p === '/healthz') return send(res, 200, { ok: true });
      if (req.method === 'GET' && p === '/fdc/v1/events') {
        res.writeHead(200, {
          ...CORS,
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive',
          'X-Accel-Buffering': 'no',
        });
        res.write('retry: 2000\n\n');
        for (const pump of model.pumpsJson()) sse(res, 'pump', pump);
        clients.add(res);
        req.on('close', () => clients.delete(res));
        return;
      }
      if (req.method === 'GET' && STATIC[p]) {
        const [file, type] = STATIC[p];
        const body = await readFile(path.join(PUBLIC_DIR, file));
        res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-cache' });
        return res.end(body);
      }
      let pathMatched = false;
      for (const [method, re, msg, fn] of table) {
        const m = re.exec(p);
        if (!m) continue;
        pathMatched = true;
        if (method !== req.method) continue;
        const body = method === 'POST' ? await readJson(req) : {};
        const payload = fn(model, m.slice(1).map(decodeURIComponent), body, url);
        return msg ? ok(res, msg, payload) : send(res, 200, { result: 'Success', ...payload });
      }
      if (pathMatched) return fail(res, 405, 'BAD_REQUEST', `${req.method} not allowed on ${p}`);
      return fail(res, 404, 'NOT_FOUND', `No route ${req.method} ${p}`);
    } catch (e) {
      if (e instanceof FdcError) return fail(res, e.status, e.code, e.message);
      console.error(e);
      return fail(res, 500, 'INTERNAL', 'Internal error');
    }
  });

  const ticker = setInterval(() => model.tick(), tickMs);
  const heartbeat = setInterval(() => {
    const time = new Date(now()).toISOString();
    for (const res of clients) sse(res, 'heartbeat', { time });
  }, heartbeatMs);
  ticker.unref?.();
  heartbeat.unref?.();

  return {
    server,
    model,
    listen(port, host) {
      return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, host, () => resolve(server.address().port));
      });
    },
    close() {
      clearInterval(ticker);
      clearInterval(heartbeat);
      for (const res of clients) res.end();
      clients.clear();
      return new Promise((resolve) => server.close(() => resolve()));
    },
  };
}
