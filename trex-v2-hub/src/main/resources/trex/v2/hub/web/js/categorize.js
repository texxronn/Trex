// The category popup: one dropdown, applied as a PIN decision. Underlying action is
// PIN(user, [id], category); the hub forwards it to the sequencer and the SSE delta refreshes the
// view. Kept separate so any mode can pop it over a row.

import { api } from './api.js';
import { decisions } from './decisions.js';
import { el } from './dom.js';
import { money } from './format.js';
import { reportError, toast } from './toast.js';

export function openCategorize(ctx, row, categories, onDone) {
  const overlay = el('div', { class: 'modal', onclick: (e) => { if (e.target === overlay) close(); } });
  const select = el('select', {},
    ...categories.map((c) => el('option', { value: c, selected: c === row.category }, c)));
  const dialog = el('div', { class: 'dialog', role: 'dialog', 'aria-label': 'Categorize' },
    el('h3', {}, 'Categorize'),
    el('p', { class: 'muted' }, `${money(row.amount)} · ${row.rawDescription}`),
    el('label', { class: 'field' }, 'Category', select),
    el('div', { class: 'actions' },
      row.categoryOrigin === 'PIN'
        ? el('button', { type: 'button', class: 'ghost', onclick: unpin }, 'Unpin')
        : null,
      el('button', { type: 'button', onclick: close }, 'Cancel'),
      el('button', { type: 'button', class: 'primary', onclick: pin }, 'Pin')));
  overlay.append(dialog);
  document.body.append(overlay);
  select.focus();
  document.addEventListener('keydown', onKey);

  function close() {
    overlay.remove();
    document.removeEventListener('keydown', onKey);
  }

  function onKey(e) {
    if (e.key === 'Escape') close();
  }

  async function submit(decision) {
    close();
    try {
      await api.decisions(ctx.n, [decision]);
      toast('Recorded');
    } catch (error) {
      reportError(error);
    }
    if (onDone) onDone();
  }

  function pin() {
    if (!select.value) {
      toast('Pick a category', 'bad');
      return;
    }
    submit(decisions.pin(ctx, [row.externalId], select.value, 'categorized in the hub'));
  }

  function unpin() {
    submit(decisions.unpin(ctx, [row.externalId], 'unpinned in the hub'));
  }
}
