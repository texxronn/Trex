// Eyeball mode (V2-PROPOSAL.md §10.3): a bucketed blotter you walk at your own cadence. Nested
// tabs choose the grain (Daily / Weekly / Monthly); within it, transactions are grouped by day and
// each row can be categorised in place (a PIN, one row at a time, on purpose).

import { api } from './api.js';
import { openCategorize } from './categorize.js';
import { decisions } from './decisions.js';
import { el, clear, field, scroll } from './dom.js';
import { money, shortId } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let categories = [];
let grain = 'week';
let period;
let walk = null;
let ack = null;

let errorBar;
let jumpInput;
let periodLabel;
let markerHost;
let bodyHost;

const GRAINS = ['day', 'week', 'month'];
const GRAIN_LABEL = { day: 'Daily', week: 'Weekly', month: 'Monthly' };

const KIND_LABEL = {
  BALANCE_CHAIN_BREAK: 'Balance chain break',
  NEW_MERCHANT_STEM: 'New merchant',
  AMOUNT_OUTLIER: 'Large amount',
  DUPLICATE_LOOKING: 'Duplicate-looking',
  RECURRING_MISSING: 'Recurring charge missing',
  UNMATCHED_LEG: 'Transfer unmatched',
  STALE_PENDING: 'Stale pending',
  UNCATEGORIZED: 'Uncategorized',
  ACCOUNT_SILENT: 'Account quiet',
};

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
  markerHost = el('div');
  bodyHost = el('div');

  host.append(errorBar,
    el('div', { class: 'toolbar' },
      el('div', { class: 'tabs' }, ...GRAINS.map(tab)),
      button('\u2039', () => step(-1)),
      field('Jump', jumpInput),
      periodLabel,
      button('\u203a', () => step(1)),
      el('button', { type: 'button', class: 'primary', onclick: closePeriod }, 'Close period')),
    markerHost, bodyHost);
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
    // The grain is the period walked; inside it, transactions group by day.
    walk = await api.eyeball(period, ctx.user, { bucket: 'day' });
    const acks = await api.acks();
    ack = acks.find((a) => a.user === ctx.user && a.period === period) || null;
    errorBar.hidden = true;
    renderHeading();
    renderMarker();
    renderBody();
  } catch (error) {
    showError(error);
  }
}

function renderHeading() {
  periodLabel.textContent = `${GRAIN_LABEL[grain]} · ${labelOf(period)}`;
  const b = boundsOf(period);
  if (b) jumpInput.value = isoDay(b.from);
}

function renderMarker() {
  clear(markerHost);
  if (!ack) {
    markerHost.append(el('p', { class: 'muted' }, `Not closed yet for ${ctx.user}.`));
    return;
  }
  const state = ack.stale
    ? el('span', { class: 'tag NONE' }, 'changed since reviewed')
    : el('span', { class: 'tag good' }, 'green');
  markerHost.append(el('p', {},
    state, ' ',
    `closed by ${ack.user} at n=${ack.throughN} on ${ack.ackedAt}`,
    ack.stale ? el('button', { type: 'button', onclick: showMoved }, 'Moved\u2026') : null));
}

function renderBody() {
  clear(bodyHost);
  if (!walk) return;
  const anomalies = walk.anomalies || [];
  const openItems = walk.openItems || [];
  const buckets = walk.buckets || [];
  bodyHost.append(el('p', { class: 'muted' },
    `${anomalies.length} anomal${anomalies.length === 1 ? 'y' : 'ies'} · `
    + `${openItems.length} open item${openItems.length === 1 ? '' : 's'} · `
    + `${buckets.length} day${buckets.length === 1 ? '' : 's'}`));
  bodyHost.append(section('Open items', openItemsTable(openItems)));
  bodyHost.append(section('Anomalies', anomaliesList(anomalies)));
  bodyHost.append(section('Transactions', bucketsList(buckets)));
}

// ---- open items -------------------------------------------------------------------------------

function openItemsTable(items) {
  if (!items.length) return el('p', { class: 'muted' }, 'Nothing open in this period.');
  const head = el('tr', {}, el('th', {}, 'Kind'), el('th', {}, 'About'), el('th', {}, 'Detail'),
    el('th', { class: 'amount' }, 'Stake'), el('th', { class: 'amount' }, 'Opened'), el('th', {}));
  const body = items.map((item) => el('tr', {},
    el('td', {}, el('span', { class: 'badge ' + item.kind }, KIND_LABEL[item.kind] || item.kind)),
    el('td', { class: 'desc', title: item.subject }, item.subjectDescription || shortId(item.subject)),
    el('td', { class: 'desc' }, item.detail || ''),
    el('td', { class: 'amount' }, item.amountStake == null ? '' : money(item.amountStake)),
    el('td', { class: 'amount muted' }, (item.openedAt || '').slice(0, 10)),
    el('td', {}, button('Dismiss', () => submit(
      decisions.dismiss(ctx, item.kind, [item.subject], 'dismissed in eyeball'))))));
  return scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body)));
}

// ---- anomalies --------------------------------------------------------------------------------

function anomaliesList(anomalies) {
  if (!anomalies.length) return el('p', { class: 'muted' }, 'No anomalies in this period.');
  const byKind = new Map();
  for (const a of anomalies) {
    if (!byKind.has(a.kind)) byKind.set(a.kind, []);
    byKind.get(a.kind).push(a);
  }
  const blocks = [];
  for (const [kind, rows] of byKind) {
    blocks.push(el('div', {},
      el('h4', {}, `${KIND_LABEL[kind] || kind} (${rows.length})`),
      scroll(el('table', {}, el('tbody', {}, ...rows.map(anomalyRow))))));
  }
  return el('div', {}, ...blocks);
}

function anomalyRow(a) {
  return el('tr', {},
    el('td', {}, a.date || ''),
    el('td', {}, a.accountRef || ''),
    el('td', { class: 'amount' }, a.amount == null ? '' : money(a.amount)),
    el('td', { class: 'desc' }, a.detail || ''),
    el('td', {}, linkTo(a)));
}

function linkTo(a) {
  const params = {};
  if (a.accountRef) params.account = a.accountRef;
  if (a.kind === 'UNCATEGORIZED' && a.detail) params.q = a.detail;
  if (!Object.keys(params).length) return null;
  return el('a', { href: '#blotter?' + new URLSearchParams(params) }, 'open \u2192');
}

// ---- the buckets ------------------------------------------------------------------------------

function bucketsList(buckets) {
  if (!buckets.length) return el('p', { class: 'muted' }, 'No transactions in this period.');
  return el('div', {}, ...buckets.map(bucketBlock));
}

function bucketBlock(b) {
  const closing = Object.entries(b.closingBalances || {})
    .map(([account, balance]) => `${account} ${money(balance)}`).join(' · ');
  const head = el('tr', {}, el('th', {}, 'Date'), el('th', {}, 'Account'),
    el('th', { class: 'amount' }, 'Amount'), el('th', {}, 'Description'),
    el('th', {}, 'Category'), el('th', {}, 'Leg'), el('th', {}, 'n'), el('th', {}, 'id'),
    el('th', {}, ''));
  const body = b.rows.map((row) => el('tr', { class: row.category === 'UNCATEGORIZED' ? 'bad' : '' },
    el('td', {}, row.date),
    el('td', {}, row.accountRef),
    el('td', { class: 'amount' }, money(row.amount)),
    el('td', { class: 'desc' }, row.rawDescription),
    el('td', {}, el('span', { class: 'tag ' + row.categoryOrigin, title: row.ruleId || '' }, row.category)),
    el('td', {}, row.leg + (row.transferId ? ' \u21c4' : '')),
    el('td', {}, row.n),
    el('td', { class: 'muted', title: row.externalId }, shortId(row.externalId)),
    el('td', {}, button('Categorize', () => openCategorize(ctx, row, categories, load)))));
  return el('div', { class: 'bucket' },
    el('h4', {}, `${b.key}  ·  ${b.from}${b.from === b.to ? '' : ' \u2013 ' + b.to}`
      + `  ·  total ${money(b.total)}  ·  closing ${closing}`),
    scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body))));
}

// ---- actions ----------------------------------------------------------------------------------

async function submit(decision) {
  try {
    await api.decisions(ctx.n, [decision]);
    toast('Recorded');
  } catch (error) {
    reportError(error);
  }
  await load();
}

async function closePeriod() {
  try {
    await api.postAck({ user: ctx.user, period, comment: null });
    toast(`Closed ${period} for ${ctx.user}`);
    await load();
  } catch (error) {
    reportError(error);
  }
}

async function showMoved() {
  if (!ack) return;
  try {
    const diff = await api.ackDiff(ack.user, ack.period);
    toast(diff.moved.length ? 'Moved: ' + diff.moved.join(', ') : 'Nothing moved');
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

// ---- period keys and their date ranges --------------------------------------------------------

function keyFor(date, g) {
  if (g === 'day') return isoDay(date);
  if (g === 'month') return monthKey(date);
  return isoWeek(date);
}

/** The inclusive UTC range a period key names, matching the hub's Period. */
function boundsOf(key) {
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(key);
  if (day) {
    const d = new Date(Date.UTC(+day[1], +day[2] - 1, +day[3]));
    return { from: d, to: d };
  }
  const week = /^(\d{4})-W(\d{2})$/.exec(key);
  if (week) {
    const jan4 = new Date(Date.UTC(+week[1], 0, 4));
    const wd = jan4.getUTCDay() || 7;
    const from = new Date(jan4.getTime() - (wd - 1) * 86400000 + (+week[2] - 1) * 7 * 86400000);
    return { from, to: new Date(from.getTime() + 6 * 86400000) };
  }
  const month = /^(\d{4})-(\d{2})$/.exec(key);
  if (month) {
    return {
      from: new Date(Date.UTC(+month[1], +month[2] - 1, 1)),
      to: new Date(Date.UTC(+month[1], +month[2], 0)),
    };
  }
  return null;
}

/** A compact range label, e.g. 20260120-20260126. */
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

function section(title, content) {
  return el('section', { class: 'mode-section' }, el('h3', {}, title), content);
}

function button(label, onClick) {
  return el('button', { type: 'button', onclick: onClick }, label);
}

function showError(error) {
  errorBar.textContent = error.message || 'failed to load the walk';
  errorBar.hidden = false;
}
