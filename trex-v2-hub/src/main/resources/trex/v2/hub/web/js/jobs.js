// Jobs mode (V2-PROPOSAL.md §5.5, §12.5): upload statements to the staging inbox, trigger the
// on-demand jobs, and watch a run. The hub is the only origin; every call is proxied to the
// loopback runner. No framework — the same module pattern as eyeball.js.

import { api, uploadStaged } from './api.js';
import { el, clear, scroll } from './dom.js';
import { reportError } from './toast.js';

let host;
let ctx;
let adapters = [];
let staged = [];
let jobs = [];
let runs = [];
let ingests = [];
let drift = null;
let configDrift = null;
let lastPlan = null; // in-session plan counts; Apply stays locked until one exists
let lastPreview = null; // in-session reparse preview; Apply stays locked until a fresh one exists

const selected = new Set();
let stream = null;
let outputHost;
let opsHost;
let configDriftHost;
let stagingHost;
let jobsHost;
let ingestHost;
let frontierHost;
let frontierHeading;
let historyHost;
let statusLine;
let ingestButton;
let reparseSummaryHost;
let focusFrontier = false;

export function mount(container, context) {
  host = container;
  ctx = context;
  // The status strip's "N statements to fetch" opens Jobs at this table (#jobs?frontier), so the
  // nudge lands where the date range is already suggested (V2-QOL-IMPROVEMENTS-PLAN.md §2). The scroll
  // waits for load() to render the sections above, which would otherwise move the heading.
  focusFrontier = !!(ctx.modeQuery && ctx.modeQuery.has('frontier'));
  render();
  load();
  return { refresh: load };
}

function render() {
  clear(host);
  opsHost = el('div', { class: 'ops' });
  configDriftHost = el('div');
  stagingHost = el('div');
  jobsHost = el('div');
  ingestHost = el('div');
  frontierHost = el('div');
  historyHost = el('div');
  reparseSummaryHost = el('div');
  outputHost = el('pre', { class: 'job-output' });
  statusLine = el('div', { class: 'muted', hidden: true });
  host.append(
    opsHost,
    configDriftHost,
    el('h3', {}, 'Staging inbox'),
    uploadBar(),
    statusLine,
    stagingHost,
    el('h3', {}, 'Jobs'),
    jobsHost,
    el('h3', {}, 'Run output'),
    reparseSummaryHost,
    outputHost,
    frontierHeading = el('h3', { id: 'fetch-frontier' }, 'Fetch frontier'),
    frontierHost,
    el('h3', {}, 'Ingests'),
    ingestHost,
    el('h3', {}, 'History'),
    historyHost,
  );
}

function uploadBar() {
  const input = el('input', {
    type: 'file',
    multiple: true,
    onchange: (event) => upload([...event.target.files]),
  });
  const zone = el('div', { class: 'drop-zone' }, 'Drop statements here, or ', input);
  zone.addEventListener('dragover', (event) => { event.preventDefault(); zone.classList.add('over'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('over'));
  zone.addEventListener('drop', (event) => {
    event.preventDefault();
    zone.classList.remove('over');
    upload([...event.dataTransfer.files]);
  });
  return zone;
}

async function load() {
  try {
    const [a, s, j, r] = await Promise.all([
      api.jobAdapters(), api.staging(), api.jobs(), api.jobRuns(),
    ]);
    adapters = (a && a.types) || [];
    staged = s || [];
    jobs = j || [];
    runs = r || [];
    renderOps();
    renderStaging();
    renderJobs();
    renderHistory();
  } catch (error) {
    reportError(error);
  }
  try {
    const [u, p] = await Promise.all([api.units(), api.projection()]);
    drift = computeDrift(u, p);
  } catch {
    drift = null;
  }
  try {
    ingests = (await api.ingests()).rows || [];
  } catch {
    ingests = [];
  }
  try {
    configDrift = (await api.configDrift()) || [];
  } catch {
    configDrift = [];
  }
  renderConfigDrift();
  renderIngests();
  renderFrontier();
  renderOps();
  if (focusFrontier) {
    focusFrontier = false;
    frontierHeading.scrollIntoView({ block: 'start' });
  }
}

// ---- staleness ----------------------------------------------------------------------------

function computeDrift(unitsResp, projectionResp) {
  const units = (unitsResp && unitsResp.units) || [];
  const rows = (projectionResp && projectionResp.rows) || [];
  const byId = new Map(rows.map((row) => [row.unitId, row.stateHash]));
  const unitIds = new Set(units.map((unit) => unit.unitId));
  let unprojected = 0;
  let drifted = 0;
  for (const unit of units) {
    if (!byId.has(unit.unitId)) unprojected += 1;
    else if (byId.get(unit.unitId) !== unit.unitHash) drifted += 1;
  }
  let orphaned = 0;
  for (const row of rows) {
    if (!unitIds.has(row.unitId)) orphaned += 1;
  }
  return { units: units.length, projected: rows.length, unprojected, drifted, orphaned };
}

function lastRun(jobName) {
  return runs.find((run) => run.job === jobName
    && (run.state === 'SUCCEEDED' || run.state === 'FAILED'));
}

function lastRunOf(mode) {
  return runs.find((run) => run.job === 'egress-firefly' && run.params && run.params.mode === mode
    && (run.state === 'SUCCEEDED' || run.state === 'FAILED'));
}

function renderOps() {
  if (!opsHost) return;
  clear(opsHost);
  const plan = lastRunOf('plan');
  const apply = lastRunOf('apply');
  opsHost.append(
    chip('last plan', plan ? `${rel(plan.finishedAt || plan.queuedAt)} · exit ${plan.exit}` : 'never',
      plan && plan.exit === 0 ? 'good' : plan ? 'bad' : 'muted'),
    chip('last apply', apply ? `${rel(apply.finishedAt || apply.queuedAt)} · exit ${apply.exit}` : 'never',
      apply && apply.exit === 0 ? 'good' : apply ? 'bad' : 'muted'),
    chip('unprojected', drift ? String(drift.unprojected) : '—', drift && drift.unprojected ? 'bad' : 'good'),
    chip('drifted', drift ? String(drift.drifted) : '—', drift && drift.drifted ? 'bad' : 'good'),
    chip('orphaned', drift ? String(drift.orphaned) : '—', drift && drift.orphaned ? 'bad' : 'good'),
  );
}

function chip(label, value, cls) {
  return el('span', { class: 'ops-chip' },
    el('span', { class: 'muted' }, label + ' '), el('b', { class: cls }, value));
}

function rel(ms) {
  if (!ms) return 'never';
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 90) return `${Math.round(s)}s ago`;
  if (s < 5400) return `${Math.round(s / 60)}m ago`;
  if (s < 172800) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}

function relFuture(ms) {
  if (!ms) return '\u2014';
  const s = Math.max(0, (ms - Date.now()) / 1000);
  if (s < 90) return `in ${Math.round(s)}s`;
  if (s < 5400) return `in ${Math.round(s / 60)}m`;
  if (s < 172800) return `in ${Math.round(s / 3600)}h`;
  return `in ${Math.round(s / 86400)}d`;
}

// ---- config drift (V2-QOL-IMPROVEMENTS-PLAN.md §3) --------------------------------------------------

/**
 * The config the stack runs can lag the repo: init never overwrites a live file, so a repo change
 * to a file you never edited stays in the image. One line names every file that is not `same`;
 * `repo-newer` files are safe for `sync-config`, the rest are for a person.
 */
function renderConfigDrift() {
  clear(configDriftHost);
  if (!Array.isArray(configDrift)) return;
  const rows = configDrift.filter((row) => row && row.file && row.state && row.state !== 'same');
  if (!rows.length) return;
  configDriftHost.append(el('div', { class: 'drift-strip' },
    'config: ' + rows.map(configDriftText).join(' \u00b7 ')));
}

function configDriftText(row) {
  switch (row.state) {
    case 'repo-newer': return `${row.file} is newer in the repo (safe to update)`;
    case 'edited-here': return `${row.file} edited here`;
    case 'both-changed': return `${row.file} changed here and in the repo \u2014 merge by hand`;
    case 'unknown': return `${row.file} unknown (no base recorded \u2014 look, then sync-config --adopt; up first if .shipped is missing)`;
    default: return `${row.file} ${row.state}`;
  }
}

// ---- staging ------------------------------------------------------------------------------

async function upload(files) {
  if (!files || !files.length) return;
  for (const file of files) {
    statusLine.hidden = false;
    statusLine.textContent = `uploading ${file.name}…`;
    try {
      await uploadStaged(file, (fraction) => {
        statusLine.textContent = `uploading ${file.name} ${Math.round(fraction * 100)}%`;
      });
    } catch (error) {
      reportError(error);
    }
  }
  statusLine.hidden = true;
  await load();
}

function renderStaging() {
  clear(stagingHost);
  ingestButton = el('button', { class: 'primary', onclick: runIngest }, 'Ingest selected');
  ingestButton.disabled = selected.size === 0;
  const inboxButton = el('button', { onclick: () => startRun('ingest-inbox', {}).then(load) },
    'Ingest inbox');
  inboxButton.title = 'ingest every settled file statements.yaml names; file each under done/ or failed/';
  stagingHost.append(el('div', { class: 'toolbar' }, ingestButton, inboxButton,
    el('span', { class: 'muted' }, 'The tick means these bytes are already in the log.')));
  if (!staged.length) {
    stagingHost.append(el('p', { class: 'muted' }, 'Nothing staged.'));
    return;
  }
  const head = el('tr', {},
    el('th', {}), el('th', {}, 'File'), el('th', {}, 'Type'), el('th', {}, 'Account'),
    el('th', {}, 'Size'), el('th', {}, 'Status'), el('th', {}));
  const body = staged.map((file) => el('tr', { class: file.ingested ? 'read' : '' },
    el('td', {}, el('input', {
      type: 'checkbox',
      checked: selected.has(file.name),
      onchange: (event) => {
        if (event.target.checked) selected.add(file.name); else selected.delete(file.name);
        ingestButton.disabled = selected.size === 0;
      },
    })),
    el('td', { class: 'desc', title: file.original }, file.original),
    el('td', {}, select(adapters, file.sourceType, (value) => { file.sourceType = value; })),
    el('td', {}, select((ctx.refdata.accounts || []).map((account) => account.ref), file.account,
      (value) => { file.account = value; })),
    el('td', { class: 'muted' }, humanSize(file.size)),
    el('td', {}, statusCell(file)),
    el('td', {}, file.ingested
      ? el('button', { class: 'ghost', onclick: () => clearOne(file) }, 'Clear')
      : ''),
  ));
  stagingHost.append(scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body))));
}

function select(values, current, onPick) {
  const node = el('select', { onchange: (event) => onPick(event.target.value) },
    el('option', { value: '' }, '—'),
    ...values.map((value) => el('option', { value, selected: value === current }, value)));
  return node;
}

function statusCell(file) {
  if (file.ingested) {
    return el('span', { class: 'tick', title: 'ingested: these bytes are on facts' }, '\u2713 ingested');
  }
  if (file.state === 'done') {
    return el('span', { class: 'muted', title: 'cleared from the inbox' }, 'cleared');
  }
  if (file.state === 'failed') {
    return el('span', { class: 'occ-missed',
      title: 'the inbox sweep could not ingest it (bad rows or rejected); see the run output' }, '\u2717 failed');
  }
  return el('span', { class: 'muted' }, 'staged');
}

async function clearOne(file) {
  try {
    await api.clearStaged(file.name);
    selected.delete(file.name);
    await load();
  } catch (error) {
    reportError(error);
  }
}

async function runIngest() {
  const items = staged
    .filter((file) => selected.has(file.name))
    .map((file) => ({
      file: file.name,
      sourceType: file.sourceType,
      account: file.account,
      source: 'staging',
    }));
  if (!items.length) return;
  await startRun('ingest', { items });
  selected.clear();
  await load();
}

// ---- jobs ---------------------------------------------------------------------------------

function renderJobs() {
  clear(jobsHost);
  if (!jobs.length) {
    jobsHost.append(el('p', { class: 'muted' }, 'No job runner configured.'));
    return;
  }
  for (const job of jobs) {
    jobsHost.append(el('div', { class: 'job-card' }, ...jobCard(job)));
  }
}

function jobCard(job) {
  const busy = job.running;
  const last = lastRun(job.name);
  const nodes = [el('div', { class: 'muted' }, job.description)];

  if (job.name === 'egress-firefly') {
    const canApply = !busy && lastPlan != null && lastPlan.exit === 0;
    nodes.push(lastPlan
      ? el('div', { class: 'muted' }, 'planned: ' + planSummary(lastPlan))
      : el('div', { class: 'muted' }, 'Apply is locked until you run a Plan.'));
    const buttons = [
      el('button', { class: 'primary', disabled: busy, onclick: () => startRun('egress-firefly', { mode: 'plan' }) }, 'Plan'),
      el('button', { disabled: busy, onclick: () => startRun('egress-firefly', { mode: 'verify' }) }, 'Verify'),
      el('button', { class: 'warn', disabled: !canApply, onclick: confirmApply, title: canApply ? '' : 'Run Plan first' }, 'Apply'),
    ];
    if (busy && job.activeRun) {
      buttons.push(el('button', { class: 'ghost', onclick: () => cancel(job.activeRun) }, 'Cancel'));
    }
    nodes.push(el('div', { class: 'toolbar' }, ...buttons));
    return nodes;
  }

  if (job.name === 'reparse') {
    // A preview comes from a Re-read on an ingest-history row, so Apply can only ever post that
    // same evidence; it stays locked until the preview found something and nothing to RETIRE, and
    // after a successful apply it locks again (V2-QOL-IMPROVEMENTS-PLAN.md §4).
    const canApply = !busy && lastPreview != null && lastPreview.exit === 0
      && lastPreview.changed > 0
      && (lastPreview.counts.MISSING === 0 || lastPreview.acked);
    nodes.push(el('div', { class: 'muted' }, lastPreview
      ? 'previewed: ' + reparseSummaryText(lastPreview)
      : 'Apply is locked until you Re-read a batch from the ingest history below.'));
    const buttons = [
      el('button', { class: 'warn', disabled: !canApply, onclick: confirmReparse,
        title: canApply ? '' : applyLockedReason(lastPreview, busy) }, 'Apply'),
    ];
    if (busy && job.activeRun) {
      buttons.push(el('button', { class: 'ghost', onclick: () => cancel(job.activeRun) }, 'Cancel'));
    }
    nodes.push(el('div', { class: 'toolbar' }, ...buttons));
    if (lastPreview && lastPreview.exit === 0 && lastPreview.counts.MISSING > 0) {
      nodes.push(el('label', {},
        el('input', {
          type: 'checkbox',
          checked: !!lastPreview.acked,
          onchange: (event) => {
            lastPreview.acked = event.target.checked;
            renderJobs();
          },
        }),
        ' I understand ' + lastPreview.counts.MISSING + ' row(s) will be RETIREd'));
    }
    return nodes;
  }

  if (job.name === 'journal-snapshot') {
    const next = job.nextRun ? ` · next: ${relFuture(job.nextRun)}` : '';
    nodes.push(el('div', { class: 'muted' },
      (last ? `last snapshot: ${rel(last.finishedAt || last.queuedAt)} · exit ${last.exit}` : 'never run') + next));
    const buttons = [el('button', { class: 'primary', disabled: busy, onclick: () => startRun(job.name, {}) },
      'Snapshot now')];
    if (busy && job.activeRun) {
      buttons.push(el('button', { class: 'ghost', onclick: () => cancel(job.activeRun) }, 'Cancel'));
    }
    nodes.push(el('div', { class: 'toolbar' }, ...buttons));
    return nodes;
  }

  if (job.name === 'ingest') {
    // Ingest is driven by the staging inbox above, not a bare button (it needs the file list).
    nodes.push(el('div', { class: 'muted' }, 'Use the staging inbox above.'));
    return nodes;
  }

  // Generic: any job runs with no params.
  const buttons = [el('button', { class: 'primary', disabled: busy, onclick: () => startRun(job.name, {}) },
    job.title)];
  if (busy && job.activeRun) {
    buttons.push(el('button', { class: 'ghost', onclick: () => cancel(job.activeRun) }, 'Cancel'));
  }
  nodes.push(el('div', { class: 'toolbar' }, ...buttons));
  return nodes;
}

function planSummary(plan) {
  const c = plan.counts;
  if (!c) return `exit ${plan.exit} (no diff line)`;
  return `${c.creates} create · ${c.retags} retag · ${c.orphans} orphan · ${c.preserved} human-owned`;
}

async function confirmApply() {
  if (!lastPlan || lastPlan.exit !== 0) return;
  const message = `Apply the projection to Firefly?\n\nLast plan: ${planSummary(lastPlan)}.\n\n`
    + 'Orphans are reported, not deleted (that needs --remove-orphans).';
  if (!window.confirm(message)) return;
  await startRun('egress-firefly', { mode: 'apply' });
}

/** The reparse preview's one-line state for the job card. */
function reparseSummaryText(preview) {
  const c = preview.counts || {};
  const counts = `SHIFTED ${c.SHIFTED || 0} \u00b7 NEW ${c.NEW || 0} \u00b7 MISSING ${c.MISSING || 0}`;
  const head = preview.matched === null || preview.matched === undefined
    ? `exit ${preview.exit}` : `${preview.matched} matched, ${preview.changed} changed`;
  return `${head} \u00b7 ${counts}`;
}

function applyLockedReason(preview, busy) {
  if (busy) return 'A run is already active';
  if (!preview) return 'Re-read a batch first';
  if (preview.exit !== 0) return 'The preview failed';
  if (!(preview.changed > 0)) return 'The preview found nothing to apply';
  return 'Tick the retirement box first';
}

async function confirmReparse() {
  const preview = lastPreview;
  if (!preview || preview.exit !== 0 || !(preview.changed > 0)) return;
  if (preview.counts.MISSING > 0 && !preview.acked) return;
  const c = preview.counts;
  const retire = c.MISSING > 0
    ? `\n\n${c.MISSING} row(s) no longer parse and will be RETIREd.` : '';
  const message = `Re-read ${shortEvidence(preview.evidence)} as `
    + `${preview.sourceType}/${preview.account} and post the changes?\n\n`
    + `${preview.matched} matched, ${preview.changed} changed \u00b7 SHIFTED ${c.SHIFTED} \u00b7 `
    + `NEW ${c.NEW} \u00b7 MISSING ${c.MISSING}` + retire;
  if (!window.confirm(message)) return;
  await startRun('reparse', {
    evidence: preview.evidence,
    sourceType: preview.sourceType,
    account: preview.account,
    mode: 'apply',
  });
}

/** Capture a finished reparse so the card's Apply gate is fresh; null (locked) on a bad read. */
async function captureReparse(runId, params, run) {
  const empty = { matched: null, changed: null, counts: { SHIFTED: 0, NEW: 0, MISSING: 0 } };
  let detail;
  try {
    detail = await api.runDetail(runId);
  } catch (error) {
    reportError(error);
    renderReparseSummary(params, run, empty);
    return null;
  }
  const parsed = parseReparseOutput(detail.output);
  renderReparseSummary(params, detail, parsed);
  return {
    evidence: params.evidence,
    sourceType: params.sourceType,
    account: params.account,
    matched: parsed.matched,
    changed: parsed.changed,
    counts: parsed.counts,
    exit: detail.exit,
    acked: false,
  };
}

/**
 * IngestCommand.reparse prints "re-parse with <parser>: N matched, M changed" and one line per
 * proposed change: the kind (padded), the external id, the detail.
 */
function parseReparseOutput(output) {
  const lines = output || [];
  const summary = [...lines].reverse().find((line) => line.startsWith('re-parse with '));
  const m = /re-parse with .*?: (\d+) matched, (\d+) changed/.exec(summary || '');
  const counts = { SHIFTED: 0, NEW: 0, MISSING: 0 };
  for (const line of lines) {
    const kind = /^\s+(SHIFTED|NEW|MISSING)\s/.exec(line);
    if (kind) counts[kind[1]] += 1;
  }
  return { matched: m ? Number(m[1]) : null, changed: m ? Number(m[2]) : null, counts };
}

/** The per-evidence summary above the raw output (V2-QOL-IMPROVEMENTS-PLAN.md §4). */
function renderReparseSummary(params, detail, parsed) {
  if (!reparseSummaryHost) return;
  clear(reparseSummaryHost);
  const p = params || {};
  const counts = parsed.counts || {};
  reparseSummaryHost.append(el('div', { class: 'ops' },
    chip('re-read', `${p.mode || 'preview'} \u00b7 ${shortEvidence(p.evidence)}`, 'muted'),
    chip('adapter', `${p.sourceType || '?'} \u00b7 ${p.account || '?'}`, 'muted'),
    chip('result', parsed.matched === null
      ? `exit ${detail.exit}`
      : `${parsed.matched} matched, ${parsed.changed} changed`,
      detail.exit === 0 ? 'good' : 'bad'),
    chip('SHIFTED', String(counts.SHIFTED || 0), 'muted'),
    chip('NEW', String(counts.NEW || 0), 'muted'),
    chip('MISSING', String(counts.MISSING || 0), counts.MISSING ? 'bad' : 'good'),
  ));
  if (counts.MISSING > 0) {
    reparseSummaryHost.append(el('div', { class: 'ops' },
      el('span', { class: 'occ-missed' },
        `${counts.MISSING} row(s) no longer parse and would be RETIREd \u2014 tick on the reparse card to unlock Apply`)));
  }
}

function shortEvidence(id) {
  return id && id.length > 18 ? id.slice(0, 17) + '\u2026' : (id || '');
}

async function cancel(runId) {
  try {
    await api.cancelRun(runId);
    await load();
  } catch (error) {
    reportError(error);
  }
}

async function startRun(name, params) {
  clear(outputHost);
  clear(reparseSummaryHost);
  if (name === 'reparse') {
    lastPreview = null; // the moment a run starts, Apply locks — and stays locked if it fails
    renderJobs();
  }
  appendOutput(`== ${name} ${JSON.stringify(params)}`);
  try {
    const { runId } = await api.runJob(name, params);
    const run = await streamRun(runId);
    if (name === 'egress-firefly' && params.mode === 'plan') {
      await capturePlan(runId);
    }
    if (name === 'egress-firefly' && params.mode === 'apply' && run.exit === 0) {
      lastPlan = null; // consumed: Apply locks again until the next plan
    }
    if (name === 'reparse') {
      const preview = await captureReparse(runId, params, run); // renders the summary for either mode
      // Only a preview unlocks Apply; an apply leaves the gate locked (cleared at start).
      if (params.mode === 'preview') {
        lastPreview = preview; // Apply posts exactly this evidence: the same-evidence rule
      }
    }
  } catch (error) {
    reportError(error);
  } finally {
    await load();
  }
}

async function capturePlan(runId) {
  try {
    const detail = await api.runDetail(runId);
    const line = [...(detail.output || [])].reverse().find((l) => l.startsWith('done:'));
    lastPlan = { at: detail.finishedAt || Date.now(), exit: detail.exit, counts: parseDone(line) };
  } catch {
    lastPlan = null;
  }
}

function parseDone(line) {
  const m = /done:\s*(\d+) created,\s*(\d+) retagged,\s*(\d+) orphan\(s\),\s*(\d+) removed,\s*(\d+) of your edits preserved/
    .exec(line || '');
  return m ? { creates: +m[1], retags: +m[2], orphans: +m[3], removed: +m[4], preserved: +m[5] } : null;
}

function streamRun(runId) {
  return new Promise((resolve) => {
    if (stream) stream.close();
    const source = new EventSource(`/api/jobs/runs/${encodeURIComponent(runId)}/events`);
    stream = source;
    source.addEventListener('line', (event) => appendOutput(event.data));
    source.addEventListener('done', (event) => {
      const run = JSON.parse(event.data);
      appendOutput(`-- ${run.state} (exit ${run.exit})`);
      source.close();
      if (stream === source) stream = null;
      resolve(run);
    });
    source.addEventListener('error', () => { /* hub may reconnect; done closes it */ });
  });
}

function appendOutput(line) {
  outputHost.append(document.createTextNode(line + '\n'));
  outputHost.scrollTop = outputHost.scrollHeight;
}

// ---- history ------------------------------------------------------------------------------

function renderIngests() {
  clear(ingestHost);
  if (!ingests.length) {
    ingestHost.append(el('p', { class: 'muted' }, 'No ingests recorded yet.'));
    return;
  }
  const head = el('tr', {}, el('th', {}, 'File'), el('th', {}, 'Account'), el('th', {}, 'Frontier'),
    el('th', {}, 'n range'),
    el('th', {}, 'app/dup/flag'), el('th', {}, 'Status'), el('th', {}, 'When'), el('th', {}));
  const body = ingests.map((i) => el('tr', {},
    el('td', { class: 'desc', title: i.evidenceId || '' }, i.file || '(unknown)'),
    el('td', {}, i.accountRef || ''),
    el('td', { class: 'muted' }, i.latestTxnDate || '\u2014'),
    el('td', { class: 'muted' }, `${i.nStart}\u2013${i.nEnd}`),
    el('td', {}, `${num(i.appended)}/${num(i.duplicate)}/${num(i.flagged)}`),
    el('td', {}, el('span', { class: 'badge ' + (i.status === 'ok' ? '' : 'POTENTIAL_DUP') },
      i.status || 'open')),
    el('td', { class: 'muted' }, rel(i.completedMs || i.startedMs)),
    el('td', {}, reReadButton(i))));
  ingestHost.append(scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body))));
}

/**
 * The ingest row's action (V2-QOL-IMPROVEMENTS-PLAN.md §4): preview this batch's evidence with the adapter
 * and account the start marker recorded, so evidence is never paired with the wrong parser.
 */
function reReadButton(row) {
  const busy = jobRunning('reparse');
  const ready = !busy && row.evidenceId && row.sourceType && row.accountRef;
  let title = 'preview this evidence with the parser the batch used';
  if (busy) title = 'a reparse run is active';
  else if (!row.evidenceId) title = 'this batch has no evidence id';
  else if (!row.sourceType) title = 'this batch has no source type';
  else if (!row.accountRef) title = 'this batch has no account';
  return el('button', {
    class: 'ghost',
    disabled: !ready,
    title,
    onclick: () => startRun('reparse', {
      evidence: row.evidenceId,
      sourceType: row.sourceType,
      account: row.accountRef,
      mode: 'preview',
    }),
  }, 'Re-read');
}

function jobRunning(name) {
  const job = jobs.find((candidate) => candidate.name === name);
  return !!(job && job.running);
}

// ---- fetch frontier -------------------------------------------------------------------------

/**
 * Per account, the frontier — the newest transaction date already processed — so the next
 * statement file can be requested with a date range (V2-INGEST-FRONTIER-PLAN.md). Built from the
 * registry accounts joined to the newest ingest row (which now carries the frontier). The range is
 * the frontier inclusive through today; an account with no facts shows an em dash.
 */
function renderFrontier() {
  clear(frontierHost);
  const accounts = (ctx.refdata && ctx.refdata.accounts) || [];
  if (!accounts.length) return;
  const today = new Date().toISOString().slice(0, 10);
  const newest = new Map();
  for (const i of ingests) {
    if (i.accountRef && !newest.has(i.accountRef)) newest.set(i.accountRef, i);
  }
  const head = el('tr', {}, el('th', {}, 'Account'), el('th', {}, 'Newest file'),
    el('th', {}, 'Frontier'), el('th', {}, 'Suggested range'));
  const body = accounts.map((account) => {
    const imp = newest.get(account.ref);
    const frontier = imp && imp.latestTxnDate ? imp.latestTxnDate : null;
    return el('tr', {},
      el('td', {}, account.ref),
      el('td', { class: 'desc muted', title: imp ? imp.file || '' : '' },
        imp ? imp.file || '(unknown)' : '\u2014'),
      el('td', { class: 'muted' }, frontier || '\u2014'),
      el('td', { class: 'muted' }, frontier ? `${frontier} \u2192 ${today}` : ''));
  });
  frontierHost.append(scroll(el('table', { class: 'frontier-table' },
    el('thead', {}, head), el('tbody', {}, ...body))));
}

function num(value) {
  return value === null || value === undefined ? '\u2014' : value;
}

function renderHistory() {
  clear(historyHost);
  if (!runs.length) {
    historyHost.append(el('p', { class: 'muted' }, 'No runs yet.'));
    return;
  }
  const head = el('tr', {}, el('th', {}, 'When'), el('th', {}, 'Job'), el('th', {}, 'State'),
    el('th', {}, 'Exit'), el('th', {}));
  const body = runs.slice(0, 20).map((run) => el('tr', {},
    el('td', { class: 'muted' }, new Date(run.queuedAt).toLocaleString()),
    el('td', {}, run.job + (run.params && run.params.mode ? ' · ' + run.params.mode : '')),
    el('td', {}, el('span', { class: 'badge ' + run.state }, run.state)),
    el('td', {}, String(run.exit)),
    el('td', {}, el('button', { class: 'ghost', onclick: () => showRun(run.id) }, 'Output')),
  ));
  historyHost.append(scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body))));
}

async function showRun(runId) {
  try {
    const run = await api.runDetail(runId);
    clear(outputHost);
    clear(reparseSummaryHost);
    appendOutput(`== ${run.job} (${run.state}, exit ${run.exit})`);
    for (const line of run.output || []) appendOutput(line);
    if (run.job === 'reparse') {
      renderReparseSummary(run.params || {}, run, parseReparseOutput(run.output));
    }
  } catch (error) {
    reportError(error);
  }
}

function humanSize(bytes) {
  if (bytes === null || bytes === undefined || bytes < 0) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}
