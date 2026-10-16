// Cash flow mode (V2-SPEC.md §9.2; V2-CASHFLOW-PLAN.md C-1, C-4, C-6): the month's cash-flow sheet —
// Money In, Money Out and Money Left Over of the liquid accounts — with a donut of Money Out. One
// read, /api/cashflow, over the derived index, in one of two views the toolbar switches between:
// Cost (spending; the loan principal and the set-aside are a muted bridge under the tables) and
// Cash (the mortgage repayment and the set-aside are Money Out slices, so Left is the change in the
// liquid accounts). The Cost view's `left` equals the status line's headroom for the same month
// because the hub shares headroom's row-walk, not because this view computes anything of its own.
//
// C-7/C-8: `exclude` (a view filter over group or leaf keys; absent = the server's defaults, present
// even empty = exactly that list) and `pivot=leaf` (Money Out and the donut by leaf category rather
// than primary group) live in the URL too.
//
// The month and the view live in the URL (#cashflow?month=YYYY-MM&view=cash) so a sheet is linkable
// and survives a reload. Changing either rewrites it with history.replaceState, never
// location.hash: a hash assignment fires `hashchange` and the router would remount the mode.
// (#budget is an alias the router maps here; the first write replaces it with #cashflow.)

import { api } from './api.js';
import { accountChip } from './account.js';
import { el, clear, field, scroll, svgEl } from './dom.js';
import { date, money } from './format.js';

// The synthetic slices the hub appends after the rolled-up groups: money still due and the wallet's
// unrecorded cash. They are not posted spending, so the tables mark them muted and the donut keeps
// them apart from the groups. Cash view's Mortgage and Set aside are posted money out, so they are
// ordinary groups here and carry their own parts (account refs for Set aside).
const VIEWS = { cost: 'Cost', cash: 'Cash' };
const ACCOUNT_PARTS = new Set(['Set aside']);
const SYNTHETIC = new Set(['Due', 'Unrecorded']);
// Slices that are not a category's spending: never excludable, the same in both pivots.
const NON_CATEGORY = new Set(['Mortgage', 'Set aside', 'Due', 'Unrecorded']);
const PIVOTS = { primary: 'Primary', leaf: 'Leaf' };

// The donut's geometry, in the SVG's own 200×200 space. The ring is stroked circles, not arc paths:
// one <circle> per slice with a dash pattern of its arc length, which needs no trigonometry.
const DONUT_RADIUS = 70;
const DONUT_STROKE = 36;
const DONUT_CIRCUMFERENCE = 2 * Math.PI * DONUT_RADIUS;

// The palette of the operator's reference budget sheet (budget_image.webp), sampled from it:
// magenta, blue, green, amber, red, then grey, and a few more in the same family so a month with
// many groups does not repeat a colour. Presentation only — nothing reads a colour back — and
// assigned by arc index, which is amount-descending, so the biggest slice takes the first colour.
const PALETTE = ['#c43074', '#479ff8', '#82d553', '#eebb42', '#e94025', '#929292',
  '#6c7ae0', '#00b0a8', '#b46cd6', '#a9683c'];
const SYNTHETIC_COLOR = { Due: '#94a3b8', Unrecorded: '#78716c' };
const OTHER_COLOR = '#6b7280';

let host;
let ctx;
let month = null; // the YYYY-MM in the URL; the response's month is the truth once it arrives
let view = 'cost'; // 'cost' | 'cash', also in the URL
let pivot = 'primary'; // 'primary' | 'leaf', also in the URL
let excludeKeys = null; // null = not in the URL (server defaults); an array once the viewer toggles
let data = null;
let loadGeneration = 0; // supersedes an in-flight load when a newer one starts
let errorBar;
let periodLabel;
let jumpInput;
let viewTabs;
let sheetHost;

export function mount(container, context) {
  host = container;
  ctx = context;
  month = (context.modeQuery && context.modeQuery.get('month')) || null;
  view = context.modeQuery && context.modeQuery.get('view') === 'cash' ? 'cash' : 'cost';
  pivot = context.modeQuery && context.modeQuery.get('pivot') === 'leaf' ? 'leaf' : 'primary';
  excludeKeys = context.modeQuery && context.modeQuery.has('exclude')
    ? context.modeQuery.get('exclude').split(',').filter(Boolean) : null;
  data = null;
  buildShell();
  renderSheet();
  load();
  return { refresh: load };
}

async function load() {
  const generation = ++loadGeneration;
  try {
    data = await api.cashflow(month || undefined, { view, exclude: excludeKeys });
    if (generation !== loadGeneration) return; // superseded: a newer load owns the view
    month = data.month; // the hub names the month it read, whether or not the URL asked for one
    errorBar.hidden = true;
  } catch (error) {
    if (generation !== loadGeneration) return;
    // The sheet keeps the last month it did read; the stepper follows it rather than the failed
    // query, so the toolbar and the tables never disagree about which month is on screen.
    if (data) month = data.month;
    errorBar.textContent = error.message || 'failed to load the cash flow sheet';
    errorBar.hidden = false;
    return;
  }
  renderSheet();
}

/** The toolbar and the sheet's host, built once: an SSE refresh re-renders the sheet, not the
 *  toolbar, so the jump input never loses what the operator is typing into it. */
function buildShell() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  periodLabel = el('span', { class: 'period muted' });
  jumpInput = el('input', { type: 'date', onchange: jump });
  sheetHost = el('div');
  viewTabs = el('div', { class: 'tabs', role: 'group', 'aria-label': 'View' },
    ...Object.entries(VIEWS).map(([value, label]) => el('button', {
      type: 'button', class: 'tab', 'data-view': value, onclick: () => setView(value),
    }, label)));
  host.append(errorBar,
    el('div', { class: 'toolbar' },
      viewTabs,
      button('\u2039', () => step(-1)),
      periodLabel,
      button('\u203a', () => step(1)),
      field('Jump', jumpInput)),
    sheetHost);
}

function renderSheet() {
  const key = currentMonth();
  if (key) {
    periodLabel.textContent = monthLabel(key);
    jumpInput.value = key + '-01';
  }
  markTabs();
  clear(sheetHost);
  if (!data) {
    sheetHost.append(el('p', { class: 'muted' }, 'Loading…'));
    return;
  }
  sheetHost.append(leftBar());
  if (!hasRows(data)) {
    // A month with nothing recorded is an empty sheet, not an error.
    sheetHost.append(el('p', { class: 'muted' }, `Nothing recorded in ${monthName(data.month)}.`));
    return;
  }
  sheetHost.append(sheetGrid());
  if (data.view === 'cost') {
    const bridge = costBridge();
    if (bridge) sheetHost.append(bridge);
  }
}

/**
 * Money Left Over (V2-SPEC.md §9.2): one figure that answers "am I okay?", with the arithmetic beside
 * it and the date the statements reach, so a late statement reads as a late statement and not as
 * money. It is the sheet's header, and the same headroom the status line carries. A move between
 * your own accounts is never In or Out.
 */
function leftBar() {
  const { income, spend, missed, unpaired, left, through, accounts } = data;
  const figure = el('b', { class: left < 0 ? 'occ-missed' : 'tick' }, money(left));
  const parts = [
    `in ${money(income.total)}`,
    `out ${money(spend.total)}`,
  ];
  if (data.excluded && data.excluded.total > 0) {
    const keys = data.excluded.keys || [];
    const shown = keys.length > 3 ? keys.slice(0, 2).join(', ') + ' +' + (keys.length - 2) : keys.join(', ');
    parts.push(`Left excl. ${shown || 'exclusions'} ${money(data.leftExcl)}`);
  }
  const notes = [];
  if (missed) notes.push(el('span', { class: 'occ-missed' }, `not seen yet: ${money(missed)}`));
  if (unpaired) {
    notes.push(el('span', { class: 'muted', title: 'transfer legs still waiting for their other side' },
      `unpaired transfers: ${money(unpaired)}`));
  }
  return el('section', { class: 'headroom' },
    el('div', {}, el('span', { class: 'muted' }, `Left in ${monthName(data.month)} `), figure),
    el('div', { class: 'muted' }, parts.join(' · ')),
    el('div', { class: 'muted', title: 'liquid accounts: ' + (accounts || []).join(', ') },
      through ? `through ${date(through)} — the statements say nothing after it` : 'no statements yet'),
    notes.length ? el('div', {}, ...notes.flatMap((n, i) => (i ? [' · ', n] : [n]))) : '',
    data.view === 'cash' ? reconcileLine() : '');
}

/**
 * C-6: the Cash view's Left should be the change in the liquid accounts, so the hub compares it with
 * the accounts' own rows. Informational only: a gap is never an error here, and a 1-cent gap is a
 * real possible value, shown as it is. A paid occurrence with no statement row yet (a "Mark paid")
 * is in Left but not in the rows, which the muted note names.
 */
function reconcileLine() {
  const r = data.reconcile;
  if (!r) return '';
  const children = [r.gap === 0
    ? "Matches the accounts' rows"
    : `Rows differ by ${money(r.gap)}`];
  if (r.settledWithoutFact) {
    children.push(` · includes ${money(r.settledWithoutFact)} marked paid without a statement row`);
  }
  return el('div', { class: 'muted reconcile', title: 'the Cash view\'s Left against the signed sum of the liquid accounts\' rows' },
    ...children);
}

/** Money In and Money Out in one column, the donut beside them on a wide screen (§5). */
function sheetGrid() {
  const grid = el('div', { class: 'budget-sheet' },
    el('div', { class: 'budget-col' }, moneyIn(), moneyOut()));
  const donut = donutSection();
  if (donut) grid.append(el('div', { class: 'budget-col' }, donut));
  return grid;
}

/**
 * Money In: the posted income per category on the liquid accounts (and the loans' credits), then
 * the Due line when non-zero, then the total. A move is never here.
 */
function moneyIn() {
  const rows = data.income.lines.map((line) =>
    el('tr', {}, el('td', {}, line.category), el('td', { class: 'amount' }, money(line.amount))));
  if (data.income.due) {
    rows.push(el('tr', {}, el('td', {}, 'Due'),
      el('td', { class: 'amount' }, money(data.income.due))));
  }
  rows.push(el('tr', { class: 'total' }, el('td', {}, 'Total'),
    el('td', { class: 'amount' }, money(data.income.total))));
  return section('Money In', scroll(el('table', { class: 'budget-table' }, el('tbody', {}, ...rows))));
}

/**
 * Money Out, one row per slice: the rolled-up groups and the synthetic Due/Unrecorded. A group row
 * opens into its parts — the trex categories inside it (the read carries them). A move is never a
 * slice, so the donut is real spending only.
 */
/** The row tooltip, worded per slice: Mortgage and Set aside do not open into categories. */
function rowTitle(group) {
  if (group === 'Mortgage') return 'click for interest and principal';
  if (ACCOUNT_PARTS.has(group)) return 'click for the accounts';
  return 'click for the categories inside this group';
}

function moneyOut() {
  const rows = [];
  const excluded = excludedInfo();
  for (const slice of outRows()) {
    const fixed = NON_CATEGORY.has(slice.group);
    const parts = pivot === 'leaf' && !fixed ? [] : (slice.parts || []);
    rows.push(...expandableRow(SYNTHETIC.has(slice.group) ? 'synthetic' : '', [slice.group], slice.amount,
      parts, ACCOUNT_PARTS.has(slice.group),
      rowTitle(slice.group),
      fixed ? null : slice.group));
  }
  const net = data.spend.total - excluded.total;
  rows.push(el('tr', { class: 'total' }, el('td', {}, 'Total'),
    el('td', { class: 'amount' }, money(net),
      excluded.total > 0 ? el('span', { class: 'muted full-total', title: 'before exclusions' },
        ' ' + money(data.spend.total)) : null)));
  return el('section', { class: 'mode-section' },
    el('h4', {}, 'Money Out'),
    pivotTabs(),
    scroll(el('table', { class: 'budget-table' }, el('tbody', {}, ...rows))),
    excludedLine());
}

function excludedInfo() {
  const e = data.excluded || {};
  return { keys: e.keys || [], total: e.total || 0, parts: e.parts || [] };
}

/**
 * The rows of Money Out in the current pivot. Primary: the slices as the hub sent them. Leaf: the
 * group slices' parts flattened to one row per leaf category (amount desc), then the slices that are
 * not a category (Mortgage, Set aside, Due, Unrecorded) unchanged. Excluded spending is already out
 * of the slices; the server did that.
 */
function outRows() {
  const slices = data.spend.slices;
  if (pivot !== 'leaf') return slices;
  const byLeaf = new Map();
  for (const slice of slices) {
    if (NON_CATEGORY.has(slice.group)) continue;
    for (const part of slice.parts || []) {
      byLeaf.set(part.label, (byLeaf.get(part.label) || 0) + part.amount);
    }
  }
  const leaves = [...byLeaf].map(([group, amount]) => ({ group, amount }))
    .sort((x, y) => y.amount - x.amount || x.group.localeCompare(y.group));
  return [...leaves, ...slices.filter((slice) => NON_CATEGORY.has(slice.group))];
}

/** Primary | Leaf: view-only, so it re-renders from the data in hand rather than re-reading. */
function pivotTabs() {
  return el('div', { class: 'tabs pivot-tabs', role: 'group', 'aria-label': 'Pivot' },
    ...Object.entries(PIVOTS).map(([value, label]) => el('button', {
      type: 'button', class: 'tab' + (value === pivot ? ' active' : ''), 'data-pivot': value,
      onclick: () => setPivot(value),
    }, label)));
}

function setPivot(next) {
  if (next === pivot) return;
  pivot = next;
  writeUrl();
  renderSheet();
}

/**
 * The Excluded line (C-7): its total and, for each key, a button that includes it again, so an
 * excluded key is never out of reach. The line opens into the excluded leaves.
 */
function excludedLine() {
  const { keys, total, parts } = excludedInfo();
  if (!keys.length) return null;
  const partRows = parts.map((part) => el('tr', { class: 'leaf', hidden: true },
    el('td', {}, part.label), el('td', { class: 'amount' }, money(part.amount))));
  const twisty = el('span', { class: 'twisty' }, '\u25b8 ');
  const open = el('button', {
    type: 'button', class: 'link', disabled: parts.length ? null : 'disabled',
    onclick: () => {
      const show = partRows[0].hidden;
      for (const row of partRows) row.hidden = !show;
      twisty.textContent = show ? '\u25be ' : '\u25b8 ';
    },
  }, twisty, `Excluded ${money(total)}`);
  return el('div', { class: 'excluded-line' },
    el('div', { class: 'muted' }, open, ' \u00b7 ',
      ...keys.flatMap((key, i) => [i ? ' ' : '', el('button', {
        type: 'button', class: 'excl-key', title: 'include ' + key + ' again',
        onclick: () => toggleExclude(key),
      }, key + ' \u21ba')])),
    partRows.length ? scroll(el('table', { class: 'budget-table' }, el('tbody', {}, ...partRows))) : null);
}

/** The \u2298 control: adds or removes one key from the exclude list and re-reads. The first toggle
 *  starts from the keys the response says are in force, so the config's defaults are respected. */
function excludeToggle(key) {
  const off = excludedInfo().keys.includes(key);
  return el('button', {
    type: 'button', class: 'excl-toggle' + (off ? ' on' : ''),
    title: (off ? 'include ' : 'exclude ') + key, 'aria-label': (off ? 'include ' : 'exclude ') + key,
    onclick: (event) => { event.stopPropagation(); toggleExclude(key); },
  }, '\u2298');
}

function toggleExclude(key) {
  const base = excludeKeys || excludedInfo().keys;
  excludeKeys = base.includes(key) ? base.filter((k) => k !== key) : [...base, key];
  writeUrl();
  load();
}

/**
 * One row that opens into its parts: a group into the trex categories inside it, a move into the
 * accounts it went to or came from (D-2). {@code chip} says the part labels are account refs and
 * renders them as account chips; otherwise they are plain category text. {@code excludeKey}, when
 * given, adds the \u2298 toggle for that key to the amount cell. {@code classes} is extra
 * row styling (the synthetic rows are muted). An empty distribution yields just the row, no twisty.
 */
function expandableRow(classes, labelChildren, amount, parts, chip, title, excludeKey) {
  const twisty = parts.length ? el('span', { class: 'twisty' }, '\u25b8 ') : null;
  const partRows = parts.map((part) => el('tr', { class: 'leaf', hidden: true },
    el('td', {}, chip ? accountChip(ctx.refdata, part.label) : part.label),
    el('td', { class: 'amount' }, money(part.amount))));
  const row = el('tr', {
      class: (classes + (parts.length ? ' expandable' : '')).trim() || null,
      title: parts.length ? title : null,
      onclick: parts.length ? () => {
        const open = partRows[0].hidden;
        for (const partRow of partRows) partRow.hidden = !open;
        twisty.textContent = open ? '\u25be ' : '\u25b8 ';
      } : null,
    },
    el('td', {}, twisty, ...labelChildren),
    el('td', { class: 'amount' }, money(amount), excludeKey ? excludeToggle(excludeKey) : null));
  return [row, ...partRows];
}

/**
 * The Money Out donut: one arc per slice, the centre carrying the Money Out total. A slice below 2%
 * of Money Out would shred the ring on a long tail, so the small groups fold into one Other arc;
 * the synthetic Due/Unrecorded stay separate because they are not posted spending.
 */
function donutSection() {
  const drawn = arcs().filter((slice) => slice.amount > 0);
  if (!drawn.length) return null;
  // The ring's denominator is what is drawn, so it always closes: normally that is spend.total, but
  // a signed-negative Unrecorded (a refund) would otherwise make the positive arcs overrun the ring.
  const drawnTotal = drawn.reduce((sum, slice) => sum + slice.amount, 0);
  let offset = 0;
  const rings = drawn.map((slice, index) => {
    const length = (slice.amount / drawnTotal) * DONUT_CIRCUMFERENCE;
    const circle = svgEl('circle', {
      cx: 100, cy: 100, r: DONUT_RADIUS, fill: 'none',
      stroke: colorOf(slice, index), 'stroke-width': DONUT_STROKE,
      'stroke-dasharray': `${length} ${DONUT_CIRCUMFERENCE - length}`,
      'stroke-dashoffset': `${-offset}`,
      transform: 'rotate(-90 100 100)',
    }, svgEl('title', {}, `${slice.group} · ${money(slice.amount)}`));
    offset += length;
    return circle;
  });
  const ring = svgEl('svg', { class: 'budget-donut', viewBox: '0 0 200 200', role: 'img' },
    ...rings,
    svgEl('text', { class: 'donut-total', x: 100, y: 97, 'text-anchor': 'middle' },
      moneyWhole(data.spend.total - excludedInfo().total)),
    svgEl('text', { class: 'donut-word', x: 100, y: 115, 'text-anchor': 'middle' }, 'out'));
  // A compact table, not a loose list: the name left, the amount and share right, boxed by a thin
  // rule so the eye can run down it.
  const legend = el('table', { class: 'budget-legend' }, el('tbody', {},
    ...drawn.map((slice, index) => el('tr', {},
      el('td', { class: 'name' },
        el('span', { class: 'swatch', style: 'background:' + colorOf(slice, index) }), slice.group),
      el('td', { class: 'amt' }, money(slice.amount)),
      el('td', { class: 'share' }, `${Math.round((slice.amount / drawnTotal) * 100)}%`),
      el('td', { class: 'tog' }, slice.group === 'Other' || NON_CATEGORY.has(slice.group)
        ? null : excludeToggle(slice.group))))));
  return section(pivot === 'leaf' ? 'Money Out by category' : 'Money Out by group', ring, legend);
}

function arcs() {
  // Same rows as the table in the current pivot; Mortgage and Set aside (Cash view) are arcs of
  // their own and stay in the folding pool as they were, Due and Unrecorded go last.
  const all = outRows();
  const groups = all.filter((slice) => !SYNTHETIC.has(slice.group));
  const synthetics = all.filter((slice) => SYNTHETIC.has(slice.group));
  const threshold = (data.spend.total - excludedInfo().total) * 0.02;
  const folded = [];
  let tail = 0;
  for (const slice of groups) {
    if (slice.amount < threshold) tail += slice.amount;
    else folded.push(slice);
  }
  if (tail) folded.push({ group: 'Other', amount: tail });
  return [...folded, ...synthetics];
}

/** The arc's colour: the fixed palette by arc index, a neutral grey for the synthetic arcs. */
function colorOf(slice, index) {
  if (SYNTHETIC.has(slice.group)) return SYNTHETIC_COLOR[slice.group];
  if (slice.group === 'Other') return OTHER_COLOR;
  return PALETTE[index % PALETTE.length];
}

/** The bridge's sentence, worded from the rows actually shown. */
function bridgeNote(principal, aside) {
  const what = principal && aside ? 'Neither is spending' : 'This is not spending';
  const why = [];
  if (principal) why.push('the principal becomes equity in the home');
  if (aside) why.push('the set-aside money is still yours');
  return `${what}: ${why.join(' and ')}. The Cash view shows ${principal && aside ? 'them as Money Out slices' : 'it as a Money Out slice'} instead.`;
}

function hasRows(sheet) {
  return Boolean(sheet.income.lines.length || sheet.income.due || sheet.spend.slices.length
    || (sheet.loans && (sheet.loans.repayments || sheet.loans.charges))
    || (sheet.setAside && sheet.setAside.total));
}

/**
 * The Cost view's bridge (C-4): the two things that left the liquid accounts but are not spending.
 * Principal repaid is equity gained, set-aside money still belongs to you in another account; the
 * Cash view shows both as Money Out slices instead. Muted, so it reads as a footnote to Left.
 */
function costBridge() {
  const principal = data.loans ? data.loans.principal : 0;
  const aside = data.setAside ? data.setAside.total : 0;
  if (!principal && !aside) return null;
  const rows = [];
  if (principal) {
    const parts = (data.loans.parts || []).filter((p) => p.principal)
      .map((p) => ({ label: p.account, amount: p.principal }));
    rows.push(...expandableRow('', ['Principal \u2192 home equity'], principal, parts, true,
      'click for the loans'));
  }
  if (aside) {
    rows.push(...expandableRow('', ['Set aside'], aside, data.setAside.parts || [], true,
      'click for the accounts'));
  }
  return el('section', { class: 'mode-section bridge' },
    scroll(el('table', { class: 'budget-table' }, el('tbody', {}, ...rows))),
    el('p', { class: 'muted' }, bridgeNote(principal, aside)));
}

// ---- the month stepper -------------------------------------------------------------------------

function step(direction) {
  const key = currentMonth();
  if (!key) return;
  const bounds = boundsOf(key);
  if (!bounds) return;
  const first = new Date(bounds.from.getTime());
  first.setUTCMonth(first.getUTCMonth() + direction);
  goTo(monthKey(first));
}

function markTabs() {
  for (const tab of viewTabs.children) {
    tab.classList.toggle('active', tab.dataset.view === view);
  }
}

function setView(next) {
  if (next === view) return;
  view = next;
  markTabs(); // at once: the sheet below keeps the view it was read in until the new read lands
  writeUrl();
  load();
}

function jump(event) {
  if (!event.target.value) return;
  goTo(monthKey(parseIso(event.target.value)));
}

/** Stepping and jumping change one month, rewrite the URL in place, and re-read. */
function goTo(key) {
  month = key;
  writeUrl();
  load();
}

/**
 * The month, view, pivot and exclude list live in the URL query, so a sheet is linkable and survives a reload.
 * history.replaceState, never location.hash: a hash assignment fires `hashchange` and the router
 * would remount the mode on every change.
 */
function writeUrl() {
  const params = new URLSearchParams();
  if (month) params.set('month', month);
  if (view !== 'cost') params.set('view', view);
  if (pivot !== 'primary') params.set('pivot', pivot);
  if (excludeKeys !== null) params.set('exclude', excludeKeys.join(','));
  const query = params.toString();
  history.replaceState(null, '', '#cashflow' + (query ? '?' + query : ''));
}

function currentMonth() {
  return month || (data && data.month) || null;
}

// ---- month keys and labels ---------------------------------------------------------------------
// The month case of eyeball.js's period maths, kept local rather than imported: this view needs one
// grain only, and a shared period module would be an abstraction for a single caller.

/** The first day a month key names, used only to step one calendar month at a time. */
function boundsOf(key) {
  const month = /^(\d{4})-(\d{2})$/.exec(key || '');
  if (!month) return null;
  return { from: new Date(Date.UTC(+month[1], +month[2] - 1, 1)) };
}

function monthKey(date) {
  return date.toISOString().slice(0, 7);
}

function parseIso(value) {
  const [y, m, d] = value.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function monthName(key) {
  return new Date(key + '-01T00:00:00Z')
    .toLocaleString(undefined, { month: 'long', timeZone: 'UTC' });
}

function monthLabel(key) {
  return `${monthName(key)} ${key.slice(0, 4)}`;
}

// ---- helpers -----------------------------------------------------------------------------------

function section(title, ...children) {
  return el('section', { class: 'mode-section' }, el('h4', {}, title), ...children);
}

function button(label, onClick) {
  return el('button', { type: 'button', onclick: onClick }, label);
}

/**
 * The donut centre's whole-dollar form: the hole is narrow, and the exact dollars and cents are
 * already in the Money Out table and the legend beside it, so the centre only has to read at a
 * glance.
 */
function moneyWhole(cents) {
  return money(cents).replace(/\.\d\d$/, '');
}
