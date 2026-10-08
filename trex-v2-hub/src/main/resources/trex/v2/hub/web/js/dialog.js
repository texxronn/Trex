// A minimal modal prompt: the one dialog shape the hub reuses for a note, a dismiss reason, and
// anything else that needs a line of text before a decision is recorded.

import { el } from './dom.js';

export function openPrompt(options, onSave) {
  const { title, summary, label, placeholder, multiline, confirm } = options;
  const overlay = el('div', { class: 'modal', onclick: (e) => { if (e.target === overlay) close(); } });
  const input = multiline
    ? el('textarea', { rows: '3', placeholder: placeholder || '' })
    : el('input', { type: 'text', placeholder: placeholder || '' });
  const dialog = el('div', { class: 'dialog', role: 'dialog', 'aria-label': title },
    el('h3', {}, title),
    summary ? el('p', { class: 'muted' }, summary) : null,
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
