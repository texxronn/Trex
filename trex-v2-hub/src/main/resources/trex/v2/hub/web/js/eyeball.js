// Eyeball mode (V2-PROPOSAL.md §10.3): the per-user period walk — close a period, and see what
// moved in one already closed.

import { api } from './api.js';
import { el, clear, field } from './dom.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let listHost;
let errorBar;
let periodInput;

export function mount(container, context) {
  host = container;
  ctx = context;
  render();
  load();
  return { refresh: load };
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  periodInput = el('input', { type: 'text', placeholder: '2026-W39', value: defaultPeriod() });
  listHost = el('div');
  host.append(errorBar,
    el('div', { class: 'toolbar' },
      field('Period', periodInput),
      el('button', { type: 'button', class: 'primary', onclick: closePeriod }, 'Close period'),
      el('span', { class: 'muted' }, 'the period goes green for you alone')),
    listHost);
}

function defaultPeriod() {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
}

async function load() {
  try {
    const rows = await api.acks();
    renderRows(rows);
    errorBar.hidden = true;
  } catch (error) {
    errorBar.textContent = error.message || 'failed to load markers';
    errorBar.hidden = false;
  }
}

function renderRows(rows) {
  clear(listHost);
  if (!rows.length) {
    listHost.append(el('p', { class: 'muted' }, 'No periods acknowledged yet.'));
    return;
  }
  const head = el('tr', {}, el('th', {}, 'User'), el('th', {}, 'Period'), el('th', {}, 'Through'),
    el('th', {}, 'State'), el('th', {}, 'Acked'), el('th', {}));
  const body = rows.map((row) => el('tr', {},
    el('td', {}, row.user),
    el('td', {}, row.period),
    el('td', {}, row.throughN),
    el('td', {}, row.stale
      ? el('span', { class: 'tag NONE' }, 'changed since reviewed')
      : el('span', { class: 'tag good' }, 'green')),
    el('td', { class: 'muted' }, row.ackedAt),
    el('td', {}, row.stale
      ? el('button', { type: 'button', onclick: () => showMoved(row) }, 'Moved\u2026')
      : null)));
  listHost.append(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body)));
}

async function closePeriod() {
  const period = periodInput.value.trim();
  if (!period) return;
  try {
    await api.postAck({ user: ctx.user, period, comment: null });
    toast(`Closed ${period} for ${ctx.user}`);
    await load();
  } catch (error) {
    reportError(error);
  }
}

async function showMoved(row) {
  try {
    const diff = await api.ackDiff(row.user, row.period);
    toast(diff.moved.length ? 'Moved: ' + diff.moved.join(', ') : 'Nothing moved');
  } catch (error) {
    reportError(error);
  }
}
