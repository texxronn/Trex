// Expected mode (V2-COMMITMENTS-PLAN.md §2.8, V2-PROPOSAL.md §10.6): what is committed and what
// is behind. The window's occurrences rendered honestly — occurred carries a fact, settled is a
// person's conclusion, due, partial and missed are the forward states — the committed totals, the
// catch-up backlog oldest first with a running total, the registry with its curation actions, and
// the rule lint.
//
// The four sections fold (V2-EXPECTED-UX-PLAN.md §4): the summary is the section header and
// carries its live figures, so a collapsed section still speaks. The daily content — occurrences
// and arrears — defaults open; the registry and lint default closed. The state is a device
// preference, like the window.

import { api } from './api.js';
import { openCommitmentActions, openDeclare, openSettle } from './commitment.js';
import { direction } from './direction.js';
import { el, clear, field, scroll } from './dom.js';
import { money, shortId } from './format.js';

const WINDOWS = { today: 'Today', week: 'This week', month: 'This month' };

let host;
let ctx;
let win = localStorage.getItem('trex.expected.window') || 'month';
let data = null;       // the /api/expected response
let registry = [];     // /api/commitments
let errorBar;

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
  if (!WINDOWS[win]) win = 'month';
  render();
  load();
  return { refresh: load };
}

async function load() {
  try {
    const [expected, commitments] = await Promise.all([api.expected(win), api.commitments()]);
    data = expected;
    registry = commitments;
    errorBar.hidden = true;
  } catch (error) {
    errorBar.textContent = error.message || 'failed to load the expected view';
    errorBar.hidden = false;
    return;
  }
  render();
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  host.append(errorBar, el('div', { class: 'toolbar' }, tabs(), summary()));
  if (!data) {
    host.append(el('p', { class: 'muted' }, 'Loading…'));
    return;
  }
  host.append(headroom(), occurrences(), catchUp(), registrySection(), lint());
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
      h.through ? `through ${h.through} — the statements say nothing after it` : 'no statements yet'),
    notes.length ? el('div', {}, ...notes.flatMap((n, i) => (i ? [' · ', n] : [n]))) : '');
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

function summary() {
  if (!data) return el('span', { class: 'muted' }, '');
  const totals = data.totals || { out: 0, in: 0 };
  return el('span', { class: 'muted' },
    'committed ', el('b', {}, money(totals.out)), ' out · ',
    el('span', { class: 'tick' }, money(totals.in) + ' in'));
}

// ---- the window's occurrences -----------------------------------------------------------------

function occurrences() {
  const rows = data.occurrences || [];
  const head = ['Occurrences',
    el('span', { class: 'muted' }, ` · ${WINDOWS[data.window] || data.window} · ${rows.length}`)];
  const body = rows.length
    ? [scroll(el('table', {}, el('thead', {}, el('tr', {},
        el('th', {}, 'Date'), el('th', {}, 'Commitment'), el('th', {}, 'Direction'),
        el('th', { class: 'amount' }, 'Amount'), el('th', {}, 'Status'),
        el('th', {}, 'Matched'))), el('tbody', {}, ...rows.map(occurrenceRow))))]
    : [el('p', { class: 'muted' }, 'Nothing due in this window.')];
  return fold('occurrences', head, body);
}

function occurrenceRow(o) {
  const current = currentOf(o.commitmentId);
  const sign = o.amount != null ? o.amount : (o.direction === 'in' ? 1 : -1);
  return el('tr', {},
    el('td', {}, o.dueDate),
    el('td', {}, o.commitmentName || shortId(o.commitmentId)),
    el('td', {}, direction(sign)),
    el('td', { class: 'amount' }, o.amount != null
      ? money(o.amount)
      : (current != null ? el('span', { class: 'muted', title: 'current price' }, money(current)) : '')),
    statusCell(o.status),
    matchedCell(o));
}

/** The six occurrence states, each rendered as what it is: a fact, a conclusion, or a gap. */
function statusCell(status) {
  switch (status) {
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
      return el('td', {}, el('span', { class: 'muted',
        title: 'window closed, but the statement has not reached it yet — fetch it (Jobs)' },
        '\u29d7 awaiting statement'));
    default:
      return el('td', {}, el('span', { class: 'muted' }, status || ''));
  }
}

function matchedCell(o) {
  if (!o.matchedExternalId) {
    return el('td', { class: 'muted' }, o.offSchedule ? 'off schedule' : '');
  }
  const via = o.matchedBy ? ` via ${o.matchedBy}` : '';
  return el('td', { class: 'muted', title: o.matchedExternalId + via },
    shortId(o.matchedExternalId) + (o.matchedDate ? ` · ${o.matchedDate}` : '')
    + (o.offSchedule ? ' · off schedule' : ''));
}

// ---- catch up ---------------------------------------------------------------------------------

function catchUp() {
  const arrears = data.arrears || [];
  if (!arrears.length) {
    return fold('arrears', ['Catch up', el('span', { class: 'muted' }, ' · nothing in arrears')], []);
  }
  const total = arrears[arrears.length - 1].runningTotal;
  const head = ['Catch up',
    el('span', { class: 'occ-partial' }, ` · ${arrears.length} behind · ${money(total)}`)];
  return fold('arrears', head, [
    el('p', { class: 'muted hint' },
      'Oldest first. Mark paid concludes a hole was paid without a fact; Assign a payment takes you '
      + 'to the Blotter to place the fact itself.'),
    scroll(el('table', {}, el('thead', {}, el('tr', {},
      el('th', {}, 'Due'), el('th', {}, 'Commitment'), el('th', {}, 'Status'),
      el('th', { class: 'amount' }, 'Expected'), el('th', { class: 'amount' }, 'Shortfall'),
      el('th', { class: 'amount' }, 'Running'), el('th', {}, ''))),
      el('tbody', {}, ...arrears.map(arrearRow)))),
  ]);
}

function arrearRow(a) {
  return el('tr', {},
    el('td', {}, a.dueDate),
    el('td', {}, a.commitmentName || shortId(a.commitmentId)),
    el('td', {}, el('span', { class: a.status === 'missed' ? 'occ-missed' : 'occ-partial' },
      a.status)),
    el('td', { class: 'amount' }, money(a.expected)),
    el('td', { class: 'amount' }, money(a.shortfall)),
    el('td', { class: 'amount' }, money(a.runningTotal)),
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
// box never loses focus. All rows show by default; filtering is opt-in (operator, 2026-10-08).
const filters = { origin: 'all', status: '', direction: '', q: '', sort: 'last' };
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
      declared ? null : el('span', { class: 'muted' }, ' · candidate'),
      !declared && seriesLine(c) ? el('div', { class: 'muted series' }, seriesLine(c)) : null,
      c.notes && c.notes.length
        ? el('div', {}, ...c.notes.map((n) => el('span', { class: 'note-chip',
            title: `${n.user} · ${(n.at || '').slice(0, 10)}` }, '\u270e ' + n.text)))
        : null),
    el('td', {}, el('span', { class: 'tag' }, c.origin)),
    el('td', {}, direction(c.currentAmount != null ? c.currentAmount
      : (c.direction === 'in' ? 1 : -1))),
    el('td', {}, c.cadence),
    el('td', {}, declared ? c.kind : el('span', { class: 'muted' }, '—')),
    el('td', {}, el('span', { class: 'tag ' + c.status }, c.status)),
    el('td', { class: 'amount' }, currentCell(c)),
    el('td', { class: 'muted' }, c.lastDate || '—'),
    el('td', { class: 'muted' }, c.nextDue || '—'),
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
  if (c.firstDate) bits.push(c.lastDate ? `${c.firstDate} → ${c.lastDate}` : c.firstDate);
  if (c.regularity != null) bits.push(`regularity ${c.regularity.toFixed(2)}`);
  if (c.outliers) bits.push(`+${c.outliers} one-off${c.outliers === 1 ? '' : 's'}`);
  if (c.variable) bits.push('variable');
  return bits.join(' · ');
}

function currentCell(c) {
  if (c.currentAmount == null) {
    return el('span', { class: 'muted' }, '—');
  }
  const cell = el('span', {}, money(c.currentAmount));
  if (c.previousAmount != null) {
    cell.append(el('span', { class: 'muted' }, ` from ${money(c.previousAmount)}`));
  }
  if (c.changePct != null) {
    const sign = c.changePct > 0 ? '+' : '';
    cell.append(el('span', { class: 'muted' },
      ` ${sign}${c.changePct.toFixed(1)}%${c.changeDate ? ` since ${c.changeDate}` : ''}`));
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
