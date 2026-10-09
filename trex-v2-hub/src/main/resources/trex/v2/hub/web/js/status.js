// The status strip (V2-PROPOSAL.md §14): n, lag, table counts, review by kind, reconciliation and
// the three revisions, plus the acting-user switcher.

import { api } from './api.js';
import { el, clear } from './dom.js';
import { total } from './format.js';

let host = null;
let ctx = null;

export function mount(container, context) {
  host = container;
  ctx = context;
}

function item(label, value, cls = '') {
  return el('span', {}, `${label} `, el('b', { class: cls }, String(value)));
}

export async function refresh() {
  if (!host) return;
  try {
    const status = await api.status();
    clear(host);
    const review = total(status.reviewByKind);
    host.append(
      item('n', status.n),
      item('lag', status.lagBytes + ' B', status.lagBytes > 0 ? 'bad' : 'good'),
      item('txn', status.counts.txn_current ?? 0),
      item('transfers', status.counts.transfer ?? 0),
      allClear(review, status.through),
      item('pending', status.counts.pending ?? 0),
      revisions(status),
      userSelect(),
    );
  } catch {
    // transient: the next delta retries
  }
}

/**
 * The daily finish line (V2-REVIEW-FIXES-PLAN.md §11): a count while anything is open, and once the
 * queue is empty a plain "all clear", with the date the statements reach so it never claims more.
 */
function allClear(open, through) {
  if (open > 0) {
    return el('a', { href: '#review', title: 'open review items' }, item('review', open, 'bad'));
  }
  return el('span', { class: 'tick', title: 'nothing open in review' },
    '\u2713 all clear' + (through ? ` \u2014 through ${through}` : ''));
}

function revisions(status) {
  return el('span', { class: 'muted', title: status.configRevision },
    `config ${short(status.configRevision)} · ${status.deriveVersion} · ${status.hashVersion}`);
}

function short(revision) {
  if (!revision) return '—';
  return revision.replace('sha256:', '').slice(0, 8);
}

function userSelect() {
  const users = (ctx.refdata && ctx.refdata.users) || [];
  const select = el('select', {
    onchange: (event) => {
      ctx.user = event.target.value;
      localStorage.setItem('trex.user', ctx.user);
      if (ctx.onUserChange) ctx.onUserChange();
    },
  }, ...users.map((u) => el('option', { value: u.id, selected: u.id === ctx.user }, u.name + (u.active ? '' : ' (inactive)'))));
  return el('span', {}, 'acting as ', select);
}
