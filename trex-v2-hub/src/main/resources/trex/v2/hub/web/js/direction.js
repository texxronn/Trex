// The direction of a row, from the sign of its amount: a credit is IN, a debit is OUT. Rendered
// with a colour and an arrow so it reads at a glance; zero or an absent amount shows nothing.

import { el } from './dom.js';

export function direction(amount) {
  if (amount === null || amount === undefined || amount === 0) {
    return el('span', { class: 'muted' }, '');
  }
  return amount < 0
    ? el('span', { class: 'dir out' }, 'OUT \u2192')
    : el('span', { class: 'dir in' }, 'IN \u2190');
}
