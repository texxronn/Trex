// Chains mode (V2-PROPOSAL.md §6.9, §10.5): the balance check per account, the forks it does not
// close on, and a per-side preview of marking a side noop. Nothing here is stored; the preview is
// recomputed from the facts, and marking a side writes a MARK_NOOP decision.

import { api } from './api.js';
import { accountChip } from './account.js';
import { decisions } from './decisions.js';
import { el, clear, field } from './dom.js';
import { money, shortId } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let body;
let account = '';

export function mount(container, context) {
  host = container;
  ctx = context;
  account = (context.modeQuery && context.modeQuery.get('account')) || '';
  render();
  load();
  return { refresh: load };
}

function render() {
  clear(host);
  const select = el('select', {
    onchange: (e) => { account = e.target.value; load(); },
  },
    el('option', { value: '' }, '\u2014 all accounts \u2014'),
    ...ctx.refdata.accounts.map((a) => el('option', { value: a.ref, selected: a.ref === account }, a.ref)));
  body = el('div');
  host.append(
    el('div', { class: 'toolbar' }, field('Account', select),
      button('Clear filter', clearFilter)),
    el('p', { class: 'hint muted' },
      'The balance check runs over transaction rows only. A fork is a value two rows claim; '
      + 'nooping a side is previewed before it is written.'),
    body);
}

function clearFilter() {
  account = '';
  render();
  load();
}

async function load() {
  clear(body);
  try {
    const data = await api.chains(account);
    if (!data.accounts.length) {
      body.append(el('p', { class: 'muted' }, 'No accounts to check.'));
      return;
    }
    for (const acct of data.accounts) {
      body.append(renderAccount(acct));
    }
  } catch (error) {
    reportError(error);
  }
}

function renderAccount(acct) {
  const previews = new Map(acct.previews.map((p) => [p.externalId, p]));
  const section = el('div', { class: 'mode-section' },
    el('h3', {},
      accountChip(ctx.refdata, acct.accountRef), ' ',
      el('span', { class: 'badge ' + (acct.reconciled ? 'good' : 'AMBIGUOUS_TRANSFER') }, acct.status)),
    el('div', { class: 'ops' },
      chip('opening', money(acct.opening)),
      chip('closing', money(acct.closing)),
      chip('sum', money(acct.sum)),
      chip('gap', money(acct.gap)),
      chip('excluded', String(acct.exclusions.length))));

  if (acct.exclusions.length) {
    section.append(el('p', { class: 'hint muted' },
      'Excluded noop rows: ' + acct.exclusions.map(shortId).join(', ')));
  }
  for (const fork of acct.forks) {
    const sides = fork.externalIds.map((id) => {
      const preview = previews.get(id);
      let note = '';
      if (preview) {
        note = preview.reconciled
          ? ` noop \u2192 reconciled at ${money(preview.closing)}`
          : ` noop \u2192 ${preview.remainingForks.length} fork(s) remain`;
      }
      return el('div', { class: 'fork-side' },
        el('span', { class: 'mono', title: id }, shortId(id)),
        el('span', { class: 'muted' }, note),
        button('Mark noop', () => markNoop(id, acct.accountRef)));
    });
    section.append(el('div', { class: 'fork' },
      el('div', { class: 'fork-value' }, `${fork.side} ${money(fork.value)}`),
      ...sides));
  }
  if (!acct.forks.length && !acct.reconciled) {
    section.append(el('p', { class: 'hint' },
      'Broken with no fork to name: a disjoint chain (an opening or closing with no double-claim).'));
  }
  return section;
}

function markNoop(id, accountRef) {
  const reason = window.prompt(`Why is ${shortId(id)} not a posting of ${accountRef}?`,
    'reference line, not a posting');
  if (!reason) return;
  submit([decisions.markNoop(ctx, id, reason)]);
}

async function submit(list) {
  try {
    await api.decisions(ctx.n, list);
    toast('Recorded');
    await load();
  } catch (error) {
    reportError(error);
    await load();
  }
}

function chip(label, value) {
  return el('span', { class: 'ops-chip' }, el('span', { class: 'muted' }, label + ' '), el('b', {}, value));
}

function button(label, onClick) {
  return el('button', { type: 'button', onclick: onClick, class: 'ghost' }, label);
}
