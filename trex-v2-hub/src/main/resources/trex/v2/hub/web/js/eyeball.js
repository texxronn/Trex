// Eyeball mode (V2-PROPOSAL.md §10.3): the guided period walk. Open items, the anomaly checks and
// the day-by-day transactions, then one click closes the period for the acting user alone.

import { api } from './api.js';
import { decisions } from './decisions.js';
import { el, clear, field } from './dom.js';
import { money, shortId } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let categories = [];
let period;
let walk = null;
let ack = null;

let errorBar;
let periodInput;
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
  markerHost = el('div');
  bodyHost = el('div');
  host.append(errorBar,
    el('div', { class: 'toolbar' },
      field('Period', periodInput),
      button('\u2039 Prev', () => shift(-7)),
      button('Next \u203a', () => shift(7)),
      el('button', { type: 'button', class: 'primary', onclick: closePeriod }, 'Close period'),
      el('span', { class: 'muted' }, 'the period goes green for you alone')),
    markerHost, bodyHost);
}

async function load() {
  try {
    walk = await api.eyeball(period, ctx.user);
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
  const days = walk.days || [];
  bodyHost.append(el('p', { class: 'muted' },
    `${anomalies.length} anomal${anomalies.length === 1 ? 'y' : 'ies'} · `
    + `${openItems.length} open item${openItems.length === 1 ? '' : 's'} · `
    + `${days.length} day${days.length === 1 ? '' : 's'}`));
  bodyHost.append(section('Open items', openItemsTable(openItems)));
  bodyHost.append(section('Anomalies', anomaliesList(anomalies)));
  bodyHost.append(section('Day by day', daysList(days)));
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
  return el('table', {}, el('thead', {}, head), el('tbody', {}, ...body));
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
      el('table', {}, el('tbody', {}, ...rows.map(anomalyRow)))));
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

// ---- day by day -------------------------------------------------------------------------------

function daysList(days) {
  if (!days.length) return el('p', { class: 'muted' }, 'No transactions in this period.');
  return el('div', {}, ...days.map(dayBlock));
}

function dayBlock(day) {
  const closing = Object.entries(day.closingBalances || {})
    .map(([account, balance]) => `${account} ${money(balance)}`).join(' · ');
  const head = el('tr', {}, el('th', {}, 'Account'), el('th', { class: 'amount' }, 'Amount'),
    el('th', {}, 'Description'), el('th', {}, 'Category'), el('th', {}, 'Leg'), el('th', {}, 'n'),
    el('th', {}, 'id'), el('th', {}, ''));
  const body = day.rows.map((row) => el('tr', {},
    el('td', {}, row.accountRef),
    el('td', { class: 'amount' }, money(row.amount)),
    el('td', { class: 'desc' }, row.rawDescription),
    el('td', {}, el('span', { class: 'tag ' + row.categoryOrigin, title: row.ruleId || '' }, row.category)),
    el('td', {}, row.leg + (row.transferId ? ' \u21c4' : '')),
    el('td', {}, row.n),
    el('td', { class: 'muted', title: row.externalId }, shortId(row.externalId)),
    el('td', {}, pinControl(row))));
  return el('div', { class: 'day' },
    el('h4', {}, `${day.date}  ·  total ${money(day.total)}  ·  closing ${closing}`),
    el('table', {}, el('thead', {}, head), el('tbody', {}, ...body)));
}

function pinControl(row) {
  const select = el('select', {}, ...categories.map((c) => el('option', { value: c }, c)));
  return el('span', { class: 'pin' }, select,
    button('Pin', () => submit(decisions.pin(ctx, [row.externalId], select.value, 'pinned in eyeball'))));
}

// ---- actions ----------------------------------------------------------------------------------

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

function shift(days) {
  const start = weekStart(period) || new Date();
  const moved = new Date(start.getTime() + days * 86400000);
  period = isoWeek(moved);
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

function showError(error) {
  errorBar.textContent = error.message || 'failed to load the walk';
  errorBar.hidden = false;
}

function currentPeriod() {
  return isoWeek(new Date());
}

function isoWeek(date) {
  const d = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
  const day = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - day);
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  const week = Math.ceil((((d - yearStart) / 86400000) + 1) / 7);
  return `${d.getUTCFullYear()}-W${String(week).padStart(2, '0')}`;
}

function weekStart(value) {
  const match = /^(\d{4})-W(\d{2})$/.exec(value);
  if (!match) return null;
  const jan4 = new Date(Date.UTC(Number(match[1]), 0, 4));
  const day = jan4.getUTCDay() || 7;
  return new Date(jan4.getTime() - (day - 1) * 86400000 + (Number(match[2]) - 1) * 7 * 86400000);
}
