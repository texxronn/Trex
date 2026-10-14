// Cash mode (V2-CASH-PLAN.md §4.3, V2-SPEC.md §9.3): one declared wallet's rows, newest first, and
// a quick-add form. A spend, an income or a count is a fact; the optional category is a PIN on that
// same fact, in the same request, so the writer stays the only interpreter. A synthetic row is a
// wallet transfer — an ATM withdrawal or a cash deposit — not a manual entry. The month's
// unrecorded figure belongs to cash_month (Stage 4) and is deliberately not fetched here.

import { api } from './api.js';
import { accountChip } from './account.js';
import { el, clear, field, scroll } from './dom.js';
import { date, money } from './format.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let wallet = '';
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
  rowsHost = el('div');
  inputs = form();
  host.append(
    el('div', { class: 'toolbar' }, ...walletControl(wallets)),
    inputs.node,
    el('p', { class: 'hint muted' },
      'This shows the wallet\u2019s facts and transfers. The month\u2019s unrecorded figure arrives '
      + 'with the cash_month stage.'),
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
  rowsHost.append(el('p', { class: 'muted' }, 'Loading\u2026'));
  let data;
  try {
    data = await api.ledger({ account: wallet, sort: 'date', order: 'desc' });
  } catch (error) {
    reportError(error);
    clear(rowsHost);
    return;
  }
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
      ? el('span', { class: 'tag ' + (r.categoryOrigin || ''), title: r.ruleId || '' }, r.category)
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
