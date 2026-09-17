'use strict';

// trex grid — read-only, paged, sortable view of the journal. Pages are pinned to a
// snapshot (asOfN); head changes arrive by Server-Sent Events and surface as a
// "new rows" chip (or refresh automatically in Follow mode). All bank text is
// inserted with textContent (never as HTML).

const $ = (id) => document.getElementById(id);
const HEAD_POLL_MS = 2000;

const COLUMNS = [
  { key: 'n', label: 'n', cls: 'num muted', on: true, cell: (r) => String(r.n) },
  { key: 'date', label: 'Date', on: true, cell: (r) => r.date },
  { key: 'accountRef', label: 'Account', cls: 'mono', on: true, cell: (r) => r.accountRef },
  { key: 'toAccountRef', label: 'To account', cls: 'mono', on: false, cell: (r) => r.toAccountRef },
  { key: 'amount', label: 'Amount', cls: 'num', on: true, cell: amountCell },
  { key: 'currency', label: 'Ccy', cls: 'muted', on: true, cell: (r) => r.currency },
  { key: 'description', label: 'Description', on: true, cell: descriptionCell },
  { key: 'typeHint', label: 'Type', cls: 'muted', on: true, cell: (r) => r.typeHint.toLowerCase() },
  { key: 'state', label: 'State', on: true, cell: stateCell },
  { key: 'flags', label: 'Flags', on: false, cell: flagsCell },
  { key: 'confidence', label: 'Confidence', cls: 'muted', on: false, cell: (r) => r.confidence },
  { key: 'provenance', label: 'Provenance', cls: 'muted', on: false, cell: (r) => r.provenance },
  { key: 'source', label: 'Source', cls: 'muted', on: false, cell: (r) => r.source },
  { key: 'receipt', label: 'Receipt', cls: 'mono', on: false, cell: (r) => r.receipt },
  { key: 'transferKey', label: 'Transfer', cls: 'mono', on: false, cell: (r) => r.transferKey },
  { key: 'comment', label: 'Comment', cls: 'muted', on: true, cell: (r) => r.comment },
  { key: 'ingestedAt', label: 'Ingested', cls: 'muted', on: false, cell: (r) => r.ingestedAt && new Date(r.ingestedAt).toLocaleString() },
  { key: 'externalId', label: 'Id', cls: 'mono muted', on: false, cell: idCell },
];

const FILTERS = ['q', 'account', 'state', 'type', 'from', 'to'];

const model = {
  view: 'transactions',
  sort: [{ key: 'n', desc: true }],
  page: 1,
  size: 50,
  filters: { q: '', account: '', state: '', type: '', from: '', to: '' },
  visible: new Set(COLUMNS.filter((c) => c.on).map((c) => c.key)),
  asOfN: null,     // snapshot the current pages are pinned to
  headN: 0,        // latest n reported by the server
  total: 0,
  live: false,
  loading: null,
};

// ---------------------------------------------------------------- cells

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

function formatCents(cents) {
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100).toLocaleString('en-AU');
  return `${cents < 0 ? '−' : ''}${whole}.${String(abs % 100).padStart(2, '0')}`;
}

function amountCell(r) {
  const span = el('span', r.typeHint === 'TRANSFER' ? '' : (r.amount < 0 ? 'neg' : 'pos'), formatCents(r.amount));
  return span;
}

function descriptionCell(r) {
  const span = el('span', null, r.rawDescription);
  span.title = r.rawDescription;
  return span;
}

function stateCell(r) {
  return el('span', `badge ${r.state}`, r.state.toLowerCase());
}

function flagsCell(r) {
  if (!r.flags.length) return null;
  return el('span', 'badge dup', r.flags.includes('POTENTIAL_DUP') ? 'dup?' : r.flags.join(','));
}

function idCell(r) {
  const span = el('span', null, r.externalId.length > 12 ? r.externalId.slice(0, 12) + '…' : r.externalId);
  span.title = r.externalId;
  return span;
}

// ---------------------------------------------------------------- url state

function readUrl() {
  const p = new URLSearchParams(location.search);
  if (p.get('view') === 'journal') model.view = 'journal';
  if (p.get('sort')) {
    const keys = p.get('sort').split(',').map((part) => {
      const [key, dir] = part.split(':');
      return { key, desc: dir === 'desc' };
    }).filter((k) => COLUMNS.some((c) => c.key === k.key));
    if (keys.length) model.sort = keys;
  }
  model.page = Math.max(1, Number(p.get('page')) || 1);
  const size = Number(p.get('size'));
  if ([25, 50, 100, 250].includes(size)) model.size = size;
  for (const f of FILTERS) model.filters[f] = p.get(f) || '';
}

function sortParam() {
  return model.sort.map((k) => `${k.key}:${k.desc ? 'desc' : 'asc'}`).join(',');
}

function queryParams(withPin) {
  const p = new URLSearchParams();
  p.set('view', model.view);
  p.set('sort', sortParam());
  p.set('page', String(model.page));
  p.set('size', String(model.size));
  for (const f of FILTERS) if (model.filters[f]) p.set(f, model.filters[f]);
  if (withPin && model.asOfN !== null) p.set('asOfN', String(model.asOfN));
  return p;
}

function writeUrl() {
  history.replaceState(null, '', `${location.pathname}?${queryParams(false)}`);
}

function loadColumns() {
  try {
    const saved = JSON.parse(localStorage.getItem('trex-grid-columns'));
    if (Array.isArray(saved) && saved.length) model.visible = new Set(saved.filter((k) => COLUMNS.some((c) => c.key === k)));
  } catch (e) { /* storage unavailable: keep defaults */ }
}

function saveColumns() {
  try { localStorage.setItem('trex-grid-columns', JSON.stringify([...model.visible])); } catch (e) { /* ignore */ }
}

// ---------------------------------------------------------------- rendering

function visibleColumns() {
  return COLUMNS.filter((c) => model.visible.has(c.key));
}

function renderHead() {
  const tr = $('grid-head');
  const cols = visibleColumns();
  tr.replaceChildren(...cols.map((c) => {
    const th = el('th', c.cls && c.cls.includes('num') ? 'num' : null, c.label);
    const idx = model.sort.findIndex((k) => k.key === c.key);
    if (idx >= 0) {
      th.append(el('span', 'arrow', model.sort[idx].desc ? '▼' : '▲'));
      if (model.sort.length > 1) th.append(el('span', 'rank', String(idx + 1)));
      th.setAttribute('aria-sort', model.sort[idx].desc ? 'descending' : 'ascending');
    }
    th.title = 'Click to sort · shift-click to add a secondary sort';
    th.addEventListener('click', (e) => onSort(c.key, e.shiftKey));
    return th;
  }));
}

function renderRows(rows) {
  const cols = visibleColumns();
  $('grid-body').replaceChildren(...rows.map((r) => {
    const tr = el('tr');
    for (const c of cols) {
      const td = el('td', c.cls || null);
      const v = c.cell(r);
      if (v instanceof Node) td.append(v); else if (v !== null && v !== undefined) td.textContent = v;
      tr.append(td);
    }
    return tr;
  }));
  $('grid-empty').hidden = rows.length > 0;
}

function renderFooter(data) {
  const pages = Math.max(1, Math.ceil(data.total / data.size));
  const first = data.total === 0 ? 0 : (data.page - 1) * data.size + 1;
  const last = Math.min(data.page * data.size, data.total);
  $('range').textContent = `${first.toLocaleString()}–${last.toLocaleString()} of ${data.total.toLocaleString()}`;
  $('page').value = String(data.page);
  $('page').max = String(pages);
  $('of').textContent = `/ ${pages.toLocaleString()}`;
  $('prev').disabled = data.page <= 1;
  $('next').disabled = data.page >= pages;
  $('totals').replaceChildren(...data.totals.map((t) => {
    const span = el('span');
    span.append(document.createTextNode(`Σ ${t.currency} `), el('b', t.amount < 0 ? 'neg' : 'pos', formatCents(t.amount)),
      document.createTextNode(` · ${t.count.toLocaleString()} rows`));
    return span;
  }));
  if (model.view === 'journal') $('totals').replaceChildren(el('span', null, `${data.total.toLocaleString()} journal lines (as of n ${data.asOfN})`));
}

function renderControls() {
  document.querySelectorAll('.segmented button').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.view === model.view)));
  $('f-q').value = model.filters.q;
  $('f-state').value = model.filters.state;
  $('f-type').value = model.filters.type;
  $('f-from').value = model.filters.from;
  $('f-to').value = model.filters.to;
  $('page-size').value = String(model.size);
  $('columns-pop').replaceChildren(...COLUMNS.map((c) => {
    const label = el('label');
    const box = el('input');
    box.type = 'checkbox';
    box.checked = model.visible.has(c.key);
    box.addEventListener('change', () => {
      if (box.checked) model.visible.add(c.key); else model.visible.delete(c.key);
      saveColumns();
      load(false);
    });
    label.append(box, document.createTextNode(c.label));
    return label;
  }));
}

function renderAccounts(accounts) {
  const select = $('f-account');
  const current = model.filters.account;
  const wanted = ['', ...accounts];
  const have = [...select.options].map((o) => o.value);
  if (wanted.length !== have.length || wanted.some((a, i) => a !== have[i])) {
    select.replaceChildren(el('option', null, 'All accounts'), ...accounts.map((a) => el('option', null, a)));
    select.options[0].value = '';
  }
  select.value = current;
}

function renderNewChip() {
  const fresh = model.asOfN === null ? 0 : model.headN - model.asOfN;
  const chip = $('new-chip');
  chip.hidden = fresh <= 0;
  chip.textContent = `${fresh.toLocaleString()} new · refresh`;
}

function renderStatus(head, error) {
  const bad = error || (head && head.error);
  $('status-dot').className = `dot ${bad ? 'bad' : 'ok'}`;
  $('status').classList.toggle('bad', Boolean(bad));
  $('status-text').textContent = bad
    ? (error ? `grid unreachable: ${error}` : head.error)
    : `${model.live ? 'live' : 'polling'} · n ${head.n.toLocaleString()} · ${head.transactions.toLocaleString()} transactions · ${head.lines.toLocaleString()} lines`;
}

// ---------------------------------------------------------------- data

async function load(repin) {
  if (repin) model.asOfN = null;
  writeUrl();
  renderHead();
  const params = queryParams(true);
  const request = model.loading = fetch(`/api/rows?${params}`, { cache: 'no-store' });
  try {
    const res = await request;
    if (request !== model.loading) return;   // a newer request superseded this one
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    if (data.total > 0 && data.rows.length === 0 && data.page > 1) {
      model.page = Math.max(1, Math.ceil(data.total / data.size));
      return load(false);
    }
    model.asOfN = data.asOfN;
    model.total = data.total;
    renderRows(data.rows);
    renderFooter(data);
    renderNewChip();
  } catch (e) {
    renderStatus(null, e.message);
  }
}

function following() {
  return $('follow').checked && model.page === 1 && model.sort[0].key === 'n' && model.sort[0].desc;
}

function onHead(head) {
  model.headN = head.n;
  renderStatus(head, null);
  renderAccounts(head.accounts);
  if (model.asOfN === null) return;
  if (head.n < model.asOfN || following()) {
    load(true);            // journal replaced (refold) or follow mode
  } else {
    renderNewChip();
  }
}

let headTimer = null;

async function pollHead() {
  clearTimeout(headTimer);
  try {
    const res = await fetch('/api/head', { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    onHead(await res.json());
  } catch (e) {
    renderStatus(null, e.message);
  } finally {
    if (!model.live) headTimer = setTimeout(pollHead, HEAD_POLL_MS);
  }
}

function connect() {
  const source = new EventSource('/api/events');
  source.addEventListener('open', () => { model.live = true; clearTimeout(headTimer); });
  source.addEventListener('head', (e) => { model.live = true; onHead(JSON.parse(e.data)); });
  source.addEventListener('error', () => {
    if (model.live) { model.live = false; pollHead(); }
  });
}

// ---------------------------------------------------------------- events

function onSort(key, add) {
  const idx = model.sort.findIndex((k) => k.key === key);
  if (add) {
    if (idx >= 0) model.sort[idx].desc = !model.sort[idx].desc;
    else model.sort.push({ key, desc: false });
  } else if (idx === 0 && model.sort.length === 1) {
    model.sort[0].desc = !model.sort[0].desc;
  } else {
    model.sort = [{ key, desc: key === 'n' || key === 'date' || key === 'ingestedAt' }];
  }
  model.page = 1;
  load(true);
}

function debounce(fn, ms) {
  let t = null;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

function setFilter(name, value) {
  model.filters[name] = value;
  model.page = 1;
  load(true);
}

document.addEventListener('DOMContentLoaded', () => {
  readUrl();
  loadColumns();
  renderControls();

  document.querySelectorAll('.segmented button').forEach((b) => b.addEventListener('click', () => {
    if (model.view === b.dataset.view) return;
    model.view = b.dataset.view;
    model.page = 1;
    renderControls();
    load(true);
  }));
  $('f-q').addEventListener('input', debounce(() => setFilter('q', $('f-q').value.trim()), 250));
  $('f-account').addEventListener('change', () => setFilter('account', $('f-account').value));
  $('f-state').addEventListener('change', () => setFilter('state', $('f-state').value));
  $('f-type').addEventListener('change', () => setFilter('type', $('f-type').value));
  $('f-from').addEventListener('change', () => setFilter('from', $('f-from').value));
  $('f-to').addEventListener('change', () => setFilter('to', $('f-to').value));
  $('f-clear').addEventListener('click', () => {
    for (const f of FILTERS) model.filters[f] = '';
    model.page = 1;
    renderControls();
    load(true);
  });
  $('new-chip').addEventListener('click', () => load(true));
  $('follow').addEventListener('change', () => { if (following()) load(true); });
  $('columns-btn').addEventListener('click', (e) => {
    e.stopPropagation();
    const pop = $('columns-pop');
    pop.hidden = !pop.hidden;
    $('columns-btn').setAttribute('aria-expanded', String(!pop.hidden));
  });
  document.addEventListener('click', (e) => {
    if (!$('columns-pop').contains(e.target)) $('columns-pop').hidden = true;
  });
  $('page-size').addEventListener('change', () => { model.size = Number($('page-size').value); model.page = 1; load(false); });
  $('prev').addEventListener('click', () => { model.page = Math.max(1, model.page - 1); load(false); });
  $('next').addEventListener('click', () => { model.page += 1; load(false); });
  $('page').addEventListener('change', () => { model.page = Math.max(1, Number($('page').value) || 1); load(false); });

  load(true);
  pollHead();
  connect();
});
