// Rules mode (V2-PROPOSAL.md §10.4): load the current rules, preview a candidate's blast radius,
// save it, and work the workbook — lint, coverage, suggestions and redundant-pin cleanup.

import { api } from './api.js';
import { decisions } from './decisions.js';
import { el, clear } from './dom.js';
import { reportError, toast } from './toast.js';

let host;
let ctx;
let editor;
let summary;
let workbookHost;
let errorBar;

export function mount(container, context) {
  host = container;
  ctx = context;
  render();
  load();
  return { refresh: refreshWorkbook };
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  editor = el('textarea', { spellcheck: 'false' });
  summary = el('div', { class: 'muted' });
  workbookHost = el('div');
  host.append(errorBar,
    el('div', { class: 'toolbar' },
      button('Preview diff', preview, 'primary'),
      button('Save', save)),
    editor,
    summary,
    el('h2', {}, 'Workbook'),
    workbookHost);
}

async function load() {
  try {
    editor.value = await api.categoriesYaml();
  } catch (error) {
    errorBar.textContent = 'cannot load categories.yaml: ' + (error.message || error);
    errorBar.hidden = false;
  }
  await refreshWorkbook();
}

async function refreshWorkbook() {
  try {
    renderWorkbook(await api.workbook());
  } catch (error) {
    // workbook is best-effort; the editor still works
  }
}

function renderWorkbook(report) {
  clear(workbookHost);
  const c = report.coverage;
  workbookHost.append(
    el('p', { class: 'muted' },
      `${c.total} current · ${c.categorized} categorised · ${c.pinned} pinned · ` +
      `${c.uncategorized} uncategorised · ${c.structural} structural`),
    section('Lint', report.findings.length === 0
      ? [el('p', { class: 'muted' }, 'No findings.')]
      : report.findings.map((f) =>
          el('div', {}, el('span', { class: 'tag NONE' }, f.kind), ' ', f.subject, ' — ', f.detail))),
    section('Suggestions', report.suggestions.length === 0
      ? [el('p', { class: 'muted' }, 'No suggestions.')]
      : report.suggestions.map((s) => el('div', {},
          `${s.source === 'PIN' ? 'pin cluster' : 'uncategorised'}: ${s.stem}` +
          (s.category ? ` as ${s.category}` : '') +
          ` \u00d7${s.occurrences} \u2014 regex ${s.proposedRegex} matches ${s.regexMatches} ` +
          `(${s.regexNew} new, ${s.regexConflicts} already categorised)`))),
    section('Pins', renderPins(report.pins)),
    section('Trend (by month: rule / pin / uncategorised)',
      report.coverage.trend.map((t) =>
        el('div', { class: 'muted' }, `${t.period}: ${t.rule} / ${t.pin} / ${t.uncategorized}`))),
  );
}

function renderPins(pins) {
  if (!pins.length) return [el('p', { class: 'muted' }, 'No pins.')];
  return [el('table', {}, el('thead', {}, el('tr', {},
    el('th', {}, 'Id'), el('th', {}, 'Category'), el('th', {}, 'Rule'), el('th', {}))),
    el('tbody', {}, ...pins.map((p) => el('tr', {},
      el('td', { class: 'muted', title: p.externalId }, p.externalId.slice(0, 8)),
      el('td', {}, p.category),
      el('td', { class: 'muted' }, p.redundant ? `${p.ruleId} covers it` : '\u2014'),
      el('td', {}, p.redundant
        ? el('button', { type: 'button', onclick: () => unpin(p.externalId) }, 'UNPIN')
        : null)))))];
}

async function unpin(externalId) {
  try {
    await api.decisions(ctx.n, [decisions.unpin(ctx, [externalId], 'unpinned from workbook')]);
    toast('Unpinned');
    await refreshWorkbook();
  } catch (error) {
    reportError(error);
  }
}

async function preview() {
  try {
    const result = await api.reflowPreview(editor.value);
    renderSummary(result);
    errorBar.hidden = true;
  } catch (error) {
    errorBar.textContent = error.message || 'preview failed';
    errorBar.hidden = false;
  }
}

async function save() {
  try {
    await api.saveCategories(editor.value);
    toast('Saved; the watcher will re-derive');
    errorBar.hidden = true;
    setTimeout(refreshWorkbook, 400);
  } catch (error) {
    if (error.status === 422) {
      errorBar.textContent = error.message;
      errorBar.hidden = false;
    } else {
      reportError(error);
    }
  }
}

function renderSummary(previewResult) {
  clear(summary);
  summary.append(
    el('div', {}, `categories: ${previewResult.categoriesMoved} moved`),
    el('ul', {}, ...previewResult.moved.slice(0, 50).map((m) =>
      el('li', {}, `${m.externalId}: ${m.from} \u2192 ${m.to}`))),
    el('div', {}, `transfers: ${previewResult.transfersAdded} new, ${previewResult.transfersRemoved} unmatched`),
    el('div', {}, `review: ${previewResult.reviewOpened} opened, ${previewResult.reviewCleared} cleared`));
}

function section(title, children) {
  return el('div', {}, el('h3', {}, title), ...children);
}

function button(label, onClick, cls) {
  return el('button', { type: 'button', class: cls || '', onclick: onClick }, label);
}
