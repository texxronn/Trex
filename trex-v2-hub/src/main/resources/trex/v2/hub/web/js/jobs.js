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
let drift = null;
let lastPlan = null; // in-session plan counts; Apply stays locked until one exists

const selected = new Set();
let stream = null;
let outputHost;
let opsHost;
let stagingHost;
let jobsHost;
let historyHost;
let statusLine;
let ingestButton;

export function mount(container, context) {
  host = container;
  ctx = context;
  render();
  load();
  return { refresh: load };
}

function render() {
  clear(host);
  opsHost = el('div', { class: 'ops' });
  stagingHost = el('div');
  jobsHost = el('div');
  historyHost = el('div');
  outputHost = el('pre', { class: 'job-output' });
  statusLine = el('div', { class: 'muted', hidden: true });
  host.append(
    opsHost,
    el('h3', {}, 'Staging inbox'),
    uploadBar(),
    statusLine,
    stagingHost,
    el('h3', {}, 'Egress'),
    jobsHost,
    el('h3', {}, 'Run output'),
    outputHost,
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
  renderOps();
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
  stagingHost.append(el('div', { class: 'toolbar' }, ingestButton,
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
  const egress = jobs.find((job) => job.name === 'egress-firefly');
  if (!egress) {
    jobsHost.append(el('p', { class: 'muted' }, 'No job runner configured.'));
    return;
  }
  const busy = egress.running;
  const canApply = !busy && lastPlan != null && lastPlan.exit === 0;
  const buttons = [
    el('button', { class: 'primary', disabled: busy, onclick: () => startRun('egress-firefly', { mode: 'plan' }) }, 'Plan'),
    el('button', { disabled: busy, onclick: () => startRun('egress-firefly', { mode: 'verify' }) }, 'Verify'),
    el('button', { class: 'warn', disabled: !canApply, onclick: confirmApply,
      title: canApply ? '' : 'Run Plan first' }, 'Apply'),
  ];
  if (busy && egress.activeRun) {
    buttons.push(el('button', { class: 'ghost', onclick: () => cancel(egress.activeRun) }, 'Cancel'));
  }
  const status = lastPlan
    ? el('div', { class: 'muted' }, 'planned: ' + planSummary(lastPlan))
    : el('div', { class: 'muted' }, 'Apply is locked until you run a Plan.');
  jobsHost.append(el('div', { class: 'job-card' },
    el('div', { class: 'muted' }, egress.description),
    status,
    el('div', { class: 'toolbar' }, ...buttons)));
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
    appendOutput(`== ${run.job} (${run.state}, exit ${run.exit})`);
    for (const line of run.output || []) appendOutput(line);
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
