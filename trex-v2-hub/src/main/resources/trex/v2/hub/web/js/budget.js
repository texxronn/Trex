// Budget mode (V2-SPEC.md §9.2): the month's "left this month" figure, moved out of Expected. It is
// the same headroom the Expected view once carried — income, commitments, other spend and the date
// the statements reach, over the budget accounts — read from /api/expected so the calculation is
// unchanged and no endpoint is added. Only its home moved.

import { api } from './api.js';
import { el, clear } from './dom.js';
import { date, money } from './format.js';

let host;
let data = null;
let loadGeneration = 0; // supersedes an in-flight load when a newer one starts
let errorBar;

export function mount(container, context) {
  host = container;
  render();
  load();
  return { refresh: load };
}

async function load() {
  const generation = ++loadGeneration;
  try {
    // The month's headroom rides on /api/expected (§9.2); Budget adds no request of its own.
    data = await api.expected('month');
    if (generation !== loadGeneration) return; // superseded: a newer load owns the view
    errorBar.hidden = true;
  } catch (error) {
    if (generation !== loadGeneration) return;
    errorBar.textContent = error.message || 'failed to load the budget view';
    errorBar.hidden = false;
    return;
  }
  render();
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  host.append(errorBar);
  if (!data) {
    host.append(el('p', { class: 'muted' }, 'Loading…'));
    return;
  }
  host.append(headroom());
}

/**
 * "Left this month" (V2-REVIEW-FIXES-PLAN.md §10): one figure that answers "am I okay?", with the
 * arithmetic beside it and the date the statements reach, so a late statement reads as a late
 * statement and not as money.
 */
function headroom() {
  const h = data.headroom;
  if (!h) return el('div');
  const month = new Date(h.month + 'T00:00:00Z')
    .toLocaleString(undefined, { month: 'long', timeZone: 'UTC' });
  const figure = el('b', { class: h.left < 0 ? 'occ-missed' : 'tick' },
    money(h.left));
  const parts = [
    `in ${money(h.incomeIn)}` + (h.incomeDue ? ` + ${money(h.incomeDue)} due` : ''),
    `commitments ${money(h.committedPaid)}` + (h.committedDue ? ` + ${money(h.committedDue)} due` : ''),
    `other spend ${money(h.uncommittedSpend)}`,
  ];
  // The wallet's unrecorded cash (V2-CASH-PLAN.md §4.2): spend the bank movements imply but no
  // fact records. Signed by the backend — positive is spend, so it moves `left` like any outflow.
  if (h.unrecordedCash) parts.push(`unrecorded cash ${money(h.unrecordedCash)}`);
  if (h.movedOut || h.movedIn) {
    parts.push(`moved to your other accounts ${money(h.movedOut - h.movedIn)}`);
  }
  const notes = [];
  if (h.missed) notes.push(el('span', { class: 'occ-missed' }, `not seen yet: ${money(h.missed)}`));
  if (h.unpaired) {
    notes.push(el('span', { class: 'muted', title: 'transfer legs still waiting for their other side' },
      `unpaired transfers: ${money(h.unpaired)}`));
  }
  return el('section', { class: 'headroom' },
    el('div', {}, el('span', { class: 'muted' }, `Left in ${month} `), figure),
    el('div', { class: 'muted' }, parts.join(' · ')),
    el('div', { class: 'muted', title: 'budget accounts: ' + (h.accounts || []).join(', ') },
      h.through ? `through ${date(h.through)} — the statements say nothing after it` : 'no statements yet'),
    notes.length ? el('div', {}, ...notes.flatMap((n, i) => (i ? [' · ', n] : [n]))) : '');
}
