'use strict';

// trex resolver — lists HELD / REVIEW from the journal follower and sends
// double-confirmed decisions through the resolver API. Live updates arrive by
// Server-Sent Events; polling is only a fallback while the stream is down.
// All bank text is inserted with textContent (never as HTML).

const POLL_MS = 2000;
const ADMIN_HEADER = { 'Content-Type': 'application/json', 'X-Trex-Admin': '1' };

const $ = (id) => document.getElementById(id);

const model = {
  byId: new Map(),     // externalId -> latest line (union of both lists)
  selected: new Set(), // externalIds selected for pairing
  lastKey: null,
  pending: false,
  live: false,         // true while the event stream is open
};

// ---------------------------------------------------------------- formatting

function formatAmount(cents, currency) {
  const negative = cents < 0;
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100).toLocaleString('en-AU');
  const frac = String(abs % 100).padStart(2, '0');
  return { text: `${negative ? '−' : ''}${whole}.${frac}`, currency, negative };
}

function formatDate(iso) {
  const [y, m, d] = iso.split('-');
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${Number(d)} ${months[Number(m) - 1]} ${y}`;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

function wrap(tag, className, ...children) {
  const node = el(tag, className);
  node.append(...children);
  return node;
}

function amountNode(line) {
  const a = formatAmount(line.amount, line.currency);
  const td = el('td', 'col-amt ' + (a.negative ? 'amt-neg' : 'amt-pos'));
  td.append(el('span', 'ccy', a.currency), document.createTextNode(a.text));
  return td;
}

function isDup(line) {
  return line.flags.includes('POTENTIAL_DUP');
}

function resolvable(line) {
  return line.state === 'HELD' || line.state === 'REVIEW';
}

function summary(line) {
  const a = formatAmount(line.amount, line.currency);
  return `${a.currency} ${a.text} · ${line.accountRef} · ${formatDate(line.date)}`;
}

// ---------------------------------------------------------------- rendering

function badges(line) {
  const td = el('td', 'col-badge');
  if (line.state === 'HELD') td.append(el('span', 'badge held', 'held'));
  if (line.state === 'REVIEW') td.append(el('span', 'badge review', 'ambiguous'));
  if (isDup(line)) td.append(el('span', 'badge dup', 'duplicate?'));
  return td;
}

function row(line) {
  const tr = el('tr');
  tr.dataset.id = line.externalId;
  if (model.selected.has(line.externalId)) tr.classList.add('selected');

  const sel = el('td', 'col-sel');
  if (resolvable(line)) {
    const box = el('input');
    box.type = 'checkbox';
    box.checked = model.selected.has(line.externalId);
    box.title = 'Select to pair as a transfer';
    box.addEventListener('change', () => toggleSelect(line.externalId, box.checked));
    sel.append(box);
  }

  const desc = el('td');
  const text = el('div', 'desc', line.rawDescription);
  text.title = `${line.rawDescription}\n${line.externalId}`;
  desc.append(text);
  if (line.comment) desc.append(el('div', 'comment', line.comment));

  const actions = el('td', 'col-act');
  if (isDup(line)) {
    const b = el('button', 'btn', 'Dismiss dup');
    b.type = 'button';
    b.addEventListener('click', () => confirmDismissDup(line));
    actions.append(b);
  }
  if (resolvable(line)) {
    const b = el('button', 'btn', 'External');
    b.type = 'button';
    b.title = 'Confirm this is a real external payment (not a transfer)';
    b.addEventListener('click', () => confirmExternal(line));
    actions.append(b);
  }

  tr.append(
    sel,
    el('td', 'col-date', formatDate(line.date)),
    wrap('td', 'col-acct', el('span', 'acct', line.accountRef)),
    amountNode(line),
    desc,
    badges(line),
    el('td', 'col-n', String(line.n)),
    actions,
  );
  return tr;
}

function renderList(name, lines) {
  const body = $(`${name}-body`);
  body.replaceChildren(...lines.map(row));
  $(`${name}-count`).textContent = String(lines.length);
  $(`${name}-empty`).hidden = lines.length > 0;
}

function render(state) {
  model.byId = new Map();
  for (const line of [...state.held, ...state.review]) model.byId.set(line.externalId, line);
  for (const id of [...model.selected]) {
    const line = model.byId.get(id);
    if (!line || !resolvable(line)) model.selected.delete(id);
  }
  renderList('review', state.review);
  renderList('held', state.held);
  renderPairbar();
}

function renderStatus(state, fetchError) {
  const dot = $('status-dot');
  const box = $('status');
  if (fetchError || state.error) {
    dot.className = 'dot bad';
    box.classList.add('bad');
    $('status-text').textContent = fetchError ? `resolver unreachable: ${fetchError}` : state.error;
    return;
  }
  dot.className = 'dot ok';
  box.classList.remove('bad');
  const kb = (state.offset / 1024).toFixed(1);
  const at = new Date(state.updatedAt).toLocaleTimeString();
  $('status-text').textContent = `${model.live ? 'live' : 'polling'} · n ${state.n} · ${kb} KB · ${at}`;
}

function apply(state) {
  renderStatus(state, null);
  const key = `${state.n}:${state.offset}`;
  if (key !== model.lastKey) {
    model.lastKey = key;
    render(state);
  }
}

// ---------------------------------------------------------------- pairing

function toggleSelect(id, on) {
  if (on) model.selected.add(id); else model.selected.delete(id);
  document.querySelectorAll('tr[data-id]').forEach((tr) => {
    const selected = model.selected.has(tr.dataset.id);
    tr.classList.toggle('selected', selected);
    const box = tr.querySelector('input[type="checkbox"]');
    if (box) box.checked = selected;
  });
  renderPairbar();
}

function pairProblem(a, b) {
  if (a.accountRef === b.accountRef) return 'both legs are in the same account';
  if (a.currency !== b.currency) return 'legs have different currencies';
  if (a.amount === 0 || a.amount + b.amount !== 0) return 'amounts are not equal and opposite';
  return null;
}

function renderPairbar() {
  const bar = $('pairbar');
  const text = $('pair-text');
  const btn = $('pair-btn');
  const ids = [...model.selected];
  bar.hidden = ids.length === 0;
  text.classList.remove('ok');
  btn.disabled = true;
  if (ids.length === 1) {
    text.textContent = '1 selected — pick the other leg';
  } else if (ids.length > 2) {
    text.textContent = `${ids.length} selected — a transfer has exactly two legs`;
  } else if (ids.length === 2) {
    const [a, b] = ids.map((id) => model.byId.get(id));
    const problem = pairProblem(a, b);
    if (problem) {
      text.textContent = `Can't pair: ${problem}`;
    } else {
      const from = a.amount < 0 ? a : b;
      const to = a.amount < 0 ? b : a;
      const amt = formatAmount(to.amount, to.currency);
      text.textContent = `${from.accountRef} → ${to.accountRef} · ${amt.currency} ${amt.text}`;
      text.classList.add('ok');
      btn.disabled = false;
    }
  }
}

// ---------------------------------------------------------------- confirmation

function confirmLine(line) {
  const box = el('div', 'confirm-line');
  const left = el('div');
  left.append(el('div', null, line.rawDescription), el('div', 'meta', `${line.accountRef} · ${formatDate(line.date)} · n ${line.n}`));
  const a = formatAmount(line.amount, line.currency);
  const right = el('div', a.negative ? 'amt-neg' : 'amt-pos', `${a.currency} ${a.text}`);
  box.append(left, right);
  return box;
}

function openConfirm(title, effect, lines, payload, successText) {
  if (model.pending) return;
  $('confirm-title').textContent = title;
  $('confirm-body').replaceChildren(el('p', 'confirm-effect', effect), ...lines.map(confirmLine));
  $('confirm-comment').value = '';
  const dialog = $('confirm');
  dialog.returnValue = '';
  dialog.onclose = () => {
    if (dialog.returnValue !== 'ok') return;
    const comment = $('confirm-comment').value.trim();
    send({ ...payload, comment: comment || null }, successText);
  };
  dialog.showModal();
  $('confirm-comment').focus();
}

function confirmExternal(line) {
  openConfirm('Mark as external?',
    'Records this as a real external payment. It leaves the worklist and will be projected as an ordinary transaction.',
    [line], { action: 'MARK_EXTERNAL', externalId: line.externalId }, 'Marked external');
}

function confirmDismissDup(line) {
  openConfirm('Dismiss duplicate flag?',
    'A re-submitted row matched this transaction with a different balance. Dismissing keeps the transaction as it is and clears the flag.',
    [line], { action: 'DISMISS_DUP', externalId: line.externalId }, 'Duplicate flag dismissed');
}

function confirmPair() {
  const [a, b] = [...model.selected].map((id) => model.byId.get(id));
  if (!a || !b || pairProblem(a, b)) return;
  const from = a.amount < 0 ? a : b;
  const to = a.amount < 0 ? b : a;
  openConfirm('Pair as transfer?',
    `Both legs become MATCHED and one transfer ${from.accountRef} → ${to.accountRef} is recorded.`,
    [from, to], { action: 'CONFIRM_TRANSFER', legA: from.externalId, legB: to.externalId }, 'Transfer recorded');
}

// ---------------------------------------------------------------- network

function toast(message, bad) {
  const t = el('div', 'toast' + (bad ? ' bad' : ''), message);
  $('toasts').append(t);
  setTimeout(() => t.remove(), bad ? 8000 : 4000);
}

async function send(payload, successText) {
  model.pending = true;
  try {
    const res = await fetch('/api/decisions', { method: 'POST', headers: ADMIN_HEADER, body: JSON.stringify(payload) });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      toast(`Request failed (${res.status}): ${body.error || 'unknown error'}`, true);
      return;
    }
    const result = (body.results || [])[0];
    if (result && result.type === 'Resolved') {
      toast(`${successText} · n ${result.n}`);
      if (payload.action === 'CONFIRM_TRANSFER') model.selected.clear();
    } else if (result && result.type === 'Rejected') {
      toast(`Rejected: ${result.reason}`, true);
    } else {
      toast(`Unexpected response: ${body.batchStatus || res.status}`, true);
    }
  } catch (e) {
    toast(`Request failed: ${e.message}`, true);
  } finally {
    model.pending = false;
    refresh();
  }
}

let timer = null;

// One-shot fetch; keeps polling only while the event stream is down.
async function refresh() {
  clearTimeout(timer);
  try {
    const res = await fetch('/api/state', { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    apply(await res.json());
  } catch (e) {
    renderStatus(null, e.message);
  } finally {
    if (!model.live) timer = setTimeout(refresh, POLL_MS);
  }
}

function connect() {
  const source = new EventSource('/api/events');
  source.addEventListener('open', () => {
    model.live = true;
    clearTimeout(timer);
  });
  source.addEventListener('state', (e) => {
    model.live = true;
    apply(JSON.parse(e.data));
  });
  source.addEventListener('error', () => {
    // EventSource reconnects by itself; poll meanwhile so the page stays current.
    if (model.live) {
      model.live = false;
      refresh();
    }
  });
}

document.addEventListener('DOMContentLoaded', () => {
  $('pair-btn').addEventListener('click', confirmPair);
  $('pair-clear').addEventListener('click', () => {
    model.selected.clear();
    toggleSelect(null, false);
  });
  refresh();
  connect();
});
