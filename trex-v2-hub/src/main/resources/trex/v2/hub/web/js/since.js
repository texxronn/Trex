// "Since you last cleared" (V2-QOL-IMPROVEMENTS-PLAN.md §5): the all-clear is a finish line, and
// this is the next visit's starting line. The marker is per viewer and lives in the browser,
// never the log or the index.
//
// The marker is captured at module load — before boot() can call status.refresh(), which rewrites
// it whenever the strip shows all clear — so the line always speaks about the marker the visit
// opened with. One /api/since fetch per visit, cached; Expected and Blotter render from it.

import { api } from './api.js';
import { el } from './dom.js';
import { money } from './format.js';

const AT_KEY = 'trex.clearedAtN';
const LEFT_KEY = 'trex.clearedLeft';

const markerN = localStorage.getItem(AT_KEY);
const markerLeft = localStorage.getItem(LEFT_KEY);

let ctx = null;
let data = null;       // the one cached /api/since response for this visit
let dismissed = false; // module state only: a reload shows the line again

/**
 * Fetch the visit's one summary. Boot calls this before status.refresh(), so the all-clear write
 * of the marker has a fresh left value to store. No marker (a new device) means no fetch and no
 * line; a failed read means no line this visit, never a crash.
 */
export async function load(context) {
  ctx = context;
  if (data || markerN === null) return;
  try {
    data = await api.since(markerN, context.user);
  } catch {
    data = null;
  }
}

/** The last known month left, for status.js's all-clear marker write; null until the read answers. */
export function latestLeft() {
  return data && data.headroom ? data.headroom.left : null;
}

/**
 * The summary line, or null when there is no marker, no cached answer, the marker line is gone
 * from the journal, or the person has dismissed it. Expected renders it above the headroom,
 * Blotter above its toolbar; both read the same cached response.
 */
export function line() {
  if (!markerN || !data || !data.at || dismissed) return null;
  const node = el('div', { class: 'since' },
    el('span', { class: 'since-text' }, summary(data)),
    el('button', {
      type: 'button', class: 'ghost since-dismiss', title: 'dismiss until the next visit',
      onclick: () => {
        dismissed = true;
        node.remove();
      },
    }, '\u00d7'));
  return node;
}

/** The one line: the example in the plan, in order, and the calm answer when nothing happened. */
function summary(s) {
  const parts = [];
  if (s.rows > 0) {
    parts.push(`${s.rows} new row${s.rows === 1 ? '' : 's'}${accountsText(s.accounts)}`);
  }
  if (s.items > 0) {
    parts.push(`${s.items} new item${s.items === 1 ? '' : 's'}`);
  }
  const occurrences = occurrencesText(s.occurrences || []);
  if (occurrences) parts.push(occurrences);
  const decisions = decisionsText(s.decisions || []);
  if (decisions) parts.push(decisions);
  if (!parts.length) return `Nothing new since ${when(s.at)}.`;
  const left = leftText(s);
  if (left) parts.push(left);
  return `Since ${when(s.at)}: ${parts.join(' \u00b7 ')}`;
}

function accountsText(accounts) {
  const names = (accounts || []).map((a) => a.account).filter(Boolean);
  return names.length ? ` (${names.join(', ')})` : '';
}

/** "Netflix and NIB paid · Gym missed": names grouped by the direction the occurrence turned. */
function occurrencesText(rows) {
  const parts = [];
  const paid = namesOf(rows, 'occurred');
  const missed = namesOf(rows, 'missed');
  if (paid) parts.push(`${paid} paid`);
  if (missed) parts.push(`${missed} missed`);
  return parts.join(' \u00b7 ');
}

function namesOf(rows, status) {
  const names = rows.filter((r) => r.status === status)
    .map((r) => r.commitmentName || r.commitmentId);
  if (names.length === 2) return `${names[0]} and ${names[1]}`;
  return names.join(', ');
}

function decisionsText(rows) {
  const count = rows.reduce((sum, r) => sum + (r.count || 0), 0);
  if (!count) return '';
  const users = rows.map((r) => nameOfUser(r.user)).filter(Boolean);
  return `${count} decision${count === 1 ? '' : 's'}${users.length ? ` by ${users.join(', ')}` : ''}`;
}

function nameOfUser(id) {
  if (!id) return '';
  const user = ((ctx && ctx.refdata && ctx.refdata.users) || []).find((u) => u.id === id);
  return user ? user.name : id;
}

/**
 * "left this month −$120 (was −$95)". The parenthetical compares with the stored left; it is
 * omitted when the marker's month is not the current one (the figure means something else after
 * a month rollover) or when no left was stored.
 */
function leftText(s) {
  const h = s.headroom;
  if (!h) return '';
  let text = `left this month ${money(h.left)}`;
  const was = markerLeft === null ? NaN : Number(markerLeft);
  if (Number.isFinite(was) && monthOf(s.at) === String(h.month || '').slice(0, 7)) {
    text += ` (was ${money(was)})`;
  }
  return text;
}

/** "Tue 08:40" in the viewer's zone. */
function when(iso) {
  const date = new Date(iso);
  const day = date.toLocaleDateString(undefined, { weekday: 'short' });
  const time = date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  return `${day} ${time}`;
}

/** The marker's month in the viewer's zone, to compare with the headroom's calendar month. */
function monthOf(iso) {
  const date = new Date(iso);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}
