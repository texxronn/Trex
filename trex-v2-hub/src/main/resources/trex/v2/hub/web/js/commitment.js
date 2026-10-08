// The commitment popups and shared bits (V2-COMMITMENTS-PLAN.md §2.6, §2.8): the declaration
// form (confirm a candidate, re-declare an existing commitment), the retire, note and settle
// dialogs, the ledger chip and the assign-to-commitment gesture. Kept separate so Review,
// Expected, Blotter and Eyeball share one shape for every commitment decision.

import { api } from './api.js';
import { decisions } from './decisions.js';
import { openPrompt, openSelect } from './dialog.js';
import { el } from './dom.js';
import { shortId } from './format.js';
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
 * The declaration form, one shape for a candidate confirm and a re-declare. {@code prefill}:
 * {@code commitmentId}, {@code name}, {@code kind}, {@code direction}, {@code cadence},
 * {@code amountKind}, {@code amount} (cents, magnitude), {@code anchor}, {@code matches}
 * ({@code {match, account}}), {@code fromCandidate} and {@code idLocked}.
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
  const anchorInput = el('input', { type: 'date', value: p.anchor || '' });
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
    row('Anchor', anchorInput),
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
    const anchor = anchorInput.value || null;
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
  const dateInput = el('input', { type: 'date', value: new Date().toISOString().slice(0, 10) });
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
