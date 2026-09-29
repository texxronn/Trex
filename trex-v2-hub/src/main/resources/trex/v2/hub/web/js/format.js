// Formatting at the edge only: money is cents, dates are ISO strings.

export function money(cents) {
  if (cents === null || cents === undefined) return '';
  const sign = cents < 0 ? '\u2212' : '';
  const abs = Math.abs(cents);
  const dollars = Math.floor(abs / 100).toLocaleString('en-AU');
  const remainder = String(abs % 100).padStart(2, '0');
  return `${sign}$${dollars}.${remainder}`;
}

export function date(iso) {
  return iso || '';
}

export function shortId(id) {
  return id ? id.slice(0, 8) : '';
}

export function total(counts) {
  return Object.values(counts || {}).reduce((sum, n) => sum + n, 0);
}
