// Eyeball mode (V2-PROPOSAL.md §10.3): the guided period walk. Open items, the anomaly checks and
// the transactions bucketed by day, week or month. Each row is pinned one at a time on purpose —
// the mechanics are quick (pick a category once, then one click per row) but the review itself is
// meant to be done with your eyes on the transaction, so there is no bulk action.

import { api } from './api.js';
import { decisions } from './decisions.js';
import { el, clear, field, scroll } from './dom.js';
import { money, shortId } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let categories = [];
let period;
let bucket;
let walk = null;
let ack = null;

let errorBar;
let periodInput;
let categorySelect;
let markerHost;
let bodyHost;

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
  const query = context.modeQuery || new URLSearchParams();
  period = query.get('period') || currentPeriod();
  bucket = localStorage.getItem('trex.eyeball.bucket') || 'week';
  render();
  load();
  return { refresh: load };
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  periodInput = el('input', {
    type: 'text', placeholder: '2026-W39', value: period,
    onkeydown: (e) => {
      if (e.key === 'Enter') {
        period = periodInput.value.trim();
        load();
      }
    },
  });
  categorySelect = el('select', {
    id: 'pinCategory',
    onchange: (e) => localStorage.setItem('trex.eyeball.pinCategory', e.target.value),
  },
    el('option', { value: '' }, '\u2014 pin as \u2014'),
    ...categories.map((c) => el('option', { value: c }, c)));
  categorySelect.value = localStorage.getItem('trex.eyeball.pinCategory') || '';

  markerHost = el('div');
  bodyHost = el('div');
  host.append(errorBar,
    el('div', { class: 'toolbar' },
      field('Period', periodInput),
      button('\u2039 Prev', () => shift(-1)),
      button('Next \u203a', () => shift(1)),
      field('Bucket', select(['day', 'week', 'month'], bucket, (v) => {
        bucket = v;
        localStorage.setItem('trex.eyeball.bucket', v);
        load();
      })),
      field('Pin as', categorySelect),
      el('button', { type: 'button', class: 'primary', onclick: closePeriod }, 'Close period')),
    markerHost, bodyHost);
}

async function load() {
  try {
    walk = await api.eyeball(period, ctx.user, { bucket });
    const acks = await api.acks();
    ack = acks.find((a) => a.user === ctx.user && a.period === period) || null;
    errorBar.hidden = true;
    renderMarker();
    renderBody();
  } catch (error) {
    showError(error);
  }
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
    + `${buckets.length} ${walk.granularity} bucket${buckets.length === 1 ? '' : 's'}`));
  bodyHost.append(section('Open items', openItemsTable(openItems)));
  bodyHost.append(section('Anomalies', anomaliesList(anomalies)));
  bodyHost.append(section('Transactions', bucketsList(buckets)));
}

// ---- open items -------------------------------------------------------------------------------

function openItemsTable(items) {
  if (!items.length) return el('p', { class: 'muted' }, 'Nothing open in this period.');
  const head = el('tr', {}, el('th', {}, 'Kind'), el('th', {}, 'Subject'), el('th', {}, 'Detail'),
    el('th', { class: 'amount' }, 'Stake'), el('th', {}, ''));
  const body = items.map((item) => el('tr', {},
    el('td', {}, item.kind),
    el('td', { title: item.subject }, shortId(item.subject)),
    el('td', { class: 'desc' }, item.detail || ''),
    el('td', { class: 'amount' }, item.amountStake == null ? '' : money(item.amountStake)),
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
  const span = b.from === b.to ? b.from : `${b.from} \u2013 ${b.to}`;
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
    el('td', {}, button('Pin', () => pinRow(row)))));
  return el('div', { class: 'bucket' },
    el('h4', {}, `${b.key}  ·  ${span}  ·  total ${money(b.total)}  ·  closing ${closing}`),
    scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body))));
}

// ---- actions ----------------------------------------------------------------------------------

/** One row, one decision — on purpose (see the file header). The brush category is chosen once. */
function pinRow(row) {
  const category = categorySelect.value;
  if (!category) {
    toast('Pick a category in "Pin as" first', 'bad');
    return;
  }
  submit(decisions.pin(ctx, [row.externalId], category, 'pinned in eyeball'));
}

async function submit(decision) {
  try {
    await api.decisions(ctx.n, [decision]);
    toast('Recorded');
    await load();
  } catch (error) {
    reportError(error);
    await load();
  }
}

async function closePeriod() {
  const value = periodInput.value.trim();
  if (!value) return;
  period = value;
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

/** Prev/next moves a week for week buckets, a month for months, a day otherwise. */
function shift(direction) {
  const start = bucketStart(period) || new Date();
  const moved = new Date(start.getTime());
  if (bucket === 'month') {
    moved.setUTCMonth(moved.getUTCMonth() + direction);
  } else if (bucket === 'week') {
    moved.setUTCDate(moved.getUTCDate() + direction * 7);
  } else {
    moved.setUTCDate(moved.getUTCDate() + direction);
  }
  period = bucket === 'month' ? monthKey(moved) : (bucket === 'week' ? isoWeek(moved) : isoDay(moved));
  periodInput.value = period;
  load();
}

// ---- helpers ----------------------------------------------------------------------------------

function section(title, content) {
  return el('section', { class: 'mode-section' }, el('h3', {}, title), content);
}

function button(label, onClick) {
  return el('button', { type: 'button', onclick: onClick }, label);
}

function select(options, value, onChange) {
  return el('select', { onchange: (e) => onChange(e.target.value) },
    ...options.map((o) => el('option', { value: o, selected: o === value }, o)));
}

function showError(error) {
  errorBar.textContent = error.message || 'failed to load the walk';
  errorBar.hidden = false;
}

function currentPeriod() {
  return isoWeek(new Date());
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

/** The first day of a period key, for stepping forward and back. */
function bucketStart(value) {
  const week = /^(\d{4})-W(\d{2})$/.exec(value);
  if (week) {
    const jan4 = new Date(Date.UTC(Number(week[1]), 0, 4));
    const day = jan4.getUTCDay() || 7;
    return new Date(jan4.getTime() - (day - 1) * 86400000 + (Number(week[2]) - 1) * 7 * 86400000);
  }
  const month = /^(\d{4})-(\d{2})$/.exec(value);
  if (month) {
    return new Date(Date.UTC(Number(month[1]), Number(month[2]) - 1, 1));
  }
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (day) {
    return new Date(Date.UTC(Number(day[1]), Number(day[2]) - 1, Number(day[3])));
  }
  return null;
}
