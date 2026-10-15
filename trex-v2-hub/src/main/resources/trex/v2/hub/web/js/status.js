// The status strip (V2-PROPOSAL.md §14): n, lag, table counts, review by kind, reconciliation and
// the three revisions, plus the acting-user switcher.

import { api } from './api.js';
import { el, clear } from './dom.js';
import { date, total } from './format.js';
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

/**
 * The header badge: TREX_ENV, the same label the sequencer stamps into the log envelope. Dev stays
 * muted; anything else is drawn in the warn colour so a Prod screen cannot be mistaken for Dev.
 */
function instance(env) {
  const badge = document.getElementById('instance');
  if (!badge) return;
  const value = env || '';
  badge.textContent = value;
  badge.hidden = !value;
  badge.classList.toggle('prod', value !== '' && !/^dev/i.test(value));
  document.title = value ? `trex \u00b7 ${value}` : 'trex';
}

export async function refresh() {
  if (!host) return;
  try {
    const status = await api.status();
    instance(status.env);
    clear(host);
    const review = total(status.reviewByKind);
    if (review === 0) recordClear(status.n);
    host.append(
      item('n', status.n),
      item('lag', status.lagBytes + ' B', status.lagBytes > 0 ? 'bad' : 'good'),
      item('txn', status.counts.txn_current ?? 0),
      item('transfers', status.counts.transfer ?? 0),
      manualToPayNudge(status.manualToPay),
      allClear(review, status.through),
      expectedNudge(status.expectedDueThisWeek),
      stale(status.stale),
      item('pending', status.counts.pending ?? 0),
      revisions(status),
      userSelect(),
    );
  } catch {
    // transient: the next delta retries
  }
}

/** Manual payments are the only nudge that asks for an action with a deadline (§9.1). */
function manualToPayNudge(counts) {
  const upcoming = Number((counts && counts.upcoming) || 0);
  const overdue = Number((counts && counts.overdue) || 0);
  const count = upcoming + overdue;
  if (!count) return el('span');
  return el('a', {
    href: '#expected?section=to-pay',
    class: overdue ? 'bad' : 'warn',
    title: 'Manual payments due within their reminder window, or unpaid in the last 30 days. '
      + 'Mark paid once you have paid; the transaction lands with the next statement.',
  }, `💳 ${count} to pay${overdue ? ` · ${overdue} overdue` : ''}`);
}

/**
 * The daily finish line (V2-REVIEW-FIXES-PLAN.md §11): a count while anything is open, and once the
 * queue is empty a plain "Review clear", with the date the statements reach so it never claims
 * more. The wording names Review specifically (V2-SPEC.md §9.1) — a clean review queue is not the
 * same as nothing due, so a zero expectations nudge next to it cannot read as "nothing due".
 */
function allClear(open, through) {
  if (open > 0) {
    return el('a', { href: '#review', title: 'open review items' }, item('review', open, 'bad'));
  }
  return el('span', { class: 'tick', title: 'nothing open in review' },
    '\u2713 Review clear' + (through ? ` \u2014 through ${date(through)}` : ''));
}

/**
 * The expectations nudge (V2-SPEC.md §9.1): declared commitments whose occurrence window is still
 * open somewhere in this ISO week. Muted, after the review count/all-clear and before the
 * statement nudge; a zero count renders nothing, so the strip stays quiet when nothing is due. A
 * click opens Expected on the week window.
 */
function expectedNudge(count) {
  if (!count) return el('span');
  return el('a', { href: '#expected?window=week', class: 'muted',
    title: 'Declared commitments with an open occurrence window this ISO week. '
      + 'Missed expectations are in Review; awaiting-statement occurrences are excluded.' },
    `${count} expectation${count === 1 ? '' : 's'} due this week`);
}

/**
 * The finish line is the next visit's starting line (V2-QOL-IMPROVEMENTS-PLAN.md §5): when the
 * strip shows "Review clear", the browser marker may move to the head, with the last known month left
 * beside it so the next summary can say "left this month X (was Y)". The marker is captured by
 * since.js at load before this can overwrite it, and it moves only when doing so is safe: a
 * failed or still-pending summary read leaves it where it was, so an all-clear can never bury
 * news the read did not show — the next visit retries. Never stored anywhere else.
 */
function recordClear(n) {
  if (!since.mayAdvance()) return;
  localStorage.setItem('trex.clearedAtN', String(n));
  // The left only ever comes from a successful read's headroom; a failure must not null it.
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
    .map((s) => `${s.account} \u00b7 ${s.days} days (through ${date(s.frontier)})`)
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
