// Cash mode (V2-CASH-PLAN.md §4.3, V2-SPEC.md §9.3): one declared wallet's rows, newest first, and
// a quick-add form. A spend, an income or a count is a fact; the optional category is a PIN on that
// same fact, in the same request, so the writer stays the only interpreter. A synthetic row is a
// wallet transfer — an ATM withdrawal or a cash deposit — not a manual entry. The header reads
// cash_month (Stage 4): the month's unrecorded figure and its parts, newest first.

import { api } from './api.js';
import { accountChip } from './account.js';
import { el, clear, field, scroll } from './dom.js';
import { categoryTitle, date, money } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let wallet = '';
let monthsHost = null;
let rowsHost = null;
let inputs = null;

export function mount(container, context) {
  host = container;
  ctx = context;
  wallet = declaredWallets()[0] || '';
  render();
  if (wallet) load();
  return { refresh: load };
}

/** The wallets are the declared-balance accounts: the ones with no statement to reconcile against. */
function declaredWallets() {
  return ((ctx.refdata && ctx.refdata.accounts) || [])
    .filter((a) => a.balanceSource === 'declared')
    .map((a) => a.ref);
}

function render() {
  clear(host);
  const wallets = declaredWallets();
  if (!wallets.length) {
    host.append(el('p', { class: 'muted' }, 'No declared cash account is configured.'));
    return;
  }
  monthsHost = el('div');
  rowsHost = el('div');
  inputs = form();
  host.append(
    el('div', { class: 'toolbar' }, ...walletControl(wallets)),
    monthsHost,
    inputs.node,
    el('p', { class: 'hint muted' },
      'The header is derived: the month\u2019s movements less what was recorded are the cash nobody '
      + 'wrote down. The rows below are the wallet\u2019s facts, newest first.'),
    rowsHost);
}

/** One declared wallet needs no choice; more than one gets a selector, first selected. */
function walletControl(wallets) {
  if (wallets.length === 1) {
    return [accountChip(ctx.refdata, wallet)];
  }
  return [field('Wallet', el('select', {
    onchange: (e) => { wallet = e.target.value; load(); },
  }, ...wallets.map((w) => el('option', { value: w, selected: w === wallet }, w))))];
}

/**
 * The quick-add form. Kind carries the sign, so the money field is a magnitude: spend negates,
 * income keeps, count makes the value the counted balance with a zero amount.
 */
function form() {
  const kind = el('select', {},
    el('option', { value: 'spend' }, 'spend'),
    el('option', { value: 'income' }, 'income'),
    el('option', { value: 'count' }, 'count'));
  const day = el('input', { type: 'date', value: today() });
  const label = el('span', {}, 'Amount');
  const amount = el('input', {
    type: 'number', min: '0', step: '0.01', inputmode: 'decimal', class: 'amount', placeholder: '0.00',
  });
  const description = el('input', { type: 'text', placeholder: 'what was it for?' });
  const category = el('select', {},
    el('option', { value: '' }, '\u2014 none \u2014'),
    ...((ctx.refdata && ctx.refdata.categories) || []).map((c) => el('option', { value: c }, c)));
  const submit = el('button', { type: 'button', class: 'primary' }, 'Record');

  kind.addEventListener('change', () => {
    label.textContent = kind.value === 'count' ? 'Balance' : 'Amount';
  });
  submit.addEventListener('click', () => record({ kind, day, amount, description, category, submit }));

  const node = el('div', { class: 'toolbar' },
    field('Kind', kind), el('label', {}, label, amount), field('Date', day),
    field('Description', description), field('Category', category), submit);
  return { node, kind, day, amount, description, category };
}

async function record(f) {
  const raw = f.amount.value.trim();
  const value = Number(raw);
  if (raw === '' || !Number.isFinite(value)) {
    toast('Enter an amount', 'bad');
    return;
  }
  if (!f.description.value.trim()) {
    toast('Describe the entry', 'bad');
    return;
  }
  const kind = f.kind.value;
  const cents = Math.round(value * 100);
  const body = {
    account: wallet,
    date: f.day.value || today(),
    amount: kind === 'count' ? 0 : (kind === 'spend' ? -cents : cents),
    balance: kind === 'count' ? cents : 0,
    description: f.description.value.trim(),
    // The natural key: minted here so an identical re-observation is suppressed, and the entry is
    // addressable for a later correction (a new fact plus SUPERSEDE, never an edit).
    receipt: 'MAN-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8),
    category: f.category.value || null,
    // The acting user the shell got from /api/whoami (V2-SPEC.md §9.5): with sign-in off the hub has
    // no session, so this declared user signs the PIN; with sign-in on the hub signs the session's
    // and ignores this field.
    user: ctx.user,
  };
  f.submit.disabled = true;
  try {
    const response = await api.cash(body);
    toast('Recorded');
    // The fact is durable; only the category PIN can fail, so say so without hiding the recording.
    if (response && response.pinError) toast(response.pinError, 'bad');
    reset();
    await load();
  } catch (error) {
    reportError(error);
  } finally {
    f.submit.disabled = false;
  }
}

function reset() {
  inputs.day.value = today();
  inputs.amount.value = '';
  inputs.description.value = '';
  inputs.category.value = '';
}

async function load() {
  if (!wallet || !rowsHost) return;
  clear(rowsHost);
  clear(monthsHost);
  rowsHost.append(el('p', { class: 'muted' }, 'Loading\u2026'));
  monthsHost.append(el('p', { class: 'muted' }, 'Loading\u2026'));
  let data;
  let months;
  try {
    // One read each, together: the wallet's ledger rows and its cash_month header. Never two calls
    // to /api/cash/months — the header above and the rows below must speak the same snapshot.
    [data, months] = await Promise.all([
      api.ledger({ account: wallet, sort: 'date', order: 'desc' }),
      api.cashMonths(wallet),
    ]);
  } catch (error) {
    reportError(error);
    clear(rowsHost);
    clear(monthsHost);
    return;
  }
  renderMonths(months);
  clear(rowsHost);
  const rows = (data && data.rows) || [];
  if (!rows.length) {
    rowsHost.append(el('p', { class: 'muted' }, 'No cash facts yet.'));
    return;
  }
  rowsHost.append(scroll(table(rows)));
  // The ledger pages by default; say so rather than let the wallet look shorter than it is.
  if (data.total > rows.length) {
    rowsHost.append(el('p', { class: 'hint muted' },
      `Showing the newest ${rows.length} of ${data.total} rows.`));
  }
}

// ---- the month header (V2-CASH-PLAN.md §4.3, §4.4) --------------------------------------------

/** The unrecorded figure per month, newest first, with its parts. One wallet, so no account column. */
function renderMonths(months) {
  clear(monthsHost);
  const rows = months || [];
  if (!rows.length) {
    monthsHost.append(el('p', { class: 'muted' }, 'No cash months yet.'));
    return;
  }
  const ordered = [...rows].sort((a, b) => (b.month || '').localeCompare(a.month || ''));
  // The open month (asOf is inside it) is the live one; with none open, the newest.
  const featured = ordered.find((m) => m.open) || ordered[0];
  const rest = ordered.filter((m) => m !== featured);
  monthsHost.append(featuredMonth(featured));
  if (rest.length) monthsHost.append(scroll(monthsTable(rest)));
}

/** The live month: the figure carries the weight, the arithmetic sits muted beneath it. */
function featuredMonth(m) {
  return el('section', { class: 'headroom' },
    el('div', {},
      el('span', { class: 'muted' }, `Unrecorded cash ${monthLabel(m.month)} `),
      // Positive is spend the bank saw but no fact records — the gap; negative means more was
      // recorded than the movements imply. Both signs are shown as they are.
      el('b', { class: m.unrecorded > 0 ? 'occ-missed' : 'tick' }, money(m.unrecorded)),
      m.open ? [' ', el('span', { class: 'badge' }, 'open')] : null),
    el('div', { class: 'muted' }, monthParts(m).join(' · ')));
}

function monthParts(m) {
  return [
    `in from bank ${money(m.inFromBank)}`,
    `out to bank ${money(m.outToBank)}`,
    `recorded out ${money(m.recordedOut)}`,
    `recorded in ${money(m.recordedIn)}`,
    `held \u0394 ${money(m.heldDelta)}`,
  ];
}

function monthsTable(rows) {
  const head = el('tr', {},
    el('th', {}, 'Month'),
    el('th', { class: 'amount' }, 'In from bank'),
    el('th', { class: 'amount' }, 'Out to bank'),
    el('th', { class: 'amount' }, 'Recorded out'),
    el('th', { class: 'amount' }, 'Recorded in'),
    el('th', { class: 'amount' }, 'Held \u0394'),
    el('th', { class: 'amount' }, 'Unrecorded'),
    el('th', {}, ''));
  return el('table', {}, el('thead', {}, head), el('tbody', {}, ...rows.map(monthRow)));
}

function monthRow(m) {
  return el('tr', {},
    el('td', {}, m.month),
    el('td', { class: 'amount' }, money(m.inFromBank)),
    el('td', { class: 'amount' }, money(m.outToBank)),
    el('td', { class: 'amount' }, money(m.recordedOut)),
    el('td', { class: 'amount' }, money(m.recordedIn)),
    el('td', { class: 'amount' }, money(m.heldDelta)),
    el('td', { class: 'amount' }, money(m.unrecorded)),
    el('td', {}, m.open ? el('span', { class: 'badge' }, 'open') : ''));
}

/** "2026-10" reads as a month name; UTC so the browser's zone cannot shift the calendar month. */
function monthLabel(month) {
  if (!month) return '';
  const parsed = new Date(month + '-01T00:00:00Z');
  return Number.isNaN(parsed.getTime()) ? month
    : parsed.toLocaleString(undefined, { month: 'long', year: 'numeric', timeZone: 'UTC' });
}

function table(rows) {
  const head = el('tr', {},
    el('th', {}, 'Date'), el('th', {}, 'Description'), el('th', {}, 'Kind'),
    el('th', { class: 'amount' }, 'Amount'), el('th', { class: 'amount' }, 'Balance'),
    el('th', {}, 'Category'));
  return el('table', {}, el('thead', {}, head), el('tbody', {}, ...rows.map(row)));
}

function row(r) {
  const count = r.amount === 0 && !r.synthetic;
  return el('tr', { class: r.synthetic ? 'synthetic' : '' },
    el('td', {}, date(r.date)),
    el('td', { class: 'desc' }, r.rawDescription,
      r.latestNote ? el('span', { class: 'note-chip', title: r.latestNote }, '\u270e ' + r.latestNote) : null),
    el('td', {}, kindTag(r, count)),
    el('td', { class: 'amount' }, money(r.amount)),
    el('td', { class: 'amount' }, count ? money(r.balance) : ''),
    el('td', {}, r.category
      ? el('span', { class: 'tag ' + (r.categoryOrigin || ''), title: categoryTitle(r) }, r.category)
      : el('span', { class: 'muted' }, '\u2014')));
}

/** A count is an attestation of what is in the wallet; a synthetic row is a transfer with the bank. */
function kindTag(r, count) {
  if (r.synthetic) {
    return el('span', { class: 'tag synthetic', title: r.amount < 0 ? 'ATM withdrawal' : 'cash deposit' },
      'transfer');
  }
  if (count) {
    return el('span', { class: 'tag' }, 'count');
  }
  return '';
}

function today() {
  return new Date().toISOString().slice(0, 10);
}
