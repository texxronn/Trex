// The commitment popups and shared bits (V2-COMMITMENTS-PLAN.md §2.6, §2.8): the declaration
// form (confirm a candidate, re-declare an existing commitment), the retire, note and settle
// dialogs, the ledger chip and the assign-to-commitment gesture. Kept separate so Review,
// Expected, Blotter and Eyeball share one shape for every commitment decision.

import { api } from './api.js';
import { decisions } from './decisions.js';
import { openChoice, openPrompt, openSelect } from './dialog.js';
import { el, clear, svgEl } from './dom.js';
import { money, shortId } from './format.js';
import { reportError, toast } from './toast.js';

export const CADENCES = ['weekly', 'fortnightly', 'monthly', 'bimonthly', 'quarterly',
  'semiannual', 'annual', 'irregular'];
export const AMOUNT_KINDS = ['fixed', 'variable', 'range'];
export const COMMITMENT_KINDS = ['subscription', 'bill', 'insurance', 'fee', 'tax', 'income',
  'other'];

/** The chip on a matched ledger row; links to the Expected view. */
export function commitmentChip(row) {
  if (!row.commitmentId) {
    return el('span', {});
  }
  return el('a', { class: 'commit-chip', href: '#expected',
    title: 'Matched commitment ' + row.commitmentId }, row.commitmentName || shortId(row.commitmentId));
}

/**
 * The declaration form, one shape for a hand declaration, a candidate confirm and a re-declare.
 * {@code prefill}: {@code commitmentId}, {@code name}, {@code kind}, {@code direction},
 * {@code cadence}, {@code amountKind}, {@code amount} (cents, magnitude), {@code anchor},
 * {@code matches} ({@code {match, account}}), {@code fromCandidate} and {@code idLocked}. An
 * empty prefill is the declare-by-hand path: the id follows the name until it is edited, a regular
 * cadence defaults its anchor to today, and {@code irregular} hides the anchor (never sent).
 */
export function openDeclare(ctx, prefill, onDone) {
  const p = prefill || {};
  const overlay = el('div', { class: 'modal', onclick: (e) => { if (e.target === overlay) close(); } });
  const idInput = el('input', { type: 'text', value: p.commitmentId || '', readonly: p.idLocked });
  const nameInput = el('input', { type: 'text', value: p.name || '' });
  const kindSelect = selectOf(COMMITMENT_KINDS, p.kind || 'other');
  const directionSelect = selectOf(['out', 'in'], p.direction || 'out');
  const cadenceSelect = selectOf(CADENCES, p.cadence || 'monthly');
  const amountKindSelect = selectOf(AMOUNT_KINDS, p.amountKind || 'fixed');
  const amountInput = el('input', { type: 'number', step: '0.01', min: '0',
    value: p.amount == null ? '' : (p.amount / 100).toFixed(2) });
  const regular = cadenceSelect.value !== 'irregular';
  const anchorInput = el('input', { type: 'date', value: p.anchor || (regular ? todayIso() : '') });
  const anchorRow = row('Anchor', anchorInput);
  const rulesInput = el('textarea', { rows: '3',
    placeholder: 'one regex per line; append "@ account" to scope it' }, formatRules(p.matches));
  const commentInput = el('input', { type: 'text', value: p.comment || '' });
  const dialog = el('div', { class: 'dialog wide', role: 'dialog', 'aria-label': 'Commitment' },
    el('h3', {}, p.idLocked ? 'Re-declare commitment' : 'Declare commitment'),
    p.summary ? el('p', { class: 'muted' }, p.summary) : null,
    row('Id', idInput),
    row('Name', nameInput),
    row('Kind', kindSelect),
    row('Direction', directionSelect),
    row('Cadence', cadenceSelect),
    row('Amount kind', amountKindSelect),
    row('Amount ($)', amountInput),
    anchorRow,
    row('Rules', rulesInput),
    row('Comment', commentInput),
    el('div', { class: 'actions' },
      el('button', { type: 'button', onclick: close }, 'Cancel'),
      el('button', { type: 'button', class: 'primary', onclick: submit },
        p.idLocked ? 'Re-declare' : 'Declare')));
  overlay.append(dialog);
  document.body.append(overlay);
  nameInput.focus();
  document.addEventListener('keydown', onKey);

  // The id follows the name until the person edits the id (or it is a locked re-declare).
  let idTouched = false;
  idInput.addEventListener('input', () => { idTouched = true; });
  nameInput.addEventListener('input', () => {
    if (!p.idLocked && !idTouched) {
      idInput.value = nameInput.value.trim() ? slugify(nameInput.value) : '';
    }
  });

  // An irregular commitment predicts nothing, so it carries no anchor (§2.1, §2.5).
  cadenceSelect.addEventListener('change', syncAnchor);
  syncAnchor();

  function syncAnchor() {
    const isRegular = cadenceSelect.value !== 'irregular';
    anchorRow.hidden = !isRegular;
    if (isRegular && !anchorInput.value) {
      anchorInput.value = todayIso();
    }
  }

  function close() {
    overlay.remove();
    document.removeEventListener('keydown', onKey);
  }

  function onKey(e) {
    if (e.key === 'Escape') close();
  }

  async function submit() {
    const commitmentId = idInput.value.trim();
    const name = nameInput.value.trim();
    const matches = parseRules(rulesInput.value);
    const cadence = cadenceSelect.value;
    const anchor = cadence === 'irregular' ? null : (anchorInput.value || null);
    if (!/^[a-z0-9-]{1,64}$/.test(commitmentId)) {
      toast('Id must be a slug of a-z, 0-9 and "-", max 64 chars', 'bad');
      return;
    }
    if (!name) {
      toast('A name is required', 'bad');
      return;
    }
    if (!matches.length) {
      toast('At least one match rule is required', 'bad');
      return;
    }
    if (cadence !== 'irregular' && !anchor) {
      toast('A regular cadence needs an anchor date', 'bad');
      return;
    }
    const amount = amountInput.value.trim() === '' ? null : Math.round(Number(amountInput.value) * 100);
    if (amount !== null && !Number.isFinite(amount)) {
      toast('Amount must be a number', 'bad');
      return;
    }
    close();
    try {
      await api.decisions(ctx.n, [decisions.declareCommitment(ctx, {
        commitmentId, name, direction: directionSelect.value, cadence,
        amountKind: amountKindSelect.value, kind: kindSelect.value, matches, amount, anchor,
        fromCandidate: p.fromCandidate || null, comment: commentInput.value.trim() || null,
      })]);
      toast(p.idLocked ? 'Re-declared' : 'Declared');
    } catch (error) {
      reportError(error);
    }
    if (onDone) onDone();
  }
}

/** Retire a commitment: an end date (today by default) and the reason that goes on the record. */
export function openRetire(ctx, target, onDone) {
  const overlay = el('div', { class: 'modal', onclick: (e) => { if (e.target === overlay) close(); } });
  const dateInput = el('input', { type: 'date', value: todayIso() });
  const reasonInput = el('input', { type: 'text', placeholder: 'cancelled, past, provider move…' });
  const dialog = el('div', { class: 'dialog', role: 'dialog', 'aria-label': 'Retire' },
    el('h3', {}, 'Retire commitment'),
    el('p', { class: 'muted' }, target.name ? `${target.name} (${target.commitmentId})` : target.commitmentId),
    target.summary ? el('p', { class: 'muted' }, target.summary) : null,
    row('Ended', dateInput),
    row('Reason', reasonInput),
    el('div', { class: 'actions' },
      el('button', { type: 'button', onclick: close }, 'Cancel'),
      el('button', { type: 'button', class: 'warn', onclick: submit }, 'Retire')));
  overlay.append(dialog);
  document.body.append(overlay);
  reasonInput.focus();
  document.addEventListener('keydown', onKey);

  function close() {
    overlay.remove();
    document.removeEventListener('keydown', onKey);
  }

  function onKey(e) {
    if (e.key === 'Escape') close();
  }

  async function submit() {
    const reason = reasonInput.value.trim();
    if (!dateInput.value) {
      toast('An end date is required', 'bad');
      return;
    }
    if (!reason) {
      toast('A reason is required', 'bad');
      return;
    }
    close();
    try {
      await api.decisions(ctx.n, [decisions.retireCommitment(ctx, target.commitmentId,
        dateInput.value, reason)]);
      toast('Retired');
    } catch (error) {
      reportError(error);
    }
    if (onDone) onDone();
  }
}

/** A free note on the commitment's own thread (§2.6 NOTE_COMMITMENT). */
export function openCommitmentNote(ctx, target, onDone) {
  openPrompt({
    title: 'Note',
    summary: target.name ? `${target.name} (${target.commitmentId})` : target.commitmentId,
    label: 'Note',
    placeholder: 'Add a note…',
    multiline: true,
    confirm: 'Save',
  }, async (text) => {
    if (!text) {
      toast('A note needs some text', 'bad');
      return;
    }
    try {
      await api.decisions(ctx.n, [decisions.noteCommitment(ctx, target.commitmentId, text)]);
      toast('Note saved');
    } catch (error) {
      reportError(error);
    }
    if (onDone) onDone();
  });
}

/**
 * Settle occurrences off-journal (§2.9): a person concluded the money moved with no fact to show.
 * The caller names the due dates; this is a conclusion, never a fabricated amount.
 */
export function openSettle(ctx, target, onDone) {
  const dates = target.dueDates || [];
  if (!dates.length) {
    toast('Nothing in arrears to settle', 'bad');
    return;
  }
  const label = target.name ? `${target.name} (${target.commitmentId})` : target.commitmentId;
  openPrompt({
    title: 'Settle occurrences',
    summary: `${label}: ${dates.length} occurrence${dates.length === 1 ? '' : 's'} — ${dates.join(', ')}`,
    label: 'Comment (optional)',
    placeholder: 'paid in cash',
    confirm: 'Settle',
  }, async (comment) => {
    try {
      await api.decisions(ctx.n, [decisions.settleOccurrence(ctx, target.commitmentId, dates,
        comment || null)]);
      toast('Settled');
    } catch (error) {
      reportError(error);
    }
    if (onDone) onDone();
  });
}

/**
 * Assign a ledger row to a commitment (PIN_COMMITMENT, the category PIN gesture), or release it
 * when it already carries a chip (UNPIN_COMMITMENT). The registry is fetched on demand so the
 * dense modes do not pay for it up front.
 */
export async function openAssignCommitment(ctx, row, onDone) {
  if (row.commitmentId) {
    try {
      await api.decisions(ctx.n, [decisions.unpinCommitment(ctx, [row.externalId],
        'unassigned in the hub')]);
      toast('Unassigned');
    } catch (error) {
      reportError(error);
    }
    if (onDone) onDone();
    return;
  }
  let registry;
  try {
    registry = await api.commitments();
  } catch (error) {
    reportError(error);
    return;
  }
  const choices = registry
    .filter((c) => c.origin === 'declared' && c.retiredN == null)
    .map((c) => ({ value: c.commitmentId, label: c.name || c.commitmentId }));
  if (!choices.length) {
    toast('No declared commitments to assign to', 'bad');
    return;
  }
  openSelect({
    title: 'Assign to commitment',
    summary: row.rawDescription,
    label: 'Commitment',
    choices,
    confirm: 'Assign',
  }, async (id) => {
    try {
      await api.decisions(ctx.n, [decisions.pinCommitment(ctx, id, [row.externalId],
        'assigned in the hub')]);
      toast('Assigned');
    } catch (error) {
      reportError(error);
    }
    if (onDone) onDone();
  });
}

// ---- detected candidates (Review and the Expected registry share these) ------------------------

/**
 * The candidate's declaration prefill: the stem is the literal rule, the name and id derive from
 * it, and the caller supplies the cadence, amount and anchor. Review and the registry both call
 * this, so confirming from either place posts the same `DECLARE_COMMITMENT` shape (§2.8).
 */
export function confirmCandidate(ctx, candidate, onDone) {
  const current = candidate.currentAmount != null ? candidate.currentAmount : 0;
  openDeclare(ctx, {
    commitmentId: slugify(candidate.stem),
    name: titleCase(candidate.stem),
    kind: 'other',
    direction: current < 0 ? 'out' : 'in',
    cadence: candidate.cadence || 'monthly',
    amountKind: 'fixed',
    amount: candidate.currentAmount != null ? Math.abs(candidate.currentAmount) : null,
    anchor: candidate.firstDate || '',
    matches: [{ match: escapeRegex(candidate.stem), account: null }],
    fromCandidate: candidate.stem,
    summary: candidate.detail || null,
  }, onDone);
}

/** Ignore is the semantic (it does not reopen on the next fact), and it carries a reason. */
export function ignoreCandidate(ctx, candidate, onDone) {
  openPrompt({
    title: 'Ignore recurring',
    summary: candidate.stem,
    label: 'Reason',
    placeholder: 'why this series is not a commitment',
    confirm: 'Ignore',
  }, async (reason) => {
    if (!reason) {
      toast('A reason is required', 'bad');
      return;
    }
    try {
      await api.decisions(ctx.n, [decisions.ignoreRecurring(ctx, candidate.stem, reason)]);
      toast('Ignored');
    } catch (error) {
      reportError(error);
    }
    if (onDone) onDone();
  });
}

/**
 * A commitment's menu (V2-EXPECTED-UX-PLAN.md §7 Stage 2): the activity table and a rudimentary
 * price timeseries, with the curation actions below — Review/Confirm/Ignore for a candidate,
 * Re-declare/Retire/Note/Settle for a declared commitment. The activity loads on open, by id.
 */
export function openCommitmentActions(ctx, c, onDone) {
  const txPane = el('div', { class: 'tx-pane' }, el('p', { class: 'muted' }, 'Loading…'));
  const chartPane = el('div', { class: 'chart-pane' }, el('p', { class: 'muted' }, 'Loading…'));
  openChoice({
    title: c.name || c.stem || shortId(c.commitmentId),
    summary: rowDetail(c),
    body: el('div', { class: 'popup-split' },
      el('div', { class: 'popup-pane' }, el('h4', {}, 'Transactions'), txPane),
      el('div', { class: 'popup-pane' }, el('h4', {}, 'Price'), chartPane)),
    xwide: true,
  }, c.origin === 'detected' ? candidateChoices(ctx, c, onDone) : declaredChoices(ctx, c, onDone));
  loadActivity(c, txPane, chartPane);
}

/** The registry row's faces as one line for the menu's subtitle. */
function rowDetail(c) {
  const bits = [c.cadence];
  if (c.origin === 'detected' && c.occurrenceCount) {
    bits.push(`${c.occurrenceCount}\u00d7`);
  }
  if (c.origin === 'detected' && c.firstDate) {
    bits.push(c.lastDate ? `${c.firstDate} \u2192 ${c.lastDate}` : c.firstDate);
  }
  if (c.currentAmount != null) {
    bits.push(`last ${money(c.currentAmount)}`
      + (c.previousAmount != null ? ` (was ${money(c.previousAmount)})` : ''));
  }
  if (c.origin === 'detected' && c.regularity != null) {
    bits.push(`regularity ${c.regularity.toFixed(2)}`);
  }
  return bits.join(' \u00b7 ');
}

/** A candidate's actions: confirm it here, hand it to Review, or ignore it for good. */
function candidateChoices(ctx, c, onDone) {
  const candidate = { stem: c.stem, cadence: c.cadence, currentAmount: c.currentAmount,
    firstDate: c.firstDate, detail: rowDetail(c) };
  return [
    { label: 'Review', onPick: () => { location.hash = '#review?kind=SUSPECTED_RECURRING'; } },
    { label: 'Ignore…', class: 'warn', onPick: () => ignoreCandidate(ctx, candidate, onDone) },
    { label: 'Confirm…', class: 'primary', onPick: () => confirmCandidate(ctx, candidate, onDone) },
  ];
}

/** A declared commitment's actions: the row's curation gestures, in the menu. */
function declaredChoices(ctx, c, onDone) {
  const choices = [
    { label: 'Re-declare', class: 'ghost',
      onPick: () => openDeclare(ctx, reDeclarePrefill(c), onDone) },
  ];
  if (c.retiredN == null) {
    choices.push({ label: 'Retire', class: 'warn',
      onPick: () => openRetire(ctx, { commitmentId: c.commitmentId, name: c.name }, onDone) });
  }
  choices.push({ label: 'Note', class: 'ghost',
    onPick: () => openCommitmentNote(ctx, { commitmentId: c.commitmentId, name: c.name }, onDone) });
  if (c.arrearsCount) {
    choices.push({ label: 'Settle', class: 'primary',
      onPick: () => settleBacklog(ctx, c, onDone) });
  }
  return choices;
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

/** Settle the whole backlog: the same gesture as Review and the Catch up panel. */
async function settleBacklog(ctx, c, onDone) {
  let expected;
  try {
    expected = await api.expected('today');
  } catch (error) {
    reportError(error);
    return;
  }
  const dueDates = [...new Set((expected.arrears || [])
    .filter((a) => a.commitmentId === c.commitmentId)
    .map((a) => a.dueDate))];
  if (!dueDates.length) {
    toast('Nothing in arrears to settle', 'bad');
    return;
  }
  openSettle(ctx, { commitmentId: c.commitmentId, name: c.name, dueDates }, onDone);
}

/** The commitment's activity, by id: a candidate's facts, or a declared commitment's periods. */
async function loadActivity(c, txPane, chartPane) {
  let rows;
  try {
    rows = await api.activity(c.commitmentId);
  } catch (error) {
    clear(txPane);
    clear(chartPane);
    const message = error.message || 'failed to load activity';
    txPane.append(el('p', { class: 'error' }, message));
    chartPane.append(el('p', { class: 'muted' }, '\u2014'));
    return;
  }
  clear(txPane);
  clear(chartPane);
  if (!rows.length) {
    txPane.append(el('p', { class: 'muted' }, 'No activity.'));
    chartPane.append(el('p', { class: 'muted' }, 'No price points.'));
    return;
  }
  txPane.append(activityTable(rows));
  chartPane.append(priceChart(rows));
}

/**
 * The activity as a compact table: date, account, signed amount, description; a Status column
 * appears when the rows carry one (a declared commitment's occurrences).
 */
function activityTable(rows) {
  const withStatus = rows.some((r) => r.status);
  return el('table', { class: 'tx-table' },
    el('thead', {}, el('tr', {},
      el('th', {}, 'Date'),
      withStatus ? el('th', {}, 'Status') : null,
      el('th', {}, 'Account'),
      el('th', { class: 'amount' }, 'Amount'),
      el('th', {}, 'Description'))),
    el('tbody', {}, ...rows.map((r) => el('tr', {},
      el('td', { class: 'tx-date' }, r.date),
      withStatus ? el('td', {}, statusLabel(r.status)) : null,
      el('td', { class: 'tx-account muted', title: r.accountRef || '' }, r.accountRef || '\u2014'),
      el('td', { class: 'amount ' + (r.amount == null ? '' : r.amount < 0 ? 'tx-out' : 'tx-in') },
        r.amount == null ? '\u2014' : money(r.amount)),
      el('td', { class: 'tx-desc muted', title: r.rawDescription || '' },
        r.rawDescription || '')))));
}

/** The occurrence states in the activity table, the same reading as the Expected view. */
function statusLabel(status) {
  switch (status) {
    case 'occurred': return el('span', { class: 'tick' }, '\u2713 occurred');
    case 'settled': return el('span', { class: 'occ-settled' }, '\u25c6 settled');
    case 'missed': return el('span', { class: 'occ-missed' }, '\u2717 missed');
    case 'due': return el('span', { class: 'muted' }, 'due');
    default: return el('span', { class: 'muted' }, status || '');
  }
}

/**
 * A rudimentary price timeseries: same-day movements sum (detection's collapse), magnitudes plot
 * against a zero baseline, and each point carries its signed value on hover.
 */
function priceChart(rows) {
  const byDate = new Map();
  for (const r of rows) {
    if (r.amount == null) {
      continue;
    }
    byDate.set(r.date, (byDate.get(r.date) || 0) + r.amount);
  }
  const points = [...byDate.entries()]
    .map(([date, amount]) => ({ date, amount }))
    .sort((a, b) => a.date.localeCompare(b.date));
  if (!points.length) {
    return el('p', { class: 'muted' }, 'No price points.');
  }
  const W = 360, H = 240, pad = 30;
  const at = (date) => Date.parse(date + 'T00:00:00Z');
  const minT = at(points[0].date);
  const maxT = at(points[points.length - 1].date);
  const maxV = Math.max(...points.map((p) => Math.abs(p.amount))) * 1.1 || 1;
  const x = (date) => minT === maxT ? W / 2
    : pad + ((at(date) - minT) / (maxT - minT)) * (W - 2 * pad);
  const y = (amount) => H - pad - (Math.abs(amount) / maxV) * (H - 2 * pad);
  return el('div', {},
    svgEl('svg', { class: 'price-chart', viewBox: `0 0 ${W} ${H}`,
        preserveAspectRatio: 'xMidYMid meet', role: 'img', 'aria-label': 'Price over time' },
      svgEl('line', { class: 'axis', x1: pad, y1: H - pad, x2: W - pad, y2: H - pad }),
      svgEl('line', { class: 'axis', x1: pad, y1: pad, x2: pad, y2: H - pad }),
      svgEl('text', { class: 'lbl', x: pad, y: pad - 10 }, money(Math.round(maxV))),
      svgEl('text', { class: 'lbl', x: pad, y: H - pad + 14 }, points[0].date),
      svgEl('text', { class: 'lbl end', x: W - pad, y: H - pad + 14 }, points[points.length - 1].date),
      points.length > 1
        ? svgEl('polyline', { class: 'line',
            points: points.map((p) => `${x(p.date).toFixed(1)},${y(p.amount).toFixed(1)}`).join(' ') })
        : null,
      ...(points.length <= 80 ? points.map((p) =>
        svgEl('circle', { class: 'dot', cx: x(p.date), cy: y(p.amount), r: 3 },
          svgEl('title', {}, `${p.date} \u00b7 ${money(p.amount)}`))) : [])),
    el('div', { class: 'muted chart-note' },
      `${points.length} price point${points.length === 1 ? '' : 's'} \u00b7 same-day movements summed`));
}

// ---- shared transformations -------------------------------------------------------------------

/** A default name from a candidate stem: "GYM MEMBERSHIP" → "Gym Membership". */
export function titleCase(stem) {
  return String(stem || '').toLowerCase().replace(/(^|\s)\S/g, (c) => c.toUpperCase());
}

/** A default declaration id from a stem; the writer's slug shape. */
export function slugify(stem) {
  return String(stem || '').toLowerCase().replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '').slice(0, 64) || 'commitment';
}

/** Regex-escape a stem so it matches as one literal rule. */
export function escapeRegex(text) {
  return String(text).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// ---- dialog bits ------------------------------------------------------------------------------

/** Today in ISO form, the dialog's default date. */
function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function selectOf(options, value) {
  return el('select', {}, ...options.map((o) =>
    el('option', { value: o, selected: o === value }, o)));
}

function row(label, control) {
  return el('label', { class: 'field' }, label, control);
}

/** Rules render one per line; a trailing "@ account" scopes the regex (§2.2). */
function formatRules(matches) {
  return (matches || []).map((m) => (m.account ? `${m.match} @ ${m.account}` : m.match)).join('\n');
}

function parseRules(text) {
  const out = [];
  for (const raw of String(text || '').split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    const at = line.lastIndexOf(' @ ');
    if (at > 0) {
      out.push({ match: line.slice(0, at).trim(), account: line.slice(at + 3).trim() || null });
    } else {
      out.push({ match: line, account: null });
    }
  }
  return out;
}
