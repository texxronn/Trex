// Review mode (V2-PROPOSAL.md §10.1): the derived review queue, one decision away from clear.

import { api } from './api.js';
import { decisions } from './decisions.js';
import { el, clear, field } from './dom.js';
import { money, shortId } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let listHost;
let errorBar;
let kind = '';

const KINDS = ['', 'POTENTIAL_DUP', 'RESTATEMENT', 'AMBIGUOUS_TRANSFER', 'AMBIGUOUS_SETTLEMENT',
  'UNMATCHED_LEG', 'STALE_PENDING', 'INEFFECTIVE_DECISION'];

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
  const head = el('tr', {}, el('th', {}, 'Kind'), el('th', {}, 'Subject'), el('th', {}, 'Detail'),
    el('th', { class: 'amount' }, 'Stake'), el('th', {}, 'Opened'), el('th', {}));
  const body = rows.map((row) => {
    const canDismiss = row.kind !== 'INEFFECTIVE_DECISION';
    return el('tr', {},
      el('td', {}, row.kind),
      el('td', { class: 'muted', title: row.subject }, shortId(row.subject)),
      el('td', { class: 'desc' }, row.detail),
      el('td', { class: 'amount' }, row.amountStake ? money(row.amountStake) : ''),
      el('td', { class: 'muted' }, row.openedAt),
      el('td', {}, canDismiss
        ? el('button', { type: 'button', onclick: () => dismiss(row) }, 'Dismiss')
        : el('span', { class: 'muted' }, 'revoke the decision')));
  });
  listHost.append(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body)));
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
