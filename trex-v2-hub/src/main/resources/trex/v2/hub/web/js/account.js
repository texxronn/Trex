// The account chip, shared by every mode so one account reads the same everywhere
// (V2-PROPOSAL.md §10.1). The colour is the configured `chip_color`, or a deterministic fallback
// so an unconfigured account still gets a stable colour. The palette lives in app.css.

import { el } from './dom.js';

const FALLBACK = ['blue', 'emerald', 'amber', 'violet', 'red', 'cyan', 'lime', 'slate'];

export function accountColor(refdata, ref) {
  const account = ((refdata && refdata.accounts) || []).find((a) => a.ref === ref);
  if (account && account.chipColor) {
    return account.chipColor;
  }
  let hash = 0;
  for (let i = 0; i < (ref || '').length; i++) {
    hash = (hash * 31 + ref.charCodeAt(i)) >>> 0;
  }
  return FALLBACK[hash % FALLBACK.length];
}

export function accountChip(refdata, ref) {
  if (!ref) {
    return el('span', {});
  }
  return el('span', { class: 'acct ' + accountColor(refdata, ref), title: ref }, ref);
}
