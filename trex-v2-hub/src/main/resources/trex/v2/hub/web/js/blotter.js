// Blotter mode (V2-PROPOSAL.md §10.2): the current transactions, SQL-backed filters and paging,
// with inline decisions. Selection drives the batch actions.

import { api } from './api.js';
import { accountChip } from './account.js';
import { commitmentChip, openAssignCommitment } from './commitment.js';
import { decisions } from './decisions.js';
import { direction } from './direction.js';
import { openAnnotate } from './annotate.js';
import { el, clear, field, scroll } from './dom.js';
import { money, shortId } from './format.js';
import * as since from './since.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let refdata;
let errorBar;
let tableHost;
let pager;
let pinSelect;

const filters = {
  account: '', category: '', leg: '', role: '', q: '', hasReview: false,
  sort: 'date', order: 'desc', limit: 100, offset: 0,
};
let selected = new Set();
let status = {};

export function mount(container, context) {
  host = container;
  ctx = context;
  refdata = context.refdata;
  selected = new Set();
  filters.offset = 0;
  const query = context.modeQuery || new URLSearchParams();
  if (query.has('q')) filters.q = query.get('q');
  if (query.has('account')) filters.account = query.get('account');
  if (query.has('category')) filters.category = query.get('category');
  if (query.has('leg')) filters.leg = query.get('leg');
  if (query.has('role')) filters.role = query.get('role');
  if (query.has('hasReview')) filters.hasReview = query.get('hasReview') === 'true';
  render();
  load();
  return { refresh: load };
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });

  pinSelect = el('select', { id: 'pinCategory' },
    ...refdata.categories.map((c) => el('option', { value: c }, c)));

  const toolbar = el('div', { class: 'toolbar' },
    field('Account', select('account', ['', ...refdata.accounts.map((a) => a.ref)], filters.account,
      (v) => set('account', v))),
    field('Category', select('category', ['', ...refdata.categories, 'UNCATEGORIZED'], filters.category,
      (v) => set('category', v))),
    field('Leg', select('leg', ['', 'MATCHED', 'HELD', 'EXTERNAL'], filters.leg, (v) => set('leg', v))),
    field('Role', select('role', ['', 'transaction', 'noop'], filters.role, (v) => set('role', v))),
    field('Text', el('input', {
      type: 'search', value: filters.q,
      oninput: debounce((e) => set('q', e.target.value, true), 250),
    })),
    el('label', {}, el('input', {
      type: 'checkbox', checked: filters.hasReview,
      onchange: (e) => set('hasReview', e.target.checked, true),
    }), 'has review'),
    field('Sort', select('sort', ['date', 'amount', 'category', 'account', 'n'], filters.sort,
      (v) => set('sort', v))),
    field('Order', select('order', ['desc', 'asc'], filters.order, (v) => set('order', v))),
    button('Clear filters', clearFilters),
  );

  const actions = el('div', { class: 'toolbar' },
    pinSelect,
    button('Pin', () => apply((ids) => decisions.pin(ctx, ids, pinSelect.value, 'pinned in blotter'))),
    button('Mark external', () => apply((ids) => ids.length === 1
      ? decisions.markExternal(ctx, ids[0], 'marked external in blotter') : null, 1)),
    button('Pair', () => apply((ids) => ids.length === 2 ? decisions.pair(ctx, ids[0], ids[1], 'paired in blotter') : null, 2)),
    button('Unpair', () => apply((ids) => ids.length === 2 ? decisions.unpair(ctx, ids[0], ids[1], 'unpaired in blotter') : null, 2)),
    button('Mark noop', markNoop),
    button('Unmark noop', unmarkNoop),
    button('Note', () => {
      const ids = [...selected];
      if (!ids.length) {
        toast('Select at least one row', 'bad');
        return;
      }
      openAnnotate(ctx, { ids, summary: `${ids.length} selected row${ids.length > 1 ? 's' : ''}` }, load);
    }),
  );

  tableHost = el('div');
  pager = el('div', { class: 'pager' });
  host.append(errorBar);
  // The since-clear line leads the Blotter too (QOL §5), from the visit's cached read.
  const notice = since.line();
  if (notice) host.append(notice);
  host.append(toolbar, actions, tableHost, pager);
}

async function load() {
  try {
    const params = {};
    for (const [k, v] of Object.entries(filters)) {
      if (v !== '' && v !== false && v !== null) params[k] = String(v);
    }
    status = await api.ledger(params);
    renderRows();
    renderPager();
    errorBar.hidden = true;
  } catch (error) {
    showError(error);
  }
}

function renderRows() {
  clear(tableHost);
  const head = el('tr', {},
    el('th', {}), el('th', {}, 'Date'), el('th', {}, 'Account'), el('th', {}, 'Direction'),
    el('th', { class: 'amount' }, 'Amount'),
    el('th', { class: 'amount' }, 'Balance'), el('th', {}, 'Description'), el('th', {}, 'Category'),
    el('th', {}, 'Commitment'), el('th', {}, 'Leg'), el('th', {}, 'Role'), el('th', {}, 'Rail'),
    el('th', {}, 'n'), el('th', {}, 'id'),
    el('th', {}));
  const rows = (status.rows || []).map((row) => {
    const checkbox = el('input', {
      type: 'checkbox', checked: selected.has(row.externalId),
      onchange: (e) => {
        if (e.target.checked) selected.add(row.externalId); else selected.delete(row.externalId);
      },
    });
    const classes = [row.hasReview ? 'bad' : '', row.role === 'noop' ? 'noop' : '',
      row.synthetic ? 'synthetic' : ''].filter(Boolean).join(' ');
    return el('tr', { class: classes },
      el('td', {}, checkbox),
      el('td', {}, row.date),
      el('td', {}, accountChip(refdata, row.accountRef)),
      el('td', {}, direction(row.amount)),
      el('td', { class: 'amount' }, money(row.amount)),
      el('td', { class: 'amount' }, money(row.balance)),
      el('td', { class: 'desc' }, row.rawDescription,
        row.latestNote ? el('span', { class: 'note-chip', title: row.latestNote }, '\u270e ' + row.latestNote) : null),
      el('td', {}, el('span', { class: 'tag ' + row.categoryOrigin, title: row.ruleId || '' }, row.category)),
      el('td', {}, commitmentChip(row)),
      el('td', {}, row.leg + (row.transferId ? ' \u21c4' : '')),
      el('td', {}, row.synthetic ? el('span', { class: 'tag synthetic' }, 'synthetic')
        : (row.role === 'noop' ? el('span', { class: 'tag role-noop' }, 'noop') : '')),
      el('td', { class: 'muted' }, row.rail ? row.rail + ' \u00b7 ' + (row.amount < 0 ? 'OUT' : 'IN') : ''),
      el('td', {}, row.n),
      el('td', { class: 'muted', title: row.externalId }, shortId(row.externalId)),
      el('td', {},
        el('button', {
          type: 'button', class: 'ghost',
          onclick: () => openAnnotate(ctx, { ids: [row.externalId], summary: row.rawDescription }, load),
        }, 'Note'),
        el('button', {
          type: 'button', class: 'ghost', title: 'Assign to commitment',
          onclick: () => openAssignCommitment(ctx, row, load),
        }, row.commitmentId ? 'Unassign' : 'Assign')));
  });
  tableHost.append(scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...rows))));
}

function renderPager() {
  clear(pager);
  const from = status.total === 0 ? 0 : filters.offset + 1;
  const to = Math.min(filters.offset + filters.limit, status.total);
  pager.append(
    el('span', {}, `${from}–${to} of ${status.total}`),
    button('Prev', () => { filters.offset = Math.max(0, filters.offset - filters.limit); load(); }),
    button('Next', () => { filters.offset += filters.limit; load(); }),
  );
}

function apply(build, required = 1) {
  const ids = [...selected];
  if (ids.length < required) {
    toast(`Select at least ${required} row${required > 1 ? 's' : ''}`, 'bad');
    return;
  }
  const decision = build(ids);
  if (!decision) {
    toast('Select exactly 2 rows', 'bad');
    return;
  }
  submit([decision]);
}

// A role is a classification with a reason (§6.9), so marking one asks why.
function markNoop() {
  const ids = [...selected];
  if (ids.length !== 1) {
    toast('Select exactly 1 row', 'bad');
    return;
  }
  const reason = window.prompt('Why is this row not a posting of its account?',
    'reference line, not a posting');
  if (!reason) return;
  submit([decisions.markNoop(ctx, ids[0], reason)]);
}

function unmarkNoop() {
  const ids = [...selected];
  if (ids.length !== 1) {
    toast('Select exactly 1 row', 'bad');
    return;
  }
  submit([decisions.unmarkNoop(ctx, ids[0], 'restored to a posting in blotter')]);
}

async function submit(list) {
  try {
    await api.decisions(ctx.n, list);
    selected = new Set();
    toast('Recorded');
    await load();
  } catch (error) {
    reportError(error);
    await load();
  }
}

function set(key, value, resetOffset = true) {
  filters[key] = value;
  if (resetOffset) filters.offset = 0;
  load();
}

// Reset every filter field (not the sort/order, which are view controls) and reload.
function clearFilters() {
  filters.account = '';
  filters.category = '';
  filters.leg = '';
  filters.role = '';
  filters.q = '';
  filters.hasReview = false;
  filters.offset = 0;
  render();
  load();
}

function showError(error) {
  errorBar.textContent = error.message || 'failed to load';
  errorBar.hidden = false;
}

function select(name, options, value, onChange) {
  return el('select', { onchange: (e) => onChange(e.target.value) },
    ...options.map((o) => el('option', { value: o, selected: o === value }, o === '' ? '—' : o)));
}

function button(label, onClick) {
  return el('button', { type: 'button', onclick: onClick }, label);
}

function debounce(fn, ms) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}
