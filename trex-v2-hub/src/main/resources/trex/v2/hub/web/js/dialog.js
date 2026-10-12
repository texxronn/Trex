// A minimal modal prompt: the one dialog shape the hub reuses for a note, a dismiss reason, and
// anything else that needs a line of text before a decision is recorded.

import { el } from './dom.js';

export function openPrompt(options, onSave) {
  const { title, summary, warning, label, placeholder, multiline, confirm } = options;
  const overlay = el('div', { class: 'modal', onclick: (e) => { if (e.target === overlay) close(); } });
  const input = multiline
    ? el('textarea', { rows: '3', placeholder: placeholder || '' })
    : el('input', { type: 'text', placeholder: placeholder || '' });
  const dialog = el('div', { class: 'dialog', role: 'dialog', 'aria-label': title },
    el('h3', {}, title),
    summary ? el('p', { class: 'muted' }, summary) : null,
    warning ? el('p', { class: 'warn' }, warning) : null,
    el('label', { class: 'field' }, label || '', input),
    el('div', { class: 'actions' },
      el('button', { type: 'button', onclick: close }, 'Cancel'),
      el('button', { type: 'button', class: 'primary', onclick: submit }, confirm || 'Save')));
  overlay.append(dialog);
  document.body.append(overlay);
  input.focus();
  document.addEventListener('keydown', onKey);

  function close() {
    overlay.remove();
    document.removeEventListener('keydown', onKey);
  }

  function onKey(e) {
    if (e.key === 'Escape') close();
  }

  function submit() {
    const value = input.value.trim();
    close();
    onSave(value);
  }
}

/**
 * A confirmation with no input: the deliberate click is the step. Enter and autofocus are
 * deliberately not wired — a confirmed decision must be a click, not a keystroke.
 */
export function openConfirm(options, onConfirm) {
  const { title, lines, warning, confirm } = options;
  const overlay = el('div', { class: 'modal', onclick: (e) => { if (e.target === overlay) close(); } });
  const dialog = el('div', { class: 'dialog', role: 'dialog', 'aria-label': title },
    el('h3', {}, title),
    ...(lines || []).map((line) => el('p', { class: 'muted' }, line)),
    warning ? el('p', { class: 'warn' }, warning) : null,
    el('div', { class: 'actions' },
      el('button', { type: 'button', class: 'ghost', onclick: close }, 'Cancel'),
      el('button', { type: 'button', class: 'primary', onclick: submit }, confirm || 'Confirm')));
  overlay.append(dialog);
  document.body.append(overlay);
  document.addEventListener('keydown', onKey);

  function close() {
    overlay.remove();
    document.removeEventListener('keydown', onKey);
  }

  function onKey(e) {
    if (e.key === 'Escape') close();
  }

  function submit() {
    close();
    onConfirm();
  }
}

/** The same dialog, with a picker: used where a decision names one of several (a clearing account). */
export function openSelect(options, onSave) {
  const { title, summary, label, choices, selected, confirm } = options;
  const overlay = el('div', { class: 'modal', onclick: (e) => { if (e.target === overlay) close(); } });
  // `selected` preselects the matching choice; when nothing matches, the browser keeps the first.
  const select = el('select', {},
    ...choices.map((c) => el('option', { value: c.value, selected: c.value === selected }, c.label)));
  const dialog = el('div', { class: 'dialog', role: 'dialog', 'aria-label': title },
    el('h3', {}, title),
    summary ? el('p', { class: 'muted' }, summary) : null,
    el('label', { class: 'field' }, label || '', select),
    el('div', { class: 'actions' },
      el('button', { type: 'button', onclick: close }, 'Cancel'),
      el('button', { type: 'button', class: 'primary', onclick: submit }, confirm || 'OK')));
  overlay.append(dialog);
  document.body.append(overlay);
  document.removeEventListener('keydown', onKey);
  document.addEventListener('keydown', onKey);

  function close() {
    overlay.remove();
    document.removeEventListener('keydown', onKey);
  }

  function onKey(e) {
    if (e.key === 'Escape') close();
  }

  function submit() {
    const value = select.value;
    close();
    onSave(value);
  }
}

/** A choice dialog: one button per action, for a row menu that needs a sentence of context. */
export function openChoice(options, choices) {
  const { title, summary, body, wide, xwide } = options;
  const size = xwide ? ' xwide' : wide ? ' wide' : '';
  const overlay = el('div', { class: 'modal', onclick: (e) => { if (e.target === overlay) close(); } });
  const dialog = el('div', { class: 'dialog' + size, role: 'dialog', 'aria-label': title },
    el('h3', {}, title),
    summary ? el('p', { class: 'muted' }, summary) : null,
    body || null,
    el('div', { class: 'actions' },
      el('button', { type: 'button', onclick: close }, 'Cancel'),
      ...choices.map((c) => el('button', {
        type: 'button',
        class: c.class || 'ghost',
        onclick: () => { close(); c.onPick(); },
      }, c.label))));
  overlay.append(dialog);
  document.body.append(overlay);
  document.addEventListener('keydown', onKey);

  function close() {
    overlay.remove();
    document.removeEventListener('keydown', onKey);
  }

  function onKey(e) {
    if (e.key === 'Escape') close();
  }
}
