// Review mode (V2-PROPOSAL.md §10.1): the derived review queue, one decision away from clear.

import { api } from './api.js';
import { decisions } from './decisions.js';
import { el, clear, field, scroll } from './dom.js';
import { money, shortId } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let listHost;
let errorBar;
let kind = '';
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
  host.append(errorBar, el('div', { class: 'toolbar' },
    field('Kind', el('select', {
      onchange: (e) => { kind = e.target.value; load(); },
    }, ...KINDS.map((k) => el('option', { value: k, selected: k === kind }, k === '' ? 'all' : k))))),
    listHost);
}

async function load() {
  try {
    const rows = await api.review(kind || undefined);
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
  const head = el('tr', {}, el('th', {}, 'Kind'), el('th', {}, 'Date'), el('th', {}, 'Subject'),
    el('th', {}, 'Detail'), el('th', { class: 'amount' }, 'Stake'), el('th', {}, 'Opened'), el('th', {}));
  const body = rows.map((row) => {
    const canDismiss = row.kind !== 'INEFFECTIVE_DECISION';
    return el('tr', {},
      el('td', {}, el('span', { class: 'badge ' + row.kind }, KIND_LABEL[row.kind] || row.kind)),
      el('td', {}, row.date || ''),
      el('td', { class: 'desc', title: row.subject }, row.subjectDescription || shortId(row.subject)),
      memberCell(row),
      el('td', { class: 'amount' }, row.amountStake ? money(row.amountStake) : ''),
      el('td', { class: 'muted' }, (row.openedAt || '').slice(0, 10)),
      el('td', {}, canDismiss
        ? el('button', { type: 'button', class: 'ghost', onclick: () => dismiss(row) }, 'Dismiss')
        : el('span', { class: 'muted' }, 'revoke the decision')));
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
  try {
    await api.decisions(ctx.n, [decisions.dismiss(ctx, row.kind, [row.subject], 'dismissed in review')]);
    toast('Dismissed');
    await load();
  } catch (error) {
    reportError(error);
    await load();
  }
}
