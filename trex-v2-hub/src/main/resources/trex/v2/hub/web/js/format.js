// Formatting at the edge only: money is cents, dates are ISO strings.

export function money(cents) {
  if (cents === null || cents === undefined) return '';
  const sign = cents < 0 ? '\u2212' : '';
  const abs = Math.abs(cents);
  const dollars = Math.floor(abs / 100).toLocaleString('en-AU');
  const remainder = String(abs % 100).padStart(2, '0');
  return `${sign}$${dollars}.${remainder}`;
}

// Weekday prefixes are display-only; everything else stays ISO so values remain sortable, copyable
// and comparable. Derived from the calendar date via UTC so the browser's timezone cannot shift it.
const DOW = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'];

export function dow(iso) {
  const day = (iso || '').slice(0, 10);
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  if (!m) return '';
  const y = +m[1], mo = +m[2] - 1, d = +m[3];
  const utc = new Date(Date.UTC(y, mo, d));
  // Reject impossible dates (2026-02-30) and Date.UTC's 1900 remap for years 0000-0099:
  // a shaped-but-invalid string must pass through unchanged, not gain an invented weekday.
  if (utc.getUTCFullYear() !== y || utc.getUTCMonth() !== mo || utc.getUTCDate() !== d) {
    return '';
  }
  return DOW[utc.getUTCDay()];
}

export function date(iso) {
  if (!iso) return iso || '';
  const day = (iso || '').slice(0, 10);
  const w = dow(day);
  return w ? `${w} ${day}` : iso;
}

export function shortId(id) {
  return id ? id.slice(0, 8) : '';
}

export function total(counts) {
  return Object.values(counts || {}).reduce((sum, n) => sum + n, 0);
}
