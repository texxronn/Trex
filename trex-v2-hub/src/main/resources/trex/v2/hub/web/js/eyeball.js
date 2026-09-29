// Eyeball mode (V2-PROPOSAL.md §10.3): the Blotter list, scoped to a period, with a read state.
// The nested tabs pick the period grain (Daily / Weekly / Monthly). A transaction covered by the
// acting user's USER_ACK is "read" and renders faded; the rest are unread and bright. "Mark read"
// writes that USER_ACK. Each row can be re-categorised in place (a PIN).
//
// Open items and anomalies (the walk's other sections) are parked for now; the API still returns
// them, and the UI will bring them back.

import { api } from './api.js';
import { openCategorize } from './categorize.js';
import { el, clear, field, scroll } from './dom.js';
import { money, shortId } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let categories = [];
let grain = 'week';
let period;
let rows = [];
let acks = [];
let ack = null;

let errorBar;
let jumpInput;
let periodLabel;
let stateChip;
let tableHost;

const GRAINS = ['day', 'week', 'month'];
const GRAIN_LABEL = { day: 'Daily', week: 'Weekly', month: 'Monthly' };

export function mount(container, context) {
  host = container;
  ctx = context;
  categories = (context.refdata && context.refdata.categories) || [];
  grain = localStorage.getItem('trex.eyeball.grain') || 'week';
  const query = context.modeQuery || new URLSearchParams();
  period = query.get('period') || keyFor(new Date(), grain);
  render();
  load();
  return { refresh: load };
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  jumpInput = el('input', {
    type: 'date',
    onchange: (e) => {
      if (e.target.value) {
        period = keyFor(parseIso(e.target.value), grain);
        load();
      }
    },
  });
  periodLabel = el('span', { class: 'period muted' });
  stateChip = el('span', {});
  tableHost = el('div');

  host.append(errorBar,
    el('div', { class: 'toolbar' },
      el('div', { class: 'tabs' }, ...GRAINS.map(tab)),
      button('\u2039', () => step(-1)),
      field('Jump', jumpInput),
      periodLabel,
      button('\u203a', () => step(1)),
      stateChip,
      el('button', { type: 'button', class: 'primary', onclick: markRead }, 'Mark read')),
    el('p', { class: 'muted hint' },
      'Bright rows are unread; Mark read fades the period for you. Categorize pins a row.'),
    tableHost);
}

function tab(g) {
  return el('button', {
    type: 'button',
    class: 'tab' + (g === grain ? ' active' : ''),
    onclick: () => {
      if (g === grain) return;
      grain = g;
      localStorage.setItem('trex.eyeball.grain', g);
      const b = boundsOf(period);
      if (b) period = keyFor(b.from, grain);
      render();
      load();
    },
  }, GRAIN_LABEL[g]);
}

async function load() {
  try {
    const walk = await api.eyeball(period, ctx.user, { bucket: 'day' });
    rows = (walk.buckets || []).flatMap((b) => b.rows).sort(byDateThenN);
    const all = await api.acks();
    acks = all.filter((a) => a.user === ctx.user);
    ack = acks.find((a) => a.period === period) || null;
    errorBar.hidden = true;
    renderHeading();
    renderTable();
  } catch (error) {
    showError(error);
  }
}

function renderHeading() {
  periodLabel.textContent = `${GRAIN_LABEL[grain]} · ${labelOf(period)}`;
  const b = boundsOf(period);
  if (b) jumpInput.value = isoDay(b.from);
  clear(stateChip);
  stateChip.append(ack
    ? (ack.stale
      ? el('span', { class: 'tag NONE' }, 'changed since read')
      : el('span', { class: 'tag good' }, 'read'))
    : el('span', { class: 'tag' }, 'unread'));
}

function renderTable() {
  clear(tableHost);
  if (!rows.length) {
    tableHost.append(el('p', { class: 'muted' }, 'No transactions in this period.'));
    return;
  }
  // Exactly the Blotter's columns, plus a per-row action; no checkbox or batch bar.
  const head = el('tr', {},
    el('th', {}, 'Date'), el('th', {}, 'Account'), el('th', { class: 'amount' }, 'Amount'),
    el('th', { class: 'amount' }, 'Balance'), el('th', {}, 'Description'),
    el('th', {}, 'Category'), el('th', {}, 'Leg'), el('th', {}, 'n'), el('th', {}, 'id'),
    el('th', {}, ''));
  const body = rows.map((row) => {
    const read = isRead(row.date);
    return el('tr', { class: read ? 'read' : 'unread' },
      el('td', {}, row.date),
      el('td', {}, row.accountRef),
      el('td', { class: 'amount' }, money(row.amount)),
      el('td', { class: 'amount' }, money(row.balance)),
      el('td', { class: 'desc' }, row.rawDescription),
      el('td', {}, el('span', { class: 'tag ' + row.categoryOrigin, title: row.ruleId || '' }, row.category)),
      el('td', {}, row.leg + (row.transferId ? ' \u21c4' : '')),
      el('td', {}, row.n),
      el('td', { class: 'muted', title: row.externalId }, shortId(row.externalId)),
      el('td', {}, button('Categorize', () => openCategorize(ctx, row, categories, load))));
  });
  tableHost.append(scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body))));
}

/** Read means the acting user has a USER_ACK whose period covers this row's date. */
function isRead(dateIso) {
  const d = parseIso(dateIso);
  return acks.some((a) => covers(a.period, d));
}

async function markRead() {
  try {
    await api.postAck({ user: ctx.user, period, comment: null });
    toast(`Marked ${period} read for ${ctx.user}`);
    await load();
  } catch (error) {
    reportError(error);
  }
}

function step(direction) {
  const b = boundsOf(period);
  if (!b) return;
  const d = new Date(b.from.getTime());
  if (grain === 'month') {
    d.setUTCMonth(d.getUTCMonth() + direction);
  } else if (grain === 'week') {
    d.setUTCDate(d.getUTCDate() + 7 * direction);
  } else {
    d.setUTCDate(d.getUTCDate() + direction);
  }
  period = keyFor(d, grain);
  load();
}

function byDateThenN(a, b) {
  return a.date < b.date ? -1 : a.date > b.date ? 1 : a.n - b.n;
}

// ---- period keys and their date ranges --------------------------------------------------------

function keyFor(date, g) {
  if (g === 'day') return isoDay(date);
  if (g === 'month') return monthKey(date);
  return isoWeek(date);
}

/** The inclusive UTC range a period key names, matching the hub's Period (day…year). */
function boundsOf(key) {
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(key);
  if (day) {
    const d = new Date(Date.UTC(+day[1], +day[2] - 1, +day[3]));
    return { from: d, to: d };
  }
  const year = /^(\d{4})$/.exec(key);
  if (year) {
    return { from: new Date(Date.UTC(+year[1], 0, 1)), to: new Date(Date.UTC(+year[1], 11, 31)) };
  }
  const quarter = /^(\d{4})-Q([1-4])$/.exec(key);
  if (quarter) {
    const m = (+quarter[2] - 1) * 3;
    return { from: new Date(Date.UTC(+quarter[1], m, 1)), to: new Date(Date.UTC(+quarter[1], m + 3, 0)) };
  }
  const month = /^(\d{4})-(\d{2})$/.exec(key);
  if (month) {
    return {
      from: new Date(Date.UTC(+month[1], +month[2] - 1, 1)),
      to: new Date(Date.UTC(+month[1], +month[2], 0)),
    };
  }
  const week = /^(\d{4})-W(\d{2})$/.exec(key);
  if (week) {
    const jan4 = new Date(Date.UTC(+week[1], 0, 4));
    const wd = jan4.getUTCDay() || 7;
    const from = new Date(jan4.getTime() - (wd - 1) * 86400000 + (+week[2] - 1) * 7 * 86400000);
    return { from, to: new Date(from.getTime() + 6 * 86400000) };
  }
  return null;
}

function covers(key, date) {
  const b = boundsOf(key);
  return b !== null && date >= b.from && date <= b.to;
}

function labelOf(key) {
  const b = boundsOf(key);
  if (!b) return key;
  return b.from.getTime() === b.to.getTime() ? compact(b.from) : `${compact(b.from)}-${compact(b.to)}`;
}

function compact(d) {
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`
    + `${String(d.getUTCDate()).padStart(2, '0')}`;
}

function isoDay(date) {
  return date.toISOString().slice(0, 10);
}

function monthKey(date) {
  return date.toISOString().slice(0, 7);
}

function isoWeek(date) {
  const d = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
  const day = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - day);
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  const week = Math.ceil((((d - yearStart) / 86400000) + 1) / 7);
  return `${d.getUTCFullYear()}-W${String(week).padStart(2, '0')}`;
}

function parseIso(value) {
  const [y, m, d] = value.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

// ---- helpers ----------------------------------------------------------------------------------

function button(label, onClick) {
  return el('button', { type: 'button', onclick: onClick }, label);
}

function showError(error) {
  errorBar.textContent = error.message || 'failed to load the period';
  errorBar.hidden = false;
}
