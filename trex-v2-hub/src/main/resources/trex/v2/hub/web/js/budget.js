// Budget mode (V2-SPEC.md §9.2, V2-BUDGET-VIEW-PLAN.md §5): the month's sheet — Money In, Money Out
// and Money Left Over — with a donut of Money Out by primary group and a muted "outside the budget"
// section for the transfers that never touch a budget account. One read, /api/budget, over the
// derived index: the sheet's `left` equals the status line's headroom for the same month, because
// the hub shares headroom's row-walk (§9.2), not because the view computes anything of its own.
//
// The month lives in the URL (#budget?month=YYYY-MM) so a month is linkable and survives a reload.
// Stepping rewrites it with history.replaceState, never location.hash: a hash assignment fires
// `hashchange` and the router would remount the mode on every step.

import { api } from './api.js';
import { accountChip } from './account.js';
import { el, clear, field, scroll, svgEl } from './dom.js';
import { date, money } from './format.js';

// The synthetic slices the hub appends after the rolled-up groups: money still due, money moved to
// one of your own accounts outside the budget (D-2, a move) and the wallet's unrecorded cash. They
// are not spending, so the tables mark them muted and the donut keeps them apart from the groups.
const SYNTHETIC = new Set(['Due', 'Moved out', 'Unrecorded']);

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
const SYNTHETIC_COLOR = { Due: '#94a3b8', 'Moved out': '#a8a29e', Unrecorded: '#78716c' };
const OTHER_COLOR = '#6b7280';

let host;
let ctx;
let month = null; // the YYYY-MM in the URL; the response's month is the truth once it arrives
let data = null;
let loadGeneration = 0; // supersedes an in-flight load when a newer one starts
let errorBar;
let periodLabel;
let jumpInput;
let sheetHost;

export function mount(container, context) {
  host = container;
  ctx = context;
  month = (context.modeQuery && context.modeQuery.get('month')) || null;
  buildShell();
  renderSheet();
  load();
  return { refresh: load };
}

async function load() {
  const generation = ++loadGeneration;
  try {
    data = await api.budget(month || undefined);
    if (generation !== loadGeneration) return; // superseded: a newer load owns the view
    month = data.month; // the hub names the month it read, whether or not the URL asked for one
    errorBar.hidden = true;
  } catch (error) {
    if (generation !== loadGeneration) return;
    // The sheet keeps the last month it did read; the stepper follows it rather than the failed
    // query, so the toolbar and the tables never disagree about which month is on screen.
    if (data) month = data.month;
    errorBar.textContent = error.message || 'failed to load the budget sheet';
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
  host.append(errorBar,
    el('div', { class: 'toolbar' },
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
  const outside = outsideSection();
  if (outside) sheetHost.append(outside);
}

/**
 * Money Left Over (V2-SPEC.md §9.2): one figure that answers "am I okay?", with the arithmetic beside
 * it and the date the statements reach, so a late statement reads as a late statement and not as
 * money. It is the sheet's header, and the same headroom the status line carries.
 */
function leftBar() {
  const { income, spend, missed, unpaired, left, through, accounts } = data;
  const figure = el('b', { class: left < 0 ? 'occ-missed' : 'tick' }, money(left));
  // The arithmetic, in the shape the old single line used. The read rolls committed and uncommitted
  // spend into one `posted` figure (they share a row-walk), so the old commitments/other-spend split
  // is not available here; what is left is what came in against what went out.
  const parts = [
    `in ${money(income.posted)}` + (income.due ? ` + ${money(income.due)} due` : ''),
    `out ${money(spend.posted)}` + (spend.due ? ` + ${money(spend.due)} due` : ''),
  ];
  // The wallet's unrecorded cash (V2-CASH-PLAN.md §4.2): spend the bank movements imply but no fact
  // records. Signed by the backend — positive is spend, so it moves `left` like any outflow.
  if (spend.unrecorded) parts.push(`unrecorded cash ${money(spend.unrecorded)}`);
  if (spend.movedOut || income.movedIn) {
    parts.push(`moved to your other accounts ${money(spend.movedOut - income.movedIn)}`);
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
    el('div', { class: 'muted', title: 'budget accounts: ' + (accounts || []).join(', ') },
      through ? `through ${date(through)} — the statements say nothing after it` : 'no statements yet'),
    notes.length ? el('div', {}, ...notes.flatMap((n, i) => (i ? [' · ', n] : [n]))) : '');
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
 * Money In: the posted income per category, then the Due line when non-zero, then — when money came
 * from one of your own accounts outside the budget (D-2) — the move-in row, which opens into the
 * accounts it came from.
 */
function moneyIn() {
  const rows = data.income.lines.map((line) =>
    el('tr', {}, el('td', {}, line.category), el('td', { class: 'amount' }, money(line.amount))));
  if (data.income.due) {
    rows.push(el('tr', {}, el('td', {}, 'Due'),
      el('td', { class: 'amount' }, money(data.income.due))));
  }
  if (data.income.movedIn) {
    rows.push(...expandableRow('', ['from your other accounts'], data.income.movedIn,
      data.income.movedInParts || [], true, 'click for the accounts this move came from'));
  }
  rows.push(el('tr', { class: 'total' }, el('td', {}, 'Total'),
    el('td', { class: 'amount' }, money(data.income.total))));
  return section('Money In', scroll(el('table', { class: 'budget-table' }, el('tbody', {}, ...rows))));
}

/**
 * Money Out, one row per slice. A group row opens into its parts — the trex categories inside it
 * (the read carries them, §4) — and the Moved out row opens into the accounts the money went to, so
 * the compact total can be read first and the distribution only when wanted. The synthetic
 * Due/Unrecorded rows are muted and do not open.
 */
function moneyOut() {
  const rows = [];
  for (const slice of data.spend.slices) {
    const synthetic = SYNTHETIC.has(slice.group);
    // A Moved out slice's parts are account refs, not categories, so they render as chips (D-2).
    const chip = slice.group === 'Moved out';
    const note = chip
      // D-2: a move to one of your own accounts outside the budget. It is a slice so the donut
      // sums to Money Out, but the row says plainly that it is not saving.
      ? el('span', { class: 'muted', title: 'money moved to one of your own accounts outside the budget' },
          ' \u00b7 a move, not spending')
      : null;
    rows.push(...expandableRow(synthetic ? 'synthetic' : '', [slice.group, note], slice.amount,
      slice.parts || [], chip,
      chip ? 'click for the accounts this move went to'
        : 'click for the categories inside this group'));
  }
  rows.push(el('tr', { class: 'total' }, el('td', {}, 'Total'),
    el('td', { class: 'amount' }, money(data.spend.total))));
  return section('Money Out', scroll(el('table', { class: 'budget-table' }, el('tbody', {}, ...rows))));
}

/**
 * One row that opens into its parts: a group into the trex categories inside it, a move into the
 * accounts it went to or came from (D-2). {@code chip} says the part labels are account refs and
 * renders them as account chips; otherwise they are plain category text. {@code classes} is extra
 * row styling (the synthetic rows are muted). An empty distribution yields just the row, no twisty.
 */
function expandableRow(classes, labelChildren, amount, parts, chip, title) {
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
    el('td', { class: 'amount' }, money(amount)));
  return [row, ...partRows];
}

/**
 * The Money Out donut: one arc per slice, the centre carrying the Money Out total. A slice below 2%
 * of Money Out would shred the ring on a long tail, so the small groups fold into one Other arc;
 * the synthetic Due/Moved&nbsp;out/Unrecorded stay separate because they are not spending.
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
      moneyWhole(data.spend.total)),
    svgEl('text', { class: 'donut-word', x: 100, y: 115, 'text-anchor': 'middle' }, 'out'));
  // A compact table, not a loose list: the name left, the amount and share right, boxed by a thin
  // rule so the eye can run down it.
  const legend = el('table', { class: 'budget-legend' }, el('tbody', {},
    ...drawn.map((slice, index) => el('tr', {},
      el('td', { class: 'name' },
        el('span', { class: 'swatch', style: 'background:' + colorOf(slice, index) }), slice.group),
      el('td', { class: 'amt' }, money(slice.amount)),
      el('td', { class: 'share' }, `${Math.round((slice.amount / drawnTotal) * 100)}%`)))));
  return section('Money Out by group', ring, legend);
}

function arcs() {
  const groups = data.spend.slices.filter((slice) => !SYNTHETIC.has(slice.group));
  const synthetics = data.spend.slices.filter((slice) => SYNTHETIC.has(slice.group));
  const threshold = data.spend.total * 0.02;
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

/**
 * Outside the budget (D-3): the transfers between two accounts that are both outside it — the loan
 * repayments, one line per pair with the count of payments behind it — and those accounts' own rows,
 * which the budget cannot see (the loan interest, income into a savings account, its interest
 * earned). None of it is in In/Out/Left: it never touches a budget account, and the Mortgage tab
 * owns the detail.
 */
function outsideSection() {
  const moves = data.outside || [];
  const externals = data.outsideExternal || [];
  if (!moves.length && !externals.length) return null;
  const rows = moves.map((entry) =>
    el('tr', {},
      el('td', {}, accountChip(ctx.refdata, entry.from), ' \u2192 ', accountChip(ctx.refdata, entry.to)),
      el('td', { class: 'note muted' }, entry.count > 1 ? `${entry.count} payments` : ''),
      el('td', { class: 'amount' }, money(entry.amount))));
  for (const entry of externals) {
    // Signed: negative is money out. The category names the row the outside account carries.
    rows.push(el('tr', {},
      el('td', {}, accountChip(ctx.refdata, entry.account)),
      el('td', { class: 'note muted' }, entry.category),
      el('td', { class: 'amount' }, money(entry.amount))));
  }
  return el('section', { class: 'mode-section budget-outside' },
    el('h4', {}, 'Outside the budget'),
    el('table', { class: 'budget-outside-table' }, el('tbody', {}, ...rows)),
    el('p', { class: 'muted' },
      'These accounts are not in the budget, so none of this is in the figures above.'));
}

function hasRows(sheet) {
  return Boolean(sheet.income.lines.length || sheet.income.due || sheet.income.movedIn
    || sheet.spend.slices.length || sheet.outside.length || sheet.outsideExternal.length);
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

function jump(event) {
  if (!event.target.value) return;
  goTo(monthKey(parseIso(event.target.value)));
}

/** Stepping and jumping change one month, rewrite the URL in place, and re-read. */
function goTo(key) {
  month = key;
  history.replaceState(null, '', '#budget?month=' + key);
  load();
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
