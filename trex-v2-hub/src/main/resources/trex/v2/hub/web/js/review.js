// Review mode (V2-PROPOSAL.md §10.1): the derived review queue, one decision away from clear.

import { api } from './api.js';
import { accountChip } from './account.js';
import { decisions } from './decisions.js';
import { openAnnotate } from './annotate.js';
import { direction } from './direction.js';
import { openPrompt, openSelect } from './dialog.js';
import { el, clear, field, scroll } from './dom.js';
import { money, shortId } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let listHost;
let errorBar;
let kind = '';
let account = '';
// Subjects the person has expanded; kept across re-renders so a refresh does not snap them shut.
const expanded = new Set();

const KINDS = ['', 'POTENTIAL_DUP', 'RESTATEMENT', 'AMBIGUOUS_TRANSFER', 'AMBIGUOUS_SETTLEMENT',
  'UNMATCHED_LEG', 'STALE_PENDING', 'INEFFECTIVE_DECISION'];

const KIND_LABEL = {
  POTENTIAL_DUP: 'possible duplicate',
  RESTATEMENT: 'restatement',
  AMBIGUOUS_TRANSFER: 'ambiguous transfer',
  AMBIGUOUS_SETTLEMENT: 'ambiguous settlement',
  UNMATCHED_LEG: 'unmatched leg',
  STALE_PENDING: 'stale pending',
  INEFFECTIVE_DECISION: 'ineffective decision',
};

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
  listHost = el('div');
  const accounts = ((ctx.refdata && ctx.refdata.accounts) || []).map((a) => a.ref).filter(Boolean);
  host.append(errorBar, el('div', { class: 'toolbar' },
    field('Kind', el('select', {
      onchange: (e) => { kind = e.target.value; load(); },
    }, ...KINDS.map((k) => el('option', { value: k, selected: k === kind }, k === '' ? 'all' : k)))),
    field('Account', el('select', {
      onchange: (e) => { account = e.target.value; load(); },
    }, el('option', { value: '', selected: account === '' }, 'all'),
      ...accounts.map((a) => el('option', { value: a, selected: a === account }, a))))),
    listHost);
}

async function load() {
  try {
    const rows = await api.review(kind || undefined, account || undefined);
    renderRows(rows);
    errorBar.hidden = true;
  } catch (error) {
    errorBar.textContent = error.message || 'failed to load review';
    errorBar.hidden = false;
  }
}

function renderRows(rows) {
  clear(listHost);
  if (!rows.length) {
    listHost.append(el('p', { class: 'muted' }, 'Nothing open.'));
    return;
  }
  const head = el('tr', {}, el('th', {}, 'Kind'), el('th', {}, 'Account'), el('th', {}, 'Date'),
    el('th', {}, 'Subject'), el('th', {}, 'Detail'), el('th', { class: 'amount' }, 'Stake'),
    el('th', {}, 'Direction'), el('th', {}, 'Opened'), el('th', {}));
  const body = rows.map((row) => {
    const canDismiss = row.kind !== 'INEFFECTIVE_DECISION';
    return el('tr', {},
      el('td', {}, el('span', { class: 'badge ' + row.kind }, KIND_LABEL[row.kind] || row.kind)),
      el('td', {}, accountChip(ctx.refdata, row.accountRef)),
      el('td', {}, row.date || ''),
      el('td', { class: 'desc', title: row.subject }, row.subjectDescription || shortId(row.subject)),
      memberCell(row),
      el('td', { class: 'amount' }, row.amountStake ? money(row.amountStake) : ''),
      el('td', {}, direction(row.amount)),
      el('td', { class: 'muted' }, (row.openedAt || '').slice(0, 10)),
      el('td', {},
        canDismiss
          ? el('button', { type: 'button', class: 'ghost', onclick: () => dismiss(row) }, 'Dismiss')
          : el('span', { class: 'muted' }, 'revoke the decision'),
        canAnnotate(row)
          ? el('button', { type: 'button', class: 'ghost', onclick: () => annotate(row) }, 'Note')
          : null,
        row.kind === 'UNMATCHED_LEG'
          ? el('button', { type: 'button', class: 'ghost', onclick: () => attach(row) }, 'Attach')
          : null));
  });
  listHost.append(scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body))));
}

// A POTENTIAL_DUP/RESTATEMENT item is a cluster: a collapsible header shows the summary, and
// expanding it lists every member with the fields that tell the rows apart — running balance,
// receipt, verbatim text — instead of a count no one can act on.
function memberCell(row) {
  if (!row.members || row.members.length < 2) {
    return el('td', { class: 'desc' }, row.detail);
  }
  const details = el('details', { class: 'cluster' },
    el('summary', {}, row.detail),
    el('div', { class: 'chips' }, ...row.members.map((m, i) => el('div', { class: 'chip t' + (i % 6) },
      el('span', { class: 'muted' }, '#' + m.n),
      el('span', { class: 'chip-amt' }, money(m.amount)),
      el('span', { class: 'muted' }, 'bal ' + money(m.balance)),
      m.receipt ? el('span', { class: 'muted' }, 'rcpt ' + m.receipt) : null,
      el('span', { class: 'chip-desc', title: m.rawDescription }, m.rawDescription)))));
  if (expanded.has(row.subject)) details.open = true;
  details.addEventListener('toggle', () => {
    if (details.open) expanded.add(row.subject);
    else expanded.delete(row.subject);
  });
  return el('td', { class: 'desc members' }, details);
}

async function dismiss(row) {
  openPrompt({
    title: 'Dismiss',
    summary: row.detail,
    label: 'Reason (optional)',
    placeholder: 'why this is not a problem',
    confirm: 'Dismiss',
  }, async (reason) => {
    try {
      await api.decisions(ctx.n, [decisions.dismiss(ctx, row.kind, [row.subject], reason || null)]);
      toast('Dismissed');
      await load();
    } catch (error) {
      reportError(error);
      await load();
    }
  });
}

// A cluster is annotated by fan-out: one NOTE per member id, one batch. Every other kind is a
// single row; INEFFECTIVE_DECISION (a decision n) and BALANCE_BREAK (an account ref) are not facts.
function canAnnotate(row) {
  return row.kind !== 'INEFFECTIVE_DECISION' && row.kind !== 'BALANCE_BREAK';
}

function annotate(row) {
  const ids = row.members && row.members.length ? row.members.map((m) => m.externalId) : [row.subject];
  openAnnotate(ctx, { ids, summary: row.detail }, load);
}

// An unmatched leg whose counterparty is gone: attach it to a clearing account (§6.10).
function attach(row) {
  const clearing = (ctx.refdata.accounts || [])
    .filter((a) => a.balanceSource === 'clearing')
    .map((a) => ({ value: a.ref, label: a.ref }));
  if (!clearing.length) {
    toast('No clearing accounts are configured', 'bad');
    return;
  }
  openSelect({
    title: 'Attach to a clearing account',
    summary: row.subjectDescription || row.subject,
    label: 'Account',
    choices: clearing,
    confirm: 'Attach',
  }, async (account) => {
    try {
      await api.decisions(ctx.n, [decisions.attachAccount(ctx, [row.subject], account,
        'counterparty statements not held')]);
      toast('Attached');
    } catch (error) {
      reportError(error);
    }
    await load();
  });
}
