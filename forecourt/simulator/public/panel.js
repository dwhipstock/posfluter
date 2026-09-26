// Pump panel: the customer side of the forecourt simulator.
// Live updates over SSE (/fdc/v1/events); falls back to polling /fdc/v1/status.
'use strict';

(() => {
  const OCTANE = { REG: '87', MID: '89', PRE: '93', DSL: 'DSL' };
  const SHORT = { REG: 'Regular', MID: 'Mid', PRE: 'Premium', DSL: 'Diesel' };
  const LABEL = {
    IDLE: 'Idle',
    CALLING: 'Calling',
    AUTHORISED: 'Authorised',
    FUELLING: 'Fuelling',
    SUSPENDED: 'Suspended',
    EMERGENCY_STOP: 'Emergency stop',
    ERROR: 'Error',
    OFFLINE: 'Offline',
  };

  const pumps = new Map(); // pump no -> pump JSON
  const trx = new Map(); // trxId -> uncleared trx
  const cards = new Map(); // pump no -> { el, f: {...}, grades: {...} }
  const latched = new Set();
  const held = new Set();
  let grades = [];
  let selected = 1;
  let pollTimer = null;

  const $ = (s, r = document) => r.querySelector(s);
  const grid = $('#grid');
  const tpl = $('#pump-tpl');

  // ---- formatting (integers in, fixed decimals out; no float rounding) ----
  const fixed = (n, places) => {
    const d = 10 ** places;
    const neg = n < 0 ? '-' : '';
    n = Math.abs(n);
    return `${neg}${Math.floor(n / d).toLocaleString('en-US')}.${String(n % d).padStart(places, '0')}`;
  };
  const dollars = (cents) => fixed(cents, 2);
  const gallons = (milli) => fixed(milli, 3);
  const perGal = (mills) => fixed(mills, 3);

  // ---- API ----
  let toastTimer;
  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.hidden = true), 2600);
  }

  async function api(method, path, body) {
    try {
      const res = await fetch(path, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      });
      const json = await res.json();
      if (json.result === 'Failure') toast(`${json.error.code}: ${json.error.message}`);
      if (json.pump) setPump(json.pump);
      if (json.pumps) json.pumps.forEach(setPump);
      return json;
    } catch (e) {
      toast('Simulator not reachable');
      return null;
    }
  }
  const sim = (n, what, body) => api('POST', `/sim/v1/pumps/${n}/${what}`, body || {});
  const fdc = (n, what, body) => api('POST', `/fdc/v1/pumps/${n}/${what}`, body || {});

  // ---- building cards ----
  function gradeOf(code) {
    return grades.find((g) => g.grade === code);
  }

  function buildCard(n) {
    const el = tpl.content.firstElementChild.cloneNode(true);
    el.dataset.pump = n;
    $('.num', el).textContent = n;
    const f = {};
    el.querySelectorAll('[data-f]').forEach((x) => (f[x.dataset.f] = x));
    f.label = $('.label', el);
    f.hold = $('.hold', el);
    f.latch = $('.latch', el);
    f.menu = $('.menu', el);
    const gradeBtns = {};
    const box = $('.grades', el);
    ['REG', 'MID', 'PRE', 'DSL'].forEach((code, i) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'grade';
      b.dataset.grade = code;
      b.innerHTML = `<span class="oct">${OCTANE[code]}</span><span class="gname">${SHORT[code]}</span><span class="gprice"></span>`;
      b.addEventListener('click', () => sim(n, 'lift', { nozzle: i + 1 }));
      box.appendChild(b);
      gradeBtns[code] = b;
    });
    wireCard(n, el, f);
    grid.appendChild(el);
    cards.set(n, { el, f, gradeBtns });
    return cards.get(n);
  }

  function setTrigger(n, on) {
    const card = cards.get(n);
    if (on) held.add(n);
    else held.delete(n);
    card?.f.hold.classList.toggle('active', on);
    sim(n, 'trigger', { on });
  }

  function wireCard(n, el, f) {
    el.addEventListener('pointerdown', () => select(n));
    const hold = f.hold;
    hold.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      hold.setPointerCapture?.(e.pointerId);
      if (!held.has(n)) setTrigger(n, true);
    });
    const release = () => {
      if (held.has(n) && !latched.has(n)) setTrigger(n, false);
    };
    hold.addEventListener('pointerup', release);
    hold.addEventListener('pointercancel', release);
    hold.addEventListener('pointerleave', release);
    hold.addEventListener('contextmenu', (e) => e.preventDefault());

    f.latch.addEventListener('click', () => {
      if (latched.has(n)) {
        latched.delete(n);
        setTrigger(n, false);
      } else {
        latched.add(n);
        setTrigger(n, true);
      }
      render(n);
    });
    $('[data-act="hangup"]', el).addEventListener('click', () => {
      latched.delete(n);
      held.delete(n);
      sim(n, 'hangup');
    });

    el.querySelectorAll('.menu-list button').forEach((b) =>
      b.addEventListener('click', () => {
        f.menu.open = false;
        const p = pumps.get(n);
        switch (b.dataset.act) {
          case 'offline': return sim(n, 'offline', { on: p.state !== 'OFFLINE' });
          case 'error': return sim(n, 'error', { on: p.state !== 'ERROR', message: 'Pulser fault (simulated)' });
          case 'reset': return fdc(n, 'reset');
          case 'estop': return fdc(n, 'emergency-stop');
          case 'postpay': return fdc(n, 'authorise', { mode: 'POSTPAY', posRef: 'panel' });
          case 'prepay': return fdc(n, 'authorise', { mode: 'PREPAY', maxAmountCents: 2000, posRef: 'panel' });
          case 'stop': return fdc(n, 'stop');
          case 'resume': return fdc(n, 'resume');
        }
      }),
    );
  }

  function select(n) {
    selected = n;
    cards.forEach((c, k) => c.el.classList.toggle('selected', k === n));
  }

  // ---- rendering ----
  function authText(p) {
    const c = p.current;
    if (c && c.limitReached) return { text: `Limit reached $${dollars(c.maxAmountCents)} — hang up`, cls: 'limit' };
    const a = p.authorisation;
    if (a) {
      const lim = a.maxAmountCents != null ? ` $${dollars(a.maxAmountCents)} limit` : '';
      const hint = p.state === 'AUTHORISED' ? (p.nozzleUp ? ' · squeeze to pump' : ' · lift a nozzle') : '';
      return { text: `${a.mode}${lim}${hint}`, cls: 'on' };
    }
    if (p.state === 'CALLING') return { text: 'Waiting for the cashier…', cls: '' };
    if (p.state === 'ERROR') return { text: p.error || 'Dispenser fault', cls: 'limit' };
    if (p.state === 'OFFLINE') return { text: 'No communication with dispenser', cls: '' };
    if (p.state === 'EMERGENCY_STOP') return { text: 'Stopped — reset from the menu', cls: 'limit' };
    return { text: 'Not authorised', cls: '' };
  }

  function render(n) {
    const p = pumps.get(n);
    const card = cards.get(n) || buildCard(n);
    if (!p) return;
    const { el, f, gradeBtns } = card;
    el.dataset.state = p.state;
    el.classList.toggle('flowing', p.flowing);
    let label = LABEL[p.state] || p.state;
    if (p.state === 'FUELLING' && !p.flowing) label = p.current?.limitReached ? 'Limit reached' : 'Fuelling · paused';
    f.label.textContent = label;

    f.amount.textContent = dollars(p.display.amountCents);
    f.volume.textContent = gallons(p.display.volumeMilli);
    f.price.textContent = perGal(p.display.priceMills);

    const a = authText(p);
    f.auth.textContent = a.text;
    f.auth.className = `auth ${a.cls}`;

    const upGrade = p.nozzleUp ? p.nozzles[p.nozzleUp - 1].grade : null;
    const blocked = p.state === 'OFFLINE' || p.state === 'ERROR';
    for (const [code, b] of Object.entries(gradeBtns)) {
      const g = gradeOf(code);
      $('.gprice', b).textContent = g ? `$${perGal(g.priceMills)}` : '';
      b.classList.toggle('up', code === upGrade);
      b.disabled = upGrade != null || blocked;
    }

    const canPump = p.nozzleUp != null && (p.state === 'AUTHORISED' || p.state === 'FUELLING' || p.state === 'CALLING' || p.state === 'SUSPENDED');
    f.hold.disabled = p.nozzleUp == null;
    f.hold.textContent = canPump ? 'HOLD TO PUMP' : 'LIFT A NOZZLE';
    if (p.nozzleUp == null) {
      latched.delete(n);
      held.delete(n);
    }
    f.hold.classList.toggle('active', held.has(n));
    f.latch.setAttribute('aria-pressed', String(latched.has(n)));
    f.latch.disabled = p.nozzleUp == null;

    $('[data-act="offline"]', el).textContent = p.state === 'OFFLINE' ? 'Back online' : 'Go offline';
    $('[data-act="error"]', el).textContent = p.state === 'ERROR' ? 'Clear error' : 'Raise error';
    renderUnpaid(n);
  }

  function renderUnpaid(n) {
    const card = cards.get(n);
    if (!card) return;
    const list = [...trx.values()].filter((t) => t.pump === n);
    const box = card.f.unpaid;
    box.textContent = '';
    if (list.length === 0) {
      box.textContent = 'none';
      return;
    }
    for (const t of list) {
      const s = document.createElement('span');
      s.className = `chip${t.state === 'LOCKED' ? ' locked' : ''}`;
      s.title = `${t.trxId} · ${t.gradeName} · ${gallons(t.volumeMilli)} gal · ${t.mode} · ${t.reason}`;
      s.textContent = `$${dollars(t.amountCents)} ${t.grade}${t.state === 'LOCKED' ? ' LOCKED' : ''}`;
      box.appendChild(s);
    }
  }

  function setPump(p) {
    pumps.set(p.pump, p);
    render(p.pump);
  }

  function setTrx(t) {
    if (t.state === 'CLEARED') trx.delete(t.trxId);
    else trx.set(t.trxId, t);
    renderUnpaid(t.pump);
  }

  function applyStatus(s) {
    grades = s.grades;
    trx.clear();
    s.transactions.forEach((t) => trx.set(t.trxId, t));
    s.pumps.forEach(setPump);
    cards.forEach((_, n) => renderUnpaid(n));
    document.querySelectorAll('[data-speed]').forEach((b) =>
      b.setAttribute('aria-pressed', String(Number(b.dataset.speed) === s.speed)),
    );
  }

  async function refresh() {
    try {
      const res = await fetch('/fdc/v1/status');
      applyStatus(await res.json());
      return true;
    } catch {
      return false;
    }
  }

  // ---- connection: SSE first, polling as the fallback ----
  function setConn(kind, text) {
    const c = $('#conn');
    c.className = `conn ${kind}`;
    $('#conn-label').textContent = text;
  }

  function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(async () => {
      const ok = await refresh();
      setConn(ok ? 'live' : 'down', ok ? 'polling' : 'offline');
    }, 500);
  }
  function stopPolling() {
    clearInterval(pollTimer);
    pollTimer = null;
  }

  function connect() {
    if (!('EventSource' in window)) return startPolling();
    const es = new EventSource('/fdc/v1/events');
    es.addEventListener('open', () => {
      stopPolling();
      setConn('live', 'live');
      refresh(); // grades, prices, speed and the unpaid buffer
    });
    es.addEventListener('pump', (e) => setPump(JSON.parse(e.data)));
    es.addEventListener('transaction', (e) => setTrx(JSON.parse(e.data)));
    es.addEventListener('heartbeat', () => setConn('live', 'live'));
    es.addEventListener('error', () => {
      setConn('down', 'reconnecting');
      startPolling(); // EventSource keeps retrying; polling covers the gap
    });
  }

  // ---- global controls ----
  document.querySelectorAll('[data-speed]').forEach((b) =>
    b.addEventListener('click', async () => {
      const r = await api('POST', '/sim/v1/speed', { multiplier: Number(b.dataset.speed) });
      if (r && r.result === 'Success') {
        document.querySelectorAll('[data-speed]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
      }
    }),
  );
  $('#estop-all').addEventListener('click', () => api('POST', '/fdc/v1/emergency-stop', {}));
  $('#reset-all').addEventListener('click', async () => {
    if (!confirm('Reset the whole forecourt? Pumps, unpaid sales, prices and totals go back to boot state.')) return;
    latched.clear();
    held.clear();
    const s = await api('POST', '/sim/v1/reset', {});
    if (s && s.result === 'Success') applyStatus(s);
  });

  // Space bar = hold to pump on the selected pump.
  const typing = (e) => /INPUT|TEXTAREA|SELECT/.test(e.target.tagName);
  document.addEventListener('keydown', (e) => {
    if (e.code !== 'Space' || typing(e)) return;
    e.preventDefault();
    if (!e.repeat && !held.has(selected)) setTrigger(selected, true);
  });
  document.addEventListener('keyup', (e) => {
    if (e.code !== 'Space' || typing(e)) return;
    e.preventDefault();
    if (!latched.has(selected)) setTrigger(selected, false);
  });
  // Close open pump menus on outside click.
  document.addEventListener('click', (e) => {
    document.querySelectorAll('.menu[open]').forEach((m) => {
      if (!m.contains(e.target)) m.open = false;
    });
  });

  refresh().then(() => select(1));
  connect();
})();
