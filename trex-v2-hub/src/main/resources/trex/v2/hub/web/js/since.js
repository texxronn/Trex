// "Since you last cleared" (V2-QOL-IMPROVEMENTS-PLAN.md §5): the all-clear is a finish line, and
// this is the next visit's starting line. The marker is per viewer and lives in the browser,
// never the log or the index.
//
// The marker is captured at module load — before boot() can call status.refresh(), which rewrites
// it whenever the strip shows all clear — so the line always speaks about the marker the visit
// opened with. One /api/since fetch per visit, cached; Expected and Blotter render from it. A
// failed or malformed read never advances the marker (mayAdvance), so news it could not show
// stays unread and the next visit retries.

import { api } from './api.js';
import { el } from './dom.js';
import { money } from './format.js';

const AT_KEY = 'trex.clearedAtN';
const LEFT_KEY = 'trex.clearedLeft';

const markerN = localStorage.getItem(AT_KEY);
const markerLeft = localStorage.getItem(LEFT_KEY);

let ctx = null;
let data = null;       // the visit's validated /api/since response, when the read succeeded
let dismissed = false; // module state only: a reload shows the line again
// The visit's read outcome: 'none' (no marker to read), 'pending', 'ok', 'failed'. Only a
// success — or a marker set for the first time — may advance the stored marker: a failed read
// that advanced it would erase news it never showed.
let read = markerN === null ? 'none' : 'pending';

/**
 * Fetch the visit's one summary. Boot calls this before status.refresh(), so the all-clear write
 * of the marker has a fresh left value to store. No marker (a new device) means no fetch and no
 * line; a failed or malformed read means no line this visit, never a crash.
 */
export async function load(context) {
  ctx = context;
  if (read !== 'pending') return;
  try {
    const response = await api.since(markerN, context.user);
    if (wellFormed(response)) {
      data = response;
      read = 'ok';
    } else {
      read = 'failed';
    }
  } catch {
    read = 'failed';
  }
}

/**
 * Whether an all-clear may move the stored marker without erasing unread news: there was no
 * marker to read (setting one for the first time loses nothing), or this visit's read succeeded
 * — even with zero news. A pending or failed read leaves the marker where it was, so the next
 * visit retries.
 */
export function mayAdvance() {
  return read === 'none' || read === 'ok';
}

/** The successful read's month left, for status.js's marker write; null when there is none to trust. */
export function latestLeft() {
  if (read !== 'ok') return null;
  const headroom = headroomOf(data);
  return headroom ? headroom.left : null;
}

/**
 * The summary line, or null when there is no marker, no validated answer, the marker line is
 * gone from the journal, or the person has dismissed it. Expected renders it above the headroom,
 * Blotter above its toolbar; both read the same cached response. Every collection is normalised
 * below, so a payload with wrong-shaped rows renders nothing (or the calm line) instead of
 * throwing in either view.
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
  const rows = countOf(s.rows);
  const items = countOf(s.items);
  if (rows > 0) {
    parts.push(`${rows} new row${rows === 1 ? '' : 's'}${accountsText(s.accounts)}`);
  }
  if (items > 0) {
    parts.push(`${items} new item${items === 1 ? '' : 's'}`);
  }
  const occurrences = occurrencesText(s.occurrences);
  if (occurrences) parts.push(occurrences);
  const decisions = decisionsText(s.decisions);
  if (decisions) parts.push(decisions);
  if (!parts.length) return `Nothing new since ${when(s.at)}.`;
  const left = leftText(s);
  if (left) parts.push(left);
  return `Since ${when(s.at)}: ${parts.join(' \u00b7 ')}`;
}

function accountsText(accounts) {
  const names = arrayOf(accounts)
    .map((row) => (row && typeof row === 'object' ? row.account : null))
    .filter((name) => typeof name === 'string' && name);
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
  const names = arrayOf(rows)
    .filter((row) => row && typeof row === 'object' && row.status === status)
    .map((row) => row.commitmentName || row.commitmentId)
    .filter((name) => typeof name === 'string' && name);
  if (names.length === 2) return `${names[0]} and ${names[1]}`;
  return names.join(', ');
}

function decisionsText(rows) {
  const list = arrayOf(rows).filter((row) => row && typeof row === 'object'
    && countOf(row.count) > 0);
  const count = list.reduce((sum, row) => sum + row.count, 0);
  if (!count) return '';
  const users = list
    .map((row) => (typeof row.user === 'string' && row.user ? nameOfUser(row.user) : ''))
    .filter(Boolean);
  return `${count} decision${count === 1 ? '' : 's'}${users.length ? ` by ${users.join(', ')}` : ''}`;
}

function nameOfUser(id) {
  const user = ((ctx && ctx.refdata && ctx.refdata.users) || []).find((u) => u && u.id === id);
  return user && typeof user.name === 'string' ? user.name : id;
}

/**
 * "left this month −$120 (was −$95)". The parenthetical compares with the stored left; it is
 * omitted when the marker's month is not the current one (the figure means something else after
 * a month rollover) or when no left was stored.
 */
function leftText(s) {
  const headroom = headroomOf(s);
  if (!headroom) return '';
  let text = `left this month ${money(headroom.left)}`;
  const was = markerLeft === null ? NaN : Number(markerLeft);
  if (Number.isFinite(was) && monthOf(s.at) === String(headroom.month || '').slice(0, 7)) {
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

// ---- shape guards: nothing below is trusted until it fits what the line uses ------------------

/** The endpoint's answer, as much as a client may trust before calling anything "news". */
function wellFormed(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value)
    && markerTime(value.at) !== undefined
    && typeof value.rows === 'number' && typeof value.items === 'number'
    && Array.isArray(value.accounts) && Array.isArray(value.occurrences)
    && Array.isArray(value.decisions);
}

/** A marker time: null (the line is gone — a valid answer), a parsed date, or undefined (malformed). */
function markerTime(value) {
  if (value === null) return null;
  if (typeof value !== 'string') return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

/** The headroom object only when it carries the number the line uses. */
function headroomOf(s) {
  const headroom = s && s.headroom;
  return headroom && typeof headroom === 'object' && !Array.isArray(headroom)
    && typeof headroom.left === 'number' && Number.isFinite(headroom.left) ? headroom : null;
}

/** A positive finite count, or 0 — a wrong-shaped count is no news, never NaN in the line. */
function countOf(value) {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;
}

function arrayOf(value) {
  return Array.isArray(value) ? value : [];
}
