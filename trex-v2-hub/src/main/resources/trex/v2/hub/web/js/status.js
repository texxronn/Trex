// The status strip (V2-PROPOSAL.md §14): n, lag, table counts, review by kind, reconciliation and
// the three revisions, plus the acting-user switcher.

import { api } from './api.js';
import { el, clear } from './dom.js';
import { total } from './format.js';
import * as since from './since.js';

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
    if (review === 0) recordClear(status.n);
    host.append(
      item('n', status.n),
      item('lag', status.lagBytes + ' B', status.lagBytes > 0 ? 'bad' : 'good'),
      item('txn', status.counts.txn_current ?? 0),
      item('transfers', status.counts.transfer ?? 0),
      allClear(review, status.through),
      stale(status.stale),
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

/**
 * The finish line is the next visit's starting line (V2-QOL-IMPROVEMENTS-PLAN.md §5): whenever
 * the strip shows all clear, the browser marker moves to the head, and the last known month left
 * rides along so the next summary can say "left this month X (was Y)". The marker is captured by
 * since.js at load before this can overwrite it. Never stored anywhere else.
 */
function recordClear(n) {
  localStorage.setItem('trex.clearedAtN', String(n));
  const left = since.latestLeft();
  if (left !== null) localStorage.setItem('trex.clearedLeft', String(left));
}

/**
 * The statement-age nudge (V2-QOL-IMPROVEMENTS-PLAN.md §2): how many accounts are past the fetch cadence
 * their frontier implies. Quiet — muted — until one is more than twice its cadence, then amber; the
 * tooltip names each account's age and frontier, and a click opens Jobs at the fetch-frontier
 * table, which already suggests the date range. Nothing to fetch renders an empty span.
 */
function stale(rows) {
  if (!rows || !rows.length) return el('span');
  const amber = rows.some((s) => s.days > 2 * s.fetchEveryDays);
  const title = rows
    .map((s) => `${s.account} \u00b7 ${s.days} days (through ${s.frontier})`)
    .join('\n');
  return el('a', { href: '#jobs?frontier', class: amber ? 'warn' : 'muted', title },
    `\u29d7 ${rows.length} statement${rows.length === 1 ? '' : 's'} to fetch`);
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
