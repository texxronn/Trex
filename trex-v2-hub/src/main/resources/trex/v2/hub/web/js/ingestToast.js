// The ingest toast (V2-QOL-IMPROVEMENTS-PLAN.md §1): when an SSE delta reports that the index moved past
// batches which completed after the one we last saw, fetch just those batches and say what the
// sweep did — in any mode. The filter is /api/ingests?sinceN=, so a delta never pulls the whole
// history. No framework, no build step.

import { api } from './api.js';
import { total } from './format.js';
import { toast } from './toast.js';

let lastN = null;            // the newest n whose batches have been reported
let latestN = null;          // the newest delta seen while a fetch is in flight
let latestSettled = false;   // whether the baseline had settled when that delta arrived
let lastReview = null;       // the review total at the previous delta
let baseline = null;         // the last reset's review total: { ready, total }, for the next delta
let generation = 0;          // bumped by every baseline reset; an older fetch's result is void
let running = false;

/**
 * The SSE snapshot: seed the baseline so the first delta toasts the batches that completed since
 * the page loaded (or reconnected) instead of treating that delta as the baseline. The review
 * total is fetched now; whether a delta may trust it is decided when that delta arrives.
 */
export function onSnapshot(n) {
  rebaseline(n);
}

/** A baseline reset: line numbers restart at n, and the review total is refetched. */
function rebaseline(n) {
  generation += 1;
  lastN = n;
  latestN = n;
  latestSettled = false;
  baseline = reviewBaseline();
}

/** The review total at this reset, boxed so a delta can check whether it had settled. */
function reviewBaseline() {
  const box = { ready: false, total: null };
  box.total = reviewTotal().then((value) => {
    box.ready = true;
    return value;
  });
  return box;
}

/**
 * One SSE delta. The first processed delta after a snapshot fetches the batches completed since
 * it; only a stream that never saw a snapshot falls back to treating its first delta as the
 * baseline.
 */
export async function onDelta(change) {
  const n = change.n;
  if (lastN === null || n < lastN) {
    rebaseline(n); // no snapshot yet, or a rebuilt journal whose n line numbers restarted
    return;
  }
  if (n === lastN) {
    return;
  }
  latestN = n;
  // /api/status reads the current index, so a baseline still in flight when this delta arrived
  // may already include the delta's own items: only one that had settled before it may be trusted.
  latestSettled = baseline !== null && baseline.ready;
  if (running) {
    return; // the loop below picks the newer n up
  }
  running = true;
  try {
    while (lastN < latestN) {
      const epoch = generation;
      const since = lastN;
      const target = latestN;
      const settled = latestSettled;
      const [ingests, status] = await Promise.all([api.ingests(since), api.status()]);
      if (epoch !== generation) {
        continue; // a snapshot or rollback rebaselined mid-fetch; its baseline supersedes this
      }
      const rows = (ingests && ingests.rows) || [];
      // Everything up to a reported batch's n_end is now told; a delta that arrived mid-fetch
      // only narrows the next range.
      lastN = rows.reduce((max, row) => Math.max(max, row.nEnd || 0), target);
      if (baseline !== null) {
        lastReview = settled ? await baseline.total : null;
        baseline = null;
      }
      const review = total(status && status.reviewByKind);
      const reviewDelta = lastReview === null ? 0 : review - lastReview;
      lastReview = review;
      if (rows.length) {
        toast(summary(rows, reviewDelta), failed(rows).length ? 'bad' : '', openJobs);
      }
    }
  } catch {
    // transient; lastN is unchanged, so the next delta retries the range
  } finally {
    running = false;
  }
}

/** The mode switch the app already uses: the nav's hashes, routed by app.js's hashchange. */
function openJobs() {
  location.hash = 'jobs';
}

async function reviewTotal() {
  try {
    return total((await api.status()).reviewByKind);
  } catch {
    return null;
  }
}

function failed(rows) {
  return rows.filter((row) => row.status && row.status !== 'ok');
}

/**
 * "2 statements ingested · 47 new rows · 1 flagged · 1 new review item — a.csv, b.csv". Flagged
 * only when there is one; duplicates only when every row was a duplicate — nothing appended,
 * nothing flagged, no failed batch — so a clean no-op sweep is still confirmed; a batch that
 * failed names its file and the runner's word for it ("rejected", "bad rows") and makes the
 * toast an error one.
 */
function summary(rows, reviewDelta) {
  const appended = sum(rows, 'appended');
  const duplicate = sum(rows, 'duplicate');
  const flagged = sum(rows, 'flagged');
  const bad = failed(rows);
  const ok = rows.length - bad.length;

  const parts = [];
  if (bad.length) {
    parts.push(bad.map((row) => `${row.file || '(unknown)'} ${row.status.replace(/_/g, ' ')}`).join(', '));
    if (ok > 0) {
      parts.push(`${ok} statement${ok === 1 ? '' : 's'} ingested`);
    }
  } else {
    parts.push(`${rows.length} statement${rows.length === 1 ? '' : 's'} ingested`);
  }
  parts.push(`${appended} new row${appended === 1 ? '' : 's'}`);
  if (flagged > 0) {
    parts.push(`${flagged} flagged`);
  }
  if (appended === 0 && duplicate > 0 && flagged === 0 && bad.length === 0) {
    parts.push(`${duplicate} duplicate${duplicate === 1 ? '' : 's'}`);
  }
  if (reviewDelta > 0) {
    parts.push(`${reviewDelta} new review item${reviewDelta === 1 ? '' : 's'}`);
  }
  // A failed file is already named above; the dash lists the statements that did land.
  const files = rows.filter((row) => !bad.includes(row)).map((row) => row.file).filter(Boolean);
  return parts.join(' · ') + (files.length ? ' — ' + files.join(', ') : '');
}

function sum(rows, field) {
  return rows.reduce((running, row) => running + (row[field] || 0), 0);
}
