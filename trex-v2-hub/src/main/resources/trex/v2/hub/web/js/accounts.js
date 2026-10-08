// Accounts mode (V2-PROPOSAL.md §10.1, §10.5): per-account earliest/latest and a facts-derived
// coverage strip. A quiet week inside the account's range is a hole — a question to check, not a
// verdict, because the log records no statement periods. Month grain is the same view, coarser.

import { api } from './api.js';
import { accountChip } from './account.js';
import { el, clear, scroll } from './dom.js';
import { money } from './format.js';
import { reportError } from './toast.js';

const WINDOWS = {
  '3m': '3 months',
  '6m': '6 months',
  '12m': '12 months',
  '24m': '24 months',
  all: 'all',
};

let host;
let ctx;
let data = null;
let win = localStorage.getItem('trex.accounts.window') || '12m';
let grain = localStorage.getItem('trex.accounts.grain') || 'week';

export function mount(container, context) {
  host = container;
  ctx = context;
  render();
  load();
  return { refresh: load };
}

async function load() {
  try {
    data = await api.accounts({ window: win, granularity: grain });
  } catch (error) {
    reportError(error);
    return;
  }
  render();
}

function render() {
  clear(host);
  host.append(el('div', { class: 'toolbar' }, controls(), summary()));
  if (!data) {
    host.append(el('p', { class: 'muted' }, 'Loading…'));
    return;
  }
  host.append(scroll(table()));
}

function controls() {
  const windowSelect = el('select', {
    onchange: (event) => {
      win = event.target.value;
      localStorage.setItem('trex.accounts.window', win);
      load();
    },
  }, ...Object.entries(WINDOWS).map(([value, label]) =>
    el('option', { value, selected: value === win }, label)));
  const grainSelect = el('select', {
    onchange: (event) => {
      grain = event.target.value;
      localStorage.setItem('trex.accounts.grain', grain);
      load();
    },
  }, ...['week', 'month'].map((value) => el('option', { value, selected: value === grain }, value)));
  return [
    el('label', {}, 'window ', windowSelect),
    el('label', {}, 'bucket ', grainSelect),
    legend('facts', 'rows'),
    legend('hole', 'hole'),
    legend('before', 'outside'),
  ];
}

function legend(state, label) {
  return el('span', { class: 'muted legend' }, el('span', { class: 'wk ' + state }), ' ' + label);
}

function summary() {
  if (!data) return el('span', { class: 'muted' }, '');
  const accounts = data.accounts || [];
  const txns = accounts.reduce((sum, a) => sum + a.txnsInWindow, 0);
  const holes = accounts.reduce((sum, a) => sum + a.holes, 0);
  const last = accounts.reduce((ms, a) => Math.max(ms, (a.lastImport && a.lastImport.startedMs) || 0), 0);
  return el('span', { class: 'muted acct-summary' },
    `${accounts.length} accounts · ${txns} txns in window · `,
    el('span', { class: 'holes' }, `${holes} hole${holes === 1 ? '' : 's'}`),
    ` · last import ${last ? rel(last) : 'never'}`);
}

function table() {
  const head = el('tr', {},
    el('th', {}, 'Account'), el('th', {}, 'Currency'), el('th', { class: 'amount' }, 'Opening'),
    el('th', {}, 'Earliest'),
    el('th', {}, 'Latest'), el('th', { class: 'amount' }, 'Txns'),
    el('th', {}, 'Last import'), el('th', { class: 'amount' }, 'Holes'),
    el('th', {}, 'Coverage'));
  const body = (data.accounts || []).map(accountRow);
  return el('table', {}, el('thead', {}, head), el('tbody', {}, ...body));
}

function accountRow(account) {
  const imp = account.lastImport;
  return el('tr', {},
    el('td', {}, el('a', { class: 'acct-link',
      href: '#blotter?' + new URLSearchParams({ account: account.ref }) },
      accountChip(ctx.refdata, account.ref))),
    el('td', { class: 'muted' }, account.currency),
    el('td', { class: 'amount' }, account.opening === null || account.opening === undefined
      ? '\u2014' : money(account.opening)),
    el('td', { class: 'muted' }, account.first || '\u2014'),
    el('td', { class: 'muted' }, account.last || '\u2014'),
    el('td', { class: 'amount' }, `${account.txnsInWindow}/${account.txns}`),
    el('td', { class: 'muted', title: imp ? imp.file : '' },
      imp ? `${imp.file} · ${rel(imp.startedMs)}` : 'never'),
    el('td', { class: 'amount' }, account.holes
      ? el('span', { class: 'holes' }, String(account.holes)) : '0'),
    el('td', {}, el('div', { class: 'weeks' }, ...account.buckets.map((b) => bucket(account.ref, b)))));
}

function bucket(ref, b) {
  const title = `${range(b.from, b.to)} · ${b.txns} txn${b.txns === 1 ? '' : 's'}`
    + (b.files && b.files.length ? '\nfrom ' + b.files.join(', ') : '');
  return el('a', {
    class: 'wk ' + b.state,
    title,
    href: '#blotter?' + new URLSearchParams({ account: ref, from: b.from, to: b.to }),
  });
}

/** A human date range: "3 Aug – 9 Aug 2026", with both years when the range crosses one. */
function range(from, to) {
  const a = new Date(from + 'T00:00:00');
  const b = new Date(to + 'T00:00:00');
  const md = (d) => d.toLocaleDateString('en-AU', { day: 'numeric', month: 'short' });
  return a.getFullYear() === b.getFullYear()
    ? `${md(a)} \u2013 ${md(b)} ${b.getFullYear()}`
    : `${md(a)} ${a.getFullYear()} \u2013 ${md(b)} ${b.getFullYear()}`;
}

function rel(ms) {
  if (!ms) return 'never';
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 90) return `${Math.round(s)}s ago`;
  if (s < 5400) return `${Math.round(s / 60)}m ago`;
  if (s < 172800) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}
