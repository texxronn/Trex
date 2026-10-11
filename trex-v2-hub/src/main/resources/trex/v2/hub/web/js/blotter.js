// Blotter mode (V2-PROPOSAL.md §10.2): the current transactions, SQL-backed filters and paging,
// with inline decisions. Selection drives the batch targets; a right-click opens the action menu,
// and every action is confirmed in a dialog before anything is recorded.

import { api } from './api.js';
import { accountChip } from './account.js';
import { commitmentChip, openAssignCommitment } from './commitment.js';
import { decisions } from './decisions.js';
import { direction } from './direction.js';
import { openAnnotate } from './annotate.js';
import { openConfirm, openPrompt, openSelect } from './dialog.js';
import { el, clear, field, scroll } from './dom.js';
import { date, money, shortId } from './format.js';
import { openContextMenu } from './menu.js';
import * as since from './since.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let refdata;
let errorBar;
let tableHost;
let pager;

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

  tableHost = el('div');
  pager = el('div', { class: 'pager' });
  host.append(errorBar);
  // The since-clear line leads the Blotter too (QOL §5), from the visit's cached read.
  const notice = since.line();
  if (notice) host.append(notice);
  host.append(toolbar, tableHost, pager);
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
    return el('tr', { class: classes, oncontextmenu: (e) => openRowMenu(e, row) },
      el('td', {}, checkbox),
      el('td', {}, date(row.date)),
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
      el('td', {}));
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

/**
 * The Blotter's action surface is a right-click on a row (deliberate friction): every menu item
 * opens a dialog, and nothing is recorded until that dialog is confirmed. The target is the row
 * itself unless it is part of the checked selection; pairing always needs exactly two checked
 * rows, never the ad-hoc row.
 */
function openRowMenu(e, row) {
  e.preventDefault();
  const targets = selected.has(row.externalId) ? [...selected] : [row.externalId];
  const checked = [...selected];
  const summary = targetSummary(row, targets);
  const one = targets.length === 1;
  const two = checked.length === 2;
  openContextMenu(e.clientX, e.clientY, [
    { label: 'Note', enabled: targets.length >= 1, hint: 'select at least one row',
      onPick: () => openAnnotate(ctx, { ids: targets, summary }, load) },
    { label: row.commitmentId ? 'Unassign\u2026' : 'Assign\u2026', enabled: one,
      hint: 'select one row to assign',
      onPick: () => openAssignCommitment(ctx, row, load) },
    { label: 'Pin category\u2026', enabled: targets.length >= 1, hint: 'select at least one row',
      onPick: () => pinCategory(row, targets, summary) },
    { label: 'Mark external', enabled: one, hint: 'select exactly one row',
      onPick: () => markExternal(row, targets[0]) },
    { label: 'Pair\u2026', enabled: two, hint: 'select exactly two rows',
      onPick: () => confirmPair(checked) },
    { label: 'Unpair\u2026', enabled: two, hint: 'select exactly two rows',
      onPick: () => confirmUnpair(checked) },
    { label: 'Mark noop\u2026', enabled: one, hint: 'select exactly one row',
      onPick: () => markNoop(row, targets[0]) },
    { label: 'Unmark noop', enabled: one, hint: 'select exactly one row',
      onPick: () => unmarkNoop(row, targets[0]) },
  ]);
}

// One decision for all targets: the pin applies the chosen category to every id in the batch.
function pinCategory(row, targets, summary) {
  openSelect({
    title: 'Pin category',
    summary,
    label: 'Category',
    choices: refdata.categories.map((c) => ({ value: c, label: c })),
    selected: row.category,
    confirm: 'Pin',
  }, (value) => submit([decisions.pin(ctx, targets, value, 'pinned in blotter')]));
}

function markExternal(row, id) {
  openConfirm({
    title: 'Mark external',
    lines: [rowLine(row)],
    warning: 'It leaves the transfer matcher and reads as an ordinary transaction.',
    confirm: 'Mark external',
  }, () => submit([decisions.markExternal(ctx, id, 'marked external in blotter')]));
}

function confirmPair(ids) {
  openConfirm({
    title: 'Pair',
    lines: confirmedLines(ids),
    confirm: 'Pair',
  }, () => submit([decisions.pair(ctx, ids[0], ids[1], 'paired in blotter')]));
}

function confirmUnpair(ids) {
  openConfirm({
    title: 'Unpair',
    lines: confirmedLines(ids),
    confirm: 'Unpair',
  }, () => submit([decisions.unpair(ctx, ids[0], ids[1], 'unpaired in blotter')]));
}

// A role is a classification with a reason (§6.9), so marking one asks why.
function markNoop(row, id) {
  openPrompt({
    title: 'Mark noop',
    summary: rowLine(row),
    label: 'Why is this row not a posting of its account?',
    placeholder: 'reference line, not a posting',
    confirm: 'Mark noop',
  }, (reason) => {
    if (!reason) {
      toast('A reason is needed', 'bad');
      return;
    }
    submit([decisions.markNoop(ctx, id, reason)]);
  });
}

function unmarkNoop(row, id) {
  openConfirm({
    title: 'Unmark noop',
    lines: [rowLine(row)],
    confirm: 'Unmark noop',
  }, () => submit([decisions.unmarkNoop(ctx, id, 'restored to a posting in blotter')]));
}

// The one-row summary line the menu and its dialogs show: date · amount · description.
function rowLine(row) {
  return `${date(row.date)} \u00b7 ${money(row.amount)} \u00b7 ${row.rawDescription || ''}`;
}

function targetSummary(row, targets) {
  return targets.length === 1 ? rowLine(row) : `${targets.length} selected rows`;
}

// A two-row confirm shows both lines when they are on the loaded page, else the short id.
function confirmedLines(ids) {
  return ids.map((id) => {
    const row = (status.rows || []).find((r) => r.externalId === id);
    return row ? rowLine(row) : shortId(id);
  });
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
