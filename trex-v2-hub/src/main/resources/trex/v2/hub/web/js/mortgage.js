// Mortgage mode (V2-SPEC.md §9.3, §12): one declared facility's offset and loans, composed from the
// existing reads — /api/refdata (the facility mapping), /api/reconcile (balances, opening/closing),
// /api/accounts (each account's own statement date) and /api/commitments/activity (the repayments
// and interest a commitment claims). Nothing is derived and no endpoint is added.
//
// Facts and estimates are kept apart: balances, movements and the residual are facts; "principal"
// is an estimate from recorded movements and its residual is shown, never folded in; the idle
// offset's cost is an estimate and states its assumptions. Balances with different statement dates
// are never netted into one headline.

import { api } from './api.js';
import { accountChip } from './account.js';
import { el, clear, scroll } from './dom.js';
import { date, money } from './format.js';
import { direction } from './direction.js';
import { reportError } from './toast.js';

let host;
let ctx;
let data = null;             // the composed facilities
let loadGeneration = 0;      // supersedes an in-flight load when a newer one starts
let errorBar;

export function mount(container, context) {
  host = container;
  ctx = context;
  render();
  load();
  return { refresh: load };
}

async function load() {
  const generation = ++loadGeneration;
  try {
    const [refdata, reconcile, accounts] = await Promise.all([
      api.refdata(), api.reconcile(), api.accounts({ window: 'all' }),
    ]);
    const facilities = (refdata && refdata.mortgages) || [];
    const ids = new Set();
    for (const f of facilities) {
      for (const l of f.loans || []) {
        if (l.repaymentCommitment) ids.add(l.repaymentCommitment);
        if (l.interestCommitment) ids.add(l.interestCommitment);
      }
    }
    // Each commitment is one read; a missing or retired one answers empty rather than failing the
    // whole view.
    const activities = new Map();
    await Promise.all([...ids].map(async (id) => {
      try {
        activities.set(id, await api.activity(id));
      } catch {
        activities.set(id, []);
      }
    }));
    if (generation !== loadGeneration) return; // superseded: a newer load owns the view
    data = compose(facilities, reconcile, accounts, activities);
    errorBar.hidden = true;
  } catch (error) {
    if (generation !== loadGeneration) return;
    errorBar.textContent = error.message || 'failed to load the mortgage view';
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
  if (!data.length) {
    host.append(el('p', { class: 'muted' },
      'No mortgage facility is declared. Add one to mortgages.yaml (V2-SPEC.md §12).'));
    return;
  }
  for (const facility of data) host.append(facilityView(facility));
}

// ---- the composed model -------------------------------------------------------------------------

function compose(facilities, reconcile, accounts, activities) {
  const byRef = new Map((reconcile.accounts || []).map((a) => [a.accountRef, a]));
  const lastByRef = new Map((accounts.accounts || []).map((a) => [a.ref, a.last]));
  return facilities.map((f) => {
    const offsetRecon = byRef.get(f.offset);
    const offset = Math.max(0, offsetRecon ? offsetRecon.closing : 0);
    const loans = (f.loans || []).map((l) => {
      const r = byRef.get(l.account);
      const repaid = series(activities.get(l.repaymentCommitment));
      const interest = series(activities.get(l.interestCommitment));
      // Principal is repayments less recorded interest: an estimate, not an observed movement.
      const principal = repaid.total - interest.total;
      const change = r ? r.closing - r.opening : null;
      return {
        ...l,
        balance: r ? r.closing : null,
        balanceDate: lastByRef.get(l.account) || null,
        change,
        reconciled: r ? r.status === 'reconciled' : false,
        repayments: repaid,
        interest,
        principal,
        residual: change === null ? null : change - principal,
      };
    });
    const debt = loans.reduce((n, l) => n + Math.abs(l.balance || 0), 0);
    return {
      ...f,
      offsetBalance: offsetRecon ? offsetRecon.closing : null,
      offsetDate: lastByRef.get(f.offset) || null,
      loans,
      debt,
      offset,
      working: Math.min(offset, debt),
      idle: Math.max(0, offset - debt),
    };
  });
}

/** One activity series: unsigned totals (magnitudes), the span, and the newest row for the estimate. */
function series(rows) {
  const current = (rows || []).filter((a) => !a.excluded);
  const total = current.reduce((n, a) => n + Math.abs(a.amount || 0), 0);
  const newest = current.length ? current[current.length - 1] : null;
  return {
    rows: current,
    count: current.length,
    total,
    first: current.length ? current[0].date : null,
    last: newest ? newest.date : null,
    lastAmount: newest ? Math.abs(newest.amount || 0) : null,
  };
}

// ---- the facility -------------------------------------------------------------------------------

function facilityView(f) {
  return el('div', { class: 'mortgage' },
    header(f),
    liquidity(f),
    seriesSection(f, 'repayments'),
    seriesSection(f, 'interest'),
    principal(f),
    idle(f));
}

function header(f) {
  const state = f.loans.every((l) => l.state === 'closed') ? 'closed' : 'open';
  const bits = [`offset ${f.offset}`, `${f.loans.length} loan${f.loans.length === 1 ? '' : 's'}`];
  if (f.observedRatePct != null) bits.push(`observed rate ${f.observedRatePct}%`);
  return el('section', { class: 'headroom' },
    el('div', {}, el('b', {}, f.name), ' ', stateTag(state)),
    el('div', { class: 'muted' }, bits.join(' · ')),
    el('div', { class: 'hint muted' },
      'A declared facility (mortgages.yaml): the offset→loan link is a declaration, not an inference.'));
}

function stateTag(state) {
  return el('span', { class: 'tag ' + state }, state);
}

// ---- 1. liquidity -------------------------------------------------------------------------------

function liquidity(f) {
  const head = ['Liquidity', el('span', { class: 'muted' },
    ` · offset ${money(f.offsetBalance)}${f.offsetDate ? ` (${date(f.offsetDate)})` : ''}`)];
  const rows = [
    liqRow('offset', f.offset, f.offsetBalance, f.offsetDate),
    ...f.loans.map((l) => liqRow('loan', l.account, l.balance, l.balanceDate)),
  ];
  return fold('liquidity:' + f.name, true, head, [
    el('p', { class: 'hint muted' },
      'Each balance is as at its own statement date (the account\'s newest statement row). The '
      + 'offset and the loans are never netted into one headline figure.'),
    scroll(el('table', {}, el('thead', {}, el('tr', {},
      el('th', {}, 'Account'), el('th', {}, 'Role'), el('th', { class: 'amount' }, 'Balance'),
      el('th', {}, 'As at'))), el('tbody', {}, ...rows))),
  ]);
}

function liqRow(role, ref, balance, at) {
  return el('tr', {},
    el('td', {}, accountChip(ctx.refdata, ref)),
    el('td', { class: 'muted' }, role),
    el('td', { class: 'amount' }, balance === null || balance === undefined ? '\u2014' : money(balance)),
    el('td', { class: 'muted' }, at ? date(at) : '\u2014'));
}

// ---- 2 & 3. repayments and interest -------------------------------------------------------------

function seriesSection(f, kind) {
  const title = kind === 'repayments' ? 'Repayments' : 'Interest';
  const count = f.loans.reduce((n, l) => n + l[kind].count, 0);
  const total = f.loans.reduce((n, l) => n + l[kind].total, 0);
  const head = [title, el('span', { class: 'muted' }, ` · ${count} recorded · ${money(total)}`)];
  const body = [el('p', { class: 'hint muted' }, kind === 'repayments'
    ? 'The offset debits the declared repayment commitment claims, per loan.'
    : 'The loan-side charges the declared interest commitment claims, per loan. This is a charge '
      + 'history — a fact — not a rate, and no rate is inferred from it.')];
  for (const l of f.loans) body.push(seriesBlock(l, kind));
  return fold(kind + ':' + f.name, false, head, body);
}

function seriesBlock(l, kind) {
  const s = l[kind];
  const span = s.first ? `${date(s.first)} → ${date(s.last)}` : '';
  const head = el('h4', {}, loanName(l), ' ', el('span', { class: 'muted' },
    s.count ? `${s.count} · ${span} · ${money(s.total)}` : 'no recorded movements'));
  if (!s.count) {
    return el('div', { class: 'mode-section' }, head, el('p', { class: 'muted' }, 'No recorded movements.'));
  }
  const rows = [...s.rows].reverse(); // newest first for reading
  return el('div', { class: 'mode-section' }, head,
    scroll(el('table', {}, el('thead', {}, el('tr', {},
      el('th', {}, 'Date'), el('th', {}, 'Description'), el('th', {}, 'Direction'),
      el('th', { class: 'amount' }, 'Amount'))), el('tbody', {}, ...rows.map(activityRow)))));
}

function activityRow(a) {
  return el('tr', {},
    el('td', {}, date(a.date)),
    el('td', { class: 'desc' }, a.rawDescription || ''),
    el('td', {}, direction(a.amount)),
    el('td', { class: 'amount' }, money(Math.abs(a.amount || 0))));
}

function loanName(l) {
  return l.label || l.account;
}

// ---- 4. principal -------------------------------------------------------------------------------

function principal(f) {
  const head = ['Principal', el('span', { class: 'muted' }, ' · estimate from recorded movements')];
  const body = [el('p', { class: 'hint muted' },
    'Principal = repayments − recorded interest: an estimate from recorded movements, not an '
    + 'observed transaction. The balance change is closing − opening over the recorded statements. '
    + 'Any residual between them is shown, never folded into principal.')];
  const rows = f.loans.map((l) => el('tr', {},
    el('td', {}, loanName(l)),
    el('td', { class: 'amount' }, money(l.repayments.total)),
    el('td', { class: 'amount' }, money(l.interest.total)),
    el('td', { class: 'amount' }, el('span', { class: 'estimate' }, money(l.principal))),
    el('td', { class: 'amount' }, l.change === null ? '\u2014' : money(l.change)),
    el('td', { class: 'amount' }, residualCell(l)),
    el('td', { class: 'muted' }, l.reconciled ? '' : el('span', { class: 'occ-partial' }, 'not reconciled'))));
  body.push(scroll(el('table', {}, el('thead', {}, el('tr', {},
    el('th', {}, 'Loan'), el('th', { class: 'amount' }, 'Repayments'),
    el('th', { class: 'amount' }, 'Interest'),
    el('th', { class: 'amount' }, 'Principal\u00a0(est)'),
    el('th', { class: 'amount' }, 'Balance change'),
    el('th', { class: 'amount' }, 'Residual'), el('th', {}, ''))), el('tbody', {}, ...rows))));
  return fold('principal:' + f.name, true, head, body);
}

function residualCell(l) {
  if (l.residual === null) {
    return '\u2014';
  }
  if (l.residual === 0) {
    return el('span', { class: 'tick' }, money(0));
  }
  return el('span', { class: 'occ-partial', title: 'balance change − principal (an unexplained difference)' },
    money(l.residual));
}

// ---- 5. idle-offset insight ---------------------------------------------------------------------

function idle(f) {
  const head = ['Offset vs loans', el('span', { class: 'muted' },
    ` · working ${money(f.working)} · idle ${money(f.idle)}`)];
  return fold('idle:' + f.name, true, head, [
    el('div', {},
      `The offset holds ${money(f.offsetBalance)} against ${money(f.debt)} of declared loans: `,
      el('b', {}, `${money(f.working)} working`), ', ',
      el('b', { class: f.idle ? 'occ-partial' : 'tick' }, `${money(f.idle)} idle`), '.'),
    costLine(f),
    el('p', { class: 'hint muted' },
      'Estimate only: it assumes the idle balance would offset the loan 1:1 and that the rate (or '
      + 'the last charge) holds. A contractual rate history, a payoff trajectory and property equity '
      + 'are not modelled.'),
  ]);
}

function costLine(f) {
  if (!f.idle) {
    return el('p', { class: 'muted' }, 'The whole offset is working, so no idle cost is estimated.');
  }
  if (f.observedRatePct != null) {
    const perYear = Math.round(f.idle * f.observedRatePct / 100);
    return el('p', {}, el('b', {}, `≈ ${money(perYear)} a year`),
      el('span', { class: 'muted' }, ` not saved (at the declared ${f.observedRatePct}%) — an estimate`));
  }
  const lastCharges = f.loans.reduce((n, l) => n + (l.interest.lastAmount || 0), 0);
  const lastDate = f.loans.map((l) => l.interest.last).filter(Boolean).sort().pop();
  if (f.debt > 0 && lastCharges > 0) {
    const perCharge = Math.round(f.idle * lastCharges / f.debt);
    return el('p', {}, el('b', {}, `≈ ${money(perCharge)} each charge period`),
      el('span', { class: 'muted' },
        ` not saved (at the latest recorded charge ${money(lastCharges)}`
        + (lastDate ? ` on ${date(lastDate)}` : '') + ') — an estimate'));
  }
  return el('p', { class: 'muted' },
    'No observed rate is declared and no interest charge is on record, so the idle offset\'s cost '
    + 'is not estimated.');
}

// ---- helpers ------------------------------------------------------------------------------------

/** Foldable section: the summary carries the live figures, so a collapsed section still speaks. */
function fold(key, open, head, body) {
  return el('details', { class: 'mode-section x-section', open },
    el('summary', {}, ...head), el('div', { class: 'x-body' }, ...body));
}
