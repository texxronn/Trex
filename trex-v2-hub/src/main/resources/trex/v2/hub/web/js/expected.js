// Expected mode (V2-COMMITMENTS-PLAN.md §2.8, V2-PROPOSAL.md §10.6): the declared-expectations
// checklist. Only declared commitments' occurrences are shown — a detected candidate is a
// discovery, not an asserted expectation (§6.5) — rendered honestly: occurred carries a fact,
// settled is a person's conclusion, due, partial and missed are the forward states. Each amount is
// an unsigned magnitude with an explicit in/out direction. The catch-up backlog is the arrears
// list, oldest first, the registry with its curation actions, and the rule lint. Headroom moved to
// the Cash flow mode (V2-SPEC.md §9.2).
//
// The four sections fold (V2-EXPECTED-UX-PLAN.md §4): the summary is the section header and
// carries its live figures, so a collapsed section still speaks. The daily content — occurrences
// and arrears — defaults open; the registry and lint default closed. The state is a device
// preference, like the window.

import { api } from './api.js';
import { openCommitmentActions, openDeclare, openSettle } from './commitment.js';
import { direction } from './direction.js';
import { el, clear, field, scroll } from './dom.js';
import { date, money, shortId } from './format.js';
import * as since from './since.js';

const WINDOWS = { today: 'Today', week: 'This week', month: 'This month' };

let host;
let ctx;
let win = localStorage.getItem('trex.expected.window') || 'month';
let data = null;       // the /api/expected response
let registry = [];     // /api/commitments
let stale = [];        // /api/status stale accounts, oldest first (V2-QOL-IMPROVEMENTS-PLAN.md §2)
let serverToday = null; // /api/status today: the hub's own day, so "N days left" never guesses (§9.1)
let loadGeneration = 0; // supersedes an in-flight load when a newer one starts
let errorBar;
let scrollToPay = false;

// The four foldable sections (§4); the open state is a device preference, like the window.
const SECTIONS_KEY = 'trex.expected.sections';
const SECTION_DEFAULTS = { occurrences: true, arrears: true, registry: false, lint: false };
const openSections = loadSections();

function loadSections() {
  try {
    return { ...SECTION_DEFAULTS, ...JSON.parse(localStorage.getItem(SECTIONS_KEY) || '{}') };
  } catch {
    return { ...SECTION_DEFAULTS };
  }
}

export function mount(container, context) {
  host = container;
  ctx = context;
  // A #expected?window=week deep link (the status nudge's click target) overrides the stored
  // device preference for this visit, like the other modes' query params.
  const requested = context.modeQuery && context.modeQuery.get('window');
  if (requested && WINDOWS[requested]) win = requested;
  scrollToPay = !!(context.modeQuery && context.modeQuery.get('section') === 'to-pay');
  if (scrollToPay) openSections.occurrences = true;
  if (!WINDOWS[win]) win = 'month';
  render();
  load();
  return { refresh: load };
}

async function load() {
  const generation = ++loadGeneration;
  try {
    // The status read fails open: the nudge is a nicety, not a reason to blank the whole view.
    const [expected, commitments, status] = await Promise.all([
      api.expected(win), api.commitments(), api.status().catch(() => null)]);
    if (generation !== loadGeneration) return; // superseded: a newer load owns the view
    data = expected;
    registry = commitments.map((commitment) => ({
      ...commitment,
      // The registry API carries payment but not remindDays; toPay exposes the effective value
      // for commitments currently in their reminder window.
      remindDays: (expected.toPay || []).find((row) => row.commitmentId === commitment.commitmentId)
        ?.remindDays ?? commitment.remindDays,
    }));
    stale = (status && status.stale) || [];
    serverToday = (status && status.today) || null;
    errorBar.hidden = true;
  } catch (error) {
    if (generation !== loadGeneration) return;
    errorBar.textContent = error.message || 'failed to load the expected view';
    errorBar.hidden = false;
    return;
  }
  render();
  if (scrollToPay) {
    scrollToPay = false;
    requestAnimationFrame(() => document.getElementById('manual-payments')
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  host.append(errorBar, el('div', { class: 'toolbar' }, tabs()));
  // The since-clear line sits above the occurrences (QOL §5), from the visit's cached read. It
  // drops the "left this month" text — that figure lives in Cash flow now; the Blotter keeps it.
  const notice = since.line({ left: false });
  if (notice) host.append(notice);
  if (!data) {
    host.append(el('p', { class: 'muted' }, 'Loading…'));
    return;
  }
  host.append(occurrences(), catchUp(), registrySection(), lint());
}

function tabs() {
  return el('div', { class: 'tabs' }, ...Object.entries(WINDOWS).map(([value, label]) =>
    el('button', {
      type: 'button',
      class: 'tab' + (value === win ? ' active' : ''),
      onclick: () => {
        if (value === win) return;
        win = value;
        localStorage.setItem('trex.expected.window', win);
        load();
      },
    }, label)));
}

// ---- the window's occurrences -----------------------------------------------------------------

function occurrences() {
  // Declared commitments only: a detected candidate is a discovery, never an asserted expectation
  // (§6.5), so its history is not part of the checklist. The registry is already loaded.
  const declared = new Set(registry.filter((c) => c.origin === 'declared')
    .map((c) => c.commitmentId));
  const rows = (data.occurrences || []).filter((o) => declared.has(o.commitmentId));
  const head = ['Occurrences',
    el('span', { class: 'muted' }, ` · ${WINDOWS[data.window] || data.window} · ${rows.length}`),
    (data.toPay || []).length
      ? el('span', { class: 'occ-partial' }, ` · ${(data.toPay || []).length} to pay`) : null];
  const body = [manualPayments(), rows.length
    ? occurrencePanels(rows)
    : el('p', { class: 'muted' }, 'Nothing due in this window.')];
  return fold('occurrences', head, body);
}

/**
 * The window's occurrences as two panels — IN on the left, OUT on the right — carrying the same
 * columns. Either side may be empty. The panels are flex siblings, so `align-items: stretch` gives
 * the shorter one the taller one's height and the pair reads as a single table.
 */
function occurrencePanels(rows) {
  return el('div', { class: 'occ-split' },
    occurrencePanel('in', rows.filter((o) => o.direction === 'in')),
    occurrencePanel('out', rows.filter((o) => o.direction === 'out')));
}

/** The magnitude the Amount column shows for one occurrence: what moved, else the current price. */
function occurrenceAmount(o) {
  if (o.amount != null) return Math.abs(o.amount);
  const current = currentOf(o.commitmentId);
  return current == null ? 0 : Math.abs(current);
}

/**
 * One panel: its header carries the side, how many rows it holds, and their summed Amount column —
 * so the figure sits with the rows it totals and needs no separate status line. A row's figure is
 * what it carries once a fact landed, else the commitment's current price, exactly what the row
 * shows, so the header never disagrees with the table under it.
 */
function occurrencePanel(dir, rows) {
  const total = rows.reduce((sum, o) => sum + occurrenceAmount(o), 0);
  const body = rows.length
    ? rows.map(occurrenceRow)
    : [el('tr', {}, el('td', { colspan: 6, class: 'empty' }, `Nothing ${dir}.`))];
  return el('section', { class: `occ-panel occ-panel-${dir}` },
    el('h4', { class: 'occ-panel-head' }, dir.toUpperCase(),
      el('span', { class: 'muted' }, ` \u00b7 ${rows.length} \u00b7 `),
      el('b', {}, money(total))),
    scroll(el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'Date'), el('th', {}, 'Commitment'), el('th', {}, 'Direction'),
        el('th', { class: 'amount' }, 'Amount'), el('th', {}, 'Status'),
        el('th', {}, 'Matched'))),
      el('tbody', {}, ...body))));
}

/** Window-independent actions, exactly the rows counted in the status strip (§9.1). */
function manualPayments() {
  const rows = data.toPay || [];
  if (!rows.length) return null;
  return el('section', { id: 'manual-payments', class: 'manual-payments' },
    el('h4', {}, '💳 Manual payments'),
    scroll(el('table', {},
      el('thead', {}, el('tr', {},
        el('th', {}, 'Commitment'), el('th', {}, 'Due'), el('th', {}, 'When'),
        el('th', { class: 'amount' }, 'Expected amount'), el('th', {}, 'Account(s)'), el('th', {}, ''))),
      el('tbody', {}, ...rows.map(toPayRow)))));
}

function toPayRow(o) {
  const commitment = currentCommitment(o.commitmentId);
  const due = daysUntil(o.dueDate);
  const expected = o.amount != null ? Math.abs(o.amount)
    : Math.abs((commitment && commitment.currentAmount) || 0);
  const accounts = [...new Set(((commitment && commitment.rules) || [])
    .map((rule) => rule.account || 'Any account'))];
  const days = due < 0 ? el('span', { class: 'occ-missed' },
    `${-due} day${due === -1 ? '' : 's'} overdue`)
    : due === 0 ? 'Due today' : `${due} day${due === 1 ? '' : 's'} left`;
  return el('tr', {},
    el('td', {}, o.commitmentName || shortId(o.commitmentId)),
    el('td', {}, date(o.dueDate)),
    el('td', {}, days),
    el('td', { class: 'amount' }, money(expected)),
    el('td', { class: 'muted' }, accounts.join(', ') || 'Any account'),
    el('td', {}, el('button', { type: 'button', class: 'ghost',
      onclick: () => markManualPaid(o) }, 'Mark paid')));
}

function markManualPaid(o) {
  openSettle(ctx, {
    commitmentId: o.commitmentId,
    name: o.commitmentName || o.commitmentId,
    dueDates: [o.dueDate],
  }, load);
}

function currentCommitment(commitmentId) {
  return registry.find((c) => c.commitmentId === commitmentId);
}

/**
 * Whole days from the hub's own calendar day to a due date. The hub publishes that day as
 * `status.today`, so the figure matches the bucket the server assigned (a browser-clock day could
 * fall the other side of midnight and label an `upcoming` row "overdue"). Falls back to the local
 * day only if the status read failed.
 */
function daysUntil(dueDate) {
  let today = serverToday;
  if (!today) {
    const now = new Date();
    today = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())).toISOString().slice(0, 10);
  }
  const due = new Date(`${dueDate}T00:00:00Z`);
  return Math.round((due - new Date(`${today}T00:00:00Z`)) / 86400000);
}

function occurrenceRow(o) {
  const current = currentOf(o.commitmentId);
  const sign = o.amount != null ? o.amount : (o.direction === 'in' ? 1 : -1);
  return el('tr', {},
    el('td', {}, date(o.dueDate)),
    el('td', {}, o.commitmentName || shortId(o.commitmentId),
      o.payment === 'manual' ? el('span', { class: 'manual-pay-marker', title: 'Manual payment' }, ' 💳') : null),
    el('td', {}, direction(sign)),
    el('td', { class: 'amount' }, o.amount != null
      ? money(Math.abs(o.amount))
      : (current != null ? el('span', { class: 'muted', title: 'current price' }, money(Math.abs(current))) : '')),
    statusCell(o),
    matchedCell(o));
}

/** The six occurrence states, each rendered as what it is: a fact, a conclusion, or a gap. */
function statusCell(o) {
  switch (o.status) {
    case 'occurred':
      return el('td', {}, el('span', { class: 'tick', title: 'a fact landed' }, '\u2713 occurred'));
    case 'settled':
      return el('td', {}, el('span', { class: 'occ-settled',
        title: 'marked paid by a person — a conclusion, not a fact (SETTLE_OCCURRENCE)' }, '\u25c6 paid by hand'));
    case 'due':
      return el('td', {}, el('span', { class: 'muted', title: 'window open' }, 'due'));
    case 'partial':
      return el('td', {}, el('span', { class: 'occ-partial',
        title: 'part covered; the rest is in arrears' }, '\u25d0 partial'));
    case 'missed':
      return el('td', {}, el('span', { class: 'occ-missed',
        title: 'window closed with no match' }, '\u2717 missed'));
    case 'awaiting':
      return el('td', {}, el('span', { class: 'muted', title: awaitingTitle(o) },
        '\u29d7 awaiting statement'));
    default:
      return el('td', {}, el('span', { class: 'muted' }, o.status || ''));
  }
}

/**
 * The awaiting tooltip (V2-QOL-IMPROVEMENTS-PLAN.md §2): the matcher escalates to awaiting on the newest
 * frontier over the commitment's rule accounts, so one account can be named honestly only when the
 * rules resolve to exactly one distinct account — repeated refs are one account. With several,
 * which statement closed the window is a guess, so the tooltip keeps the generic fetch-it hint, as
 * it does for unscoped rules, an unknown commitment, or a single account that is not stale.
 */
function awaitingTitle(o) {
  const commitment = registry.find((c) => c.commitmentId === o.commitmentId);
  const accounts = new Set(((commitment && commitment.rules) || []).map((r) => r.account));
  if (accounts.size === 1 && !accounts.has(null)) {
    const [account] = accounts;
    const hit = stale.find((s) => s.account === account);
    if (hit) return `waiting for the ${account} statement — ${hit.days} days old`;
  }
  return 'window closed, but the statement has not reached it yet — fetch it (Jobs)';
}

function matchedCell(o) {
  if (!o.matchedExternalId) {
    return el('td', { class: 'muted' }, o.offSchedule ? 'off schedule' : '');
  }
  const via = o.matchedBy ? ` via ${o.matchedBy}` : '';
  return el('td', { class: 'muted', title: o.matchedExternalId + via },
    shortId(o.matchedExternalId) + (o.matchedDate ? ` · ${date(o.matchedDate)}` : '')
    + (o.offSchedule ? ' · off schedule' : ''));
}

// ---- catch up ---------------------------------------------------------------------------------

function catchUp() {
  const arrears = data.arrears || [];
  if (!arrears.length) {
    return fold('arrears', ['Catch up', el('span', { class: 'muted' }, ' · nothing in arrears')], []);
  }
  // The running dollar total is gone (V2-SPEC.md §9.3): the backlog is a list of holes, each with
  // its own expected and shortfall, not a cumulative figure to chase.
  const head = ['Catch up',
    el('span', { class: 'occ-partial' }, ` · ${arrears.length} behind`)];
  return fold('arrears', head, [
    el('p', { class: 'muted hint' },
      'Oldest first. Mark paid concludes a hole was paid without a fact; Assign a payment takes you '
      + 'to the Blotter to place the fact itself.'),
    scroll(el('table', {}, el('thead', {}, el('tr', {},
      el('th', {}, 'Due'), el('th', {}, 'Commitment'), el('th', {}, 'Status'),
      el('th', { class: 'amount' }, 'Expected'), el('th', { class: 'amount' }, 'Shortfall'),
      el('th', {}, ''))),
      el('tbody', {}, ...arrears.map(arrearRow)))),
  ]);
}

function arrearRow(a) {
  return el('tr', {},
    el('td', {}, date(a.dueDate)),
    el('td', {}, a.commitmentName || shortId(a.commitmentId)),
    el('td', {}, el('span', { class: a.status === 'missed' ? 'occ-missed' : 'occ-partial' },
      a.status)),
    el('td', { class: 'amount' }, money(a.expected)),
    el('td', { class: 'amount' }, money(a.shortfall)),
    el('td', {},
      el('button', { type: 'button', class: 'ghost',
        onclick: () => settleCommitment(a) }, 'Mark paid'),
      el('button', { type: 'button', class: 'ghost',
        onclick: () => { location.hash = '#blotter'; } }, 'Assign a payment')));
}

/** Settle the whole backlog of the commitment the row belongs to: the same gesture as Review. */
function settleCommitment(a) {
  const dueDates = arrearsFor(a.commitmentId);
  openSettle(ctx, { commitmentId: a.commitmentId, name: a.commitmentName || a.name, dueDates }, load);
}

function arrearsFor(commitmentId) {
  return [...new Set((data.arrears || [])
    .filter((x) => x.commitmentId === commitmentId)
    .map((x) => x.dueDate))];
}

// ---- the registry -----------------------------------------------------------------------------

// The registry's controls live in module state so an SSE re-render (which rebuilds the DOM) keeps
// them, and a filter change re-renders only the list and the header count — typing in the search
// box never loses focus. The status filter starts on `active`: a live list of what is still running,
// with dormant and ended rows one click away (operator, 2026-10-15).
const filters = { origin: 'all', status: 'active', direction: '', q: '', sort: 'last' };
let registryListHost;
let registryCountSpan;
let registryRows = [];

function registrySection() {
  const head = ['Commitments', registryCountSpan = el('span', { class: 'muted' })];
  const details = fold('registry', head, [
    registryFilters(),
    el('div', { class: 'section-actions' },
      el('button', { type: 'button', class: 'ghost',
        onclick: () => openDeclare(ctx, {
          summary: 'A commitment declared by hand: a manual-cadence bill, an income, anything '
            + 'detection did not propose.',
        }, load) }, 'Declare commitment')),
    registryListHost = el('div'),
  ]);
  refreshRegistry();
  return details;
}

function registryFilters() {
  return el('div', { class: 'toolbar' },
    originTabs(),
    field('Status', filterSelect([['', 'all'], ['active', 'active'], ['dormant', 'dormant'],
      ['ended', 'ended']], filters.status, (v) => { filters.status = v; refreshRegistry(); })),
    field('Direction', filterSelect([['', 'all'], ['in', 'in'], ['out', 'out']], filters.direction,
      (v) => { filters.direction = v; refreshRegistry(); })),
    field('Sort', filterSelect([['last', 'last seen'], ['stem', 'name'], ['amount', 'amount']],
      filters.sort, (v) => { filters.sort = v; refreshRegistry(); })),
    field('Text', el('input', {
      type: 'search', value: filters.q,
      oninput: (e) => { filters.q = e.target.value; refreshRegistry(); },
    })),
    el('button', { type: 'button', class: 'ghost', onclick: () => {
      filters.origin = 'all'; filters.status = ''; filters.direction = '';
      filters.q = ''; filters.sort = 'last';
      render(); // rebuild the controls so the cleared values show
    } }, 'Clear filters'));
}

// ---- the registry list ------------------------------------------------------------------------

function refreshRegistry() {
  registryRows = visibleRegistry();
  updateRegistrySummary();
  clear(registryListHost);
  if (!registryRows.length) {
    registryListHost.append(el('p', { class: 'muted' }, 'No commitments match.'));
    return;
  }
  registryListHost.append(scroll(el('table', {},
    el('thead', {}, registryColumns()),
    el('tbody', {}, ...registryRows.map(registryRow)))));
}

function visibleRegistry() {
  const q = filters.q.trim().toLowerCase();
  const rows = registry.filter((c) => {
    if (filters.origin !== 'all' && c.origin !== filters.origin) return false;
    if (filters.status && c.status !== filters.status) return false;
    if (filters.direction && c.direction !== filters.direction) return false;
    if (q && !(labelOf(c) + ' ' + c.commitmentId).toLowerCase().includes(q)) return false;
    return true;
  });
  rows.sort((a, b) => {
    if (filters.sort === 'stem') return labelOf(a).localeCompare(labelOf(b));
    if (filters.sort === 'amount') return Math.abs(b.currentAmount || 0) - Math.abs(a.currentAmount || 0);
    return (b.lastDate || '').localeCompare(a.lastDate || '');
  });
  return rows;
}

function updateRegistrySummary() {
  const total = registry.length;
  const shown = registryRows.length;
  const candidates = registry.filter((c) => c.origin === 'detected').length;
  registryCountSpan.textContent = shown !== total
    ? ` · ${shown} of ${total}`
    : !candidates ? ` · ${total}`
      : candidates === total ? ` · ${total} candidate${total === 1 ? '' : 's'}`
        : ` · ${total} · ${candidates} candidate${candidates === 1 ? '' : 's'}`;
}

function registryColumns() {
  return el('tr', {},
    el('th', {}, 'Commitment'), el('th', {}, 'Origin'), el('th', {}, 'Direction'),
    el('th', {}, 'Cadence'), el('th', {}, 'Kind'), el('th', {}, 'Status'),
    el('th', { class: 'amount' }, 'Current'), el('th', {}, 'Last'), el('th', {}, 'Next'),
    el('th', { class: 'amount' }, 'Arrears'), el('th', {}, ''));
}

function registryRow(c) {
  const declared = c.origin === 'declared';
  const arrears = c.arrearsCount
    ? `${c.arrearsCount} · ${money(Math.abs(c.arrearsAmount || 0))}` : '—';
  return el('tr', {},
    el('td', { class: 'desc' },
      el('span', { title: c.commitmentId }, labelOf(c)),
      c.payment === 'manual'
        ? el('span', { class: 'manual-pay-marker', title: 'Manual payment' }, ' 💳') : null,
      declared ? null : el('span', { class: 'muted' }, ' · candidate'),
      !declared && seriesLine(c) ? el('div', { class: 'muted series' }, seriesLine(c)) : null,
      c.notes && c.notes.length
        ? el('div', {}, ...c.notes.map((n) => el('span', { class: 'note-chip',
            title: `${n.user} · ${date(n.at)}` }, '\u270e ' + n.text)))
        : null),
    el('td', {}, el('span', { class: 'tag' }, c.origin)),
    el('td', {}, direction(c.currentAmount != null ? c.currentAmount
      : (c.direction === 'in' ? 1 : -1))),
    el('td', {}, c.cadence),
    el('td', {}, declared ? c.kind : el('span', { class: 'muted' }, '—')),
    el('td', {}, el('span', { class: 'tag ' + c.status }, c.status)),
    el('td', { class: 'amount' }, currentCell(c)),
    el('td', { class: 'muted' }, date(c.lastDate) || '—'),
    el('td', { class: 'muted' }, date(c.nextDue) || '—'),
    el('td', { class: 'amount' }, arrears),
    el('td', {}, ...actions(c)));
}

function labelOf(c) {
  return c.name || c.stem || shortId(c.commitmentId);
}

/** A candidate's evidence, from fields the registry already carries (V2-EXPECTED-UX-PLAN.md §7). */
function seriesLine(c) {
  const bits = [];
  if (c.occurrenceCount) bits.push(`${c.occurrenceCount}\u00d7`);
  if (c.firstDate) {
    bits.push(c.lastDate ? `${date(c.firstDate)} → ${date(c.lastDate)}` : date(c.firstDate));
  }
  if (c.regularity != null) bits.push(`regularity ${c.regularity.toFixed(2)}`);
  if (c.outliers) bits.push(`+${c.outliers} one-off${c.outliers === 1 ? '' : 's'}`);
  if (c.variable) bits.push('variable');
  return bits.join(' · ');
}

function currentCell(c) {
  if (c.currentAmount == null) {
    return el('span', { class: 'muted' }, '—');
  }
  const cell = el('span', {}, money(Math.abs(c.currentAmount)));
  if (c.previousAmount != null) {
    cell.append(el('span', { class: 'muted' }, ` from ${money(Math.abs(c.previousAmount))}`));
  }
  if (c.changePct != null) {
    const sign = c.changePct > 0 ? '+' : '';
    cell.append(el('span', { class: 'muted' },
      ` ${sign}${c.changePct.toFixed(1)}%${c.changeDate ? ` since ${date(c.changeDate)}` : ''}`));
  }
  return cell;
}

/** Every row opens the same menu; its actions follow the row's origin. */
function actions(c) {
  return [el('button', { type: 'button', class: 'ghost',
    onclick: () => openCommitmentActions(ctx, c, load) }, 'Actions…')];
}

// ---- lint -------------------------------------------------------------------------------------

function lint() {
  const byMatch = new Map();
  for (const c of registry) {
    for (const r of c.rules || []) {
      if (!byMatch.has(r.match)) byMatch.set(r.match, []);
      byMatch.get(r.match).push(c);
    }
  }
  const overlaps = [...byMatch.entries()].filter(([, commitments]) => commitments.length > 1);
  const warned = overlaps.map(([match, commitments]) => el('div', { class: 'lint-warn' },
    el('code', {}, match), ' is on ',
    commitments.map((c) => c.name || c.commitmentId).join(' and '),
    ' — the latest declaration wins; split or retire one.'));
  const ruleList = registry.filter((c) => (c.rules || []).length);
  const head = ['Lint', el('span', { class: 'muted' },
    overlaps.length
      ? ` · ${overlaps.length} overlap${overlaps.length === 1 ? '' : 's'}`
      : ' · no overlaps')];
  return fold('lint', head, [
    el('p', { class: 'muted hint' },
      'Overlaps are match rules shared by more than one commitment. Never-fired counts are '
      + 'computed from matches later; catastrophic regexes are refused by the hub and cannot '
      + 'reach the log.'),
    warned.length ? warned : el('p', { class: 'muted' }, 'No overlapping rules.'),
    ruleList.length
      ? el('div', {}, ...ruleList.map((c) => el('details', {},
          el('summary', {}, `${c.name || c.commitmentId} — ${c.rules.length} rule`
            + (c.rules.length === 1 ? '' : 's')),
          ...c.rules.map((r) => el('div', { class: 'rule-line' },
            el('span', { class: 'mono' }, r.account || '*'),
            el('code', { class: 'desc' }, r.match))))))
      : el('p', { class: 'muted' }, 'No declared rules yet.'),
  ]);
}

// ---- helpers ----------------------------------------------------------------------------------

/** One foldable section (§4): the summary is the header and carries the live figures. Several
 *  sections may be open at once; native <details> gives keyboard toggling for free. */
function fold(id, head, body) {
  const details = el('details', {
    class: 'mode-section x-section',
    open: openSections[id],
    ontoggle: () => {
      openSections[id] = details.open;
      localStorage.setItem(SECTIONS_KEY, JSON.stringify(openSections));
    },
  }, el('summary', {}, ...head), el('div', { class: 'x-body' }, ...body));
  return details;
}

/** A small select for the registry filters: [value, label] pairs (§7 Stage 2). */
function filterSelect(options, value, onChange) {
  return el('select', { onchange: (e) => onChange(e.target.value) },
    ...options.map(([v, label]) => el('option', { value: v, selected: v === value }, label)));
}

/**
 * The origin selector. A click repaints the tabs in place (a full re-render would jump the
 * scroll); the list and the header count refresh behind it.
 */
function originTabs() {
  const entries = [['all', 'All'], ['detected', 'Candidates'], ['declared', 'Declared']];
  const host = el('div', { class: 'tabs' });
  entries.forEach(([value, label]) => {
    host.append(el('button', {
      type: 'button',
      class: 'tab' + (filters.origin === value ? ' active' : ''),
      onclick: () => {
        filters.origin = value;
        [...host.children].forEach((button, i) =>
          button.classList.toggle('active', entries[i][0] === value));
        refreshRegistry();
      },
    }, label));
  });
  return host;
}

function currentOf(commitmentId) {
  const row = registry.find((c) => c.commitmentId === commitmentId);
  return row && row.currentAmount != null ? row.currentAmount : null;
}
