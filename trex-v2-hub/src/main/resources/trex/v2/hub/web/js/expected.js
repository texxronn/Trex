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
import { openCommitmentNote, openDeclare, openRetire, openSettle } from './commitment.js';
import { direction } from './direction.js';
import { el, clear, scroll } from './dom.js';
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

/** The five occurrence states, each rendered as what it is: a fact, a conclusion, or a gap. */
function statusCell(status) {
  switch (status) {
    case 'occurred':
      return el('td', {}, el('span', { class: 'tick', title: 'a fact landed' }, '\u2713 occurred'));
    case 'settled':
      return el('td', {}, el('span', { class: 'occ-settled',
        title: 'settled by a person — a conclusion, not a fact' }, '\u25c6 settled'));
    case 'due':
      return el('td', {}, el('span', { class: 'muted', title: 'window open' }, 'due'));
    case 'partial':
      return el('td', {}, el('span', { class: 'occ-partial',
        title: 'part covered; the rest is in arrears' }, '\u25d0 partial'));
    case 'missed':
      return el('td', {}, el('span', { class: 'occ-missed',
        title: 'window closed with no match' }, '\u2717 missed'));
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
      'Oldest first. Settle concludes an occurrence was paid without a fact; Assign a payment '
      + 'takes you to the Blotter to pin the fact that paid it.'),
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
        onclick: () => settleCommitment(a) }, 'Settle'),
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

function registrySection() {
  const declared = registry.filter((c) => c.origin === 'declared').length;
  const candidates = registry.length - declared;
  const count = registry.length;
  const suffix = !candidates ? ` · ${count}`
    : candidates === count
      ? ` · ${count} candidate${count === 1 ? '' : 's'}`
      : ` · ${count} · ${candidates} candidate${candidates === 1 ? '' : 's'}`;
  const head = ['Commitments', el('span', { class: 'muted' }, suffix)];
  const columns = el('tr', {},
    el('th', {}, 'Commitment'), el('th', {}, 'Origin'), el('th', {}, 'Direction'),
    el('th', {}, 'Cadence'), el('th', {}, 'Kind'), el('th', {}, 'Status'),
    el('th', { class: 'amount' }, 'Current'), el('th', {}, 'Last'), el('th', {}, 'Next'),
    el('th', { class: 'amount' }, 'Arrears'), el('th', {}, ''));
  return fold('registry', head, [
    el('div', { class: 'section-actions' },
      el('button', { type: 'button', class: 'ghost',
        onclick: () => openDeclare(ctx, {
          summary: 'A commitment declared by hand: a manual-cadence bill, an income, anything '
            + 'detection did not propose.',
        }, load) }, 'Declare commitment')),
    registry.length
      ? scroll(el('table', {}, el('thead', {}, columns),
          el('tbody', {}, ...registry.map(registryRow))))
      : el('p', { class: 'muted' }, 'No commitments.'),
  ]);
}

function registryRow(c) {
  const declared = c.origin === 'declared';
  const arrears = c.arrearsCount
    ? `${c.arrearsCount} · ${money(Math.abs(c.arrearsAmount || 0))}` : '—';
  return el('tr', {},
    el('td', { class: 'desc' },
      el('span', { title: c.commitmentId }, c.name || shortId(c.commitmentId)),
      declared ? null : el('span', { class: 'muted' }, ' · candidate — confirm in Review'),
      c.notes && c.notes.length
        ? el('div', {}, ...c.notes.map((n) => el('span', { class: 'note-chip',
            title: `${n.user} · ${(n.at || '').slice(0, 10)}` }, '\u270e ' + n.text)))
        : null),
    el('td', {}, el('span', { class: 'tag' }, c.origin)),
    el('td', {}, direction(c.currentAmount != null ? c.currentAmount
      : (c.direction === 'in' ? 1 : -1))),
    el('td', {}, c.cadence),
    el('td', {}, c.kind),
    el('td', {}, el('span', { class: 'tag ' + c.status }, c.status)),
    el('td', { class: 'amount' }, currentCell(c)),
    el('td', { class: 'muted' }, c.lastDate || '—'),
    el('td', { class: 'muted' }, c.nextDue || '—'),
    el('td', { class: 'amount' }, arrears),
    el('td', {}, ...actions(c, declared)));
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
    cell.append(el('span', { class: 'muted' }, ` ${sign}${c.changePct.toFixed(1)}%`));
  }
  return cell;
}

function actions(c, declared) {
  if (!declared) {
    return [el('span', { class: 'muted' }, '')];
  }
  const out = [
    el('button', { type: 'button', class: 'ghost',
      onclick: () => openDeclare(ctx, reDeclarePrefill(c), load) }, 'Re-declare'),
  ];
  if (c.retiredN == null) {
    out.push(el('button', { type: 'button', class: 'ghost',
      onclick: () => openRetire(ctx, { commitmentId: c.commitmentId, name: c.name }, load) }, 'Retire'));
  }
  out.push(el('button', { type: 'button', class: 'ghost',
    onclick: () => openCommitmentNote(ctx, { commitmentId: c.commitmentId, name: c.name }, load) }, 'Note'));
  if (c.arrearsCount) {
    out.push(el('button', { type: 'button', class: 'ghost',
      onclick: () => settleCommitment(c) }, 'Settle'));
  }
  return out;
}

/** The declaration dialog's prefill: the current faces and the effective rules under the same id. */
function reDeclarePrefill(c) {
  return {
    commitmentId: c.commitmentId,
    name: c.name || '',
    kind: c.kind,
    direction: c.direction,
    cadence: c.cadence,
    amountKind: c.amountKind,
    amount: c.currentAmount == null ? null : Math.abs(c.currentAmount),
    anchor: c.anchorDate || '',
    matches: c.rules || [],
    fromCandidate: null,
    idLocked: true,
  };
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

function currentOf(commitmentId) {
  const row = registry.find((c) => c.commitmentId === commitmentId);
  return row && row.currentAmount != null ? row.currentAmount : null;
}
