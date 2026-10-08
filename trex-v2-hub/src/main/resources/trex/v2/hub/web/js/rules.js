// Rules mode (read-only): the current rule set as a browsable tree, with the workbook's coverage,
// lint, pins and suggestions, and the raw YAML for reference. Rules are edited in the repo and
// deployed; nothing here mutates config, so the checkout and the running config cannot drift.

import { api } from './api.js';
import { el, clear, scroll } from './dom.js';
import { reportError } from './toast.js';

let host;
let ctx;
let report = null;
let categoriesYaml = '';
let transfersYaml = '';

export function mount(container, context) {
  host = container;
  ctx = context;
  render();
  load();
  return { refresh: load };
}

async function load() {
  try {
    report = await api.workbook();
    categoriesYaml = (await api.categoriesYaml()) || '';
    transfersYaml = (await api.transfersYaml()) || '';
  } catch (error) {
    reportError(error);
    return;
  }
  render();
}

function render() {
  clear(host);
  if (!report) {
    host.append(el('p', { class: 'muted' }, 'Loading…'));
    return;
  }
  host.append(
    coverageBar(report),
    ruleTree(report),
    lintSection(report),
    pinsSection(report),
    suggestionsSection(report),
    rawSection());
}

function coverageBar(report) {
  const c = report.coverage;
  return el('section', {}, el('h2', {}, 'Coverage'),
    el('p', { class: 'muted' },
      `${c.total} current · ${c.categorized} categorised · ${c.pinned} pinned · ` +
      `${c.uncategorized} uncategorised · ${c.structural} structural`));
}

// The file says what the rules are; the workbook says what they do. One row per rule, in order
// within its category, with hits, merchants and a shadowed/never-fires marker inline.
function ruleTree(report) {
  const rules = report.rules || [];
  const byCategory = new Map();
  for (const rule of rules) {
    if (!byCategory.has(rule.category)) {
      byCategory.set(rule.category, []);
    }
    byCategory.get(rule.category).push(rule);
  }
  const declared = (ctx.refdata && ctx.refdata.categories) || [];
  const categories = [...new Set([...declared, ...byCategory.keys()])].sort();
  return el('section', {}, el('h2', {}, 'Rules'),
    el('p', { class: 'hint muted' },
      'Declared categories and the ordered rules that assign them (first match wins). '
      + 'Expand one; a rule that never fires or is shadowed is marked.'),
    ...categories.map((category) => categoryGroup(category, byCategory.get(category) || [])));
}

function categoryGroup(category, rules) {
  const ordered = rules.slice().sort((a, b) => a.index - b.index);
  const rows = ordered.reduce((sum, r) => sum + r.hits, 0);
  return el('details', { open: ordered.some((r) => r.hits > 0) },
    el('summary', {}, el('b', {}, category), ' ',
      el('span', { class: 'muted' },
        `${ordered.length} rule${ordered.length === 1 ? '' : 's'} · ${rows} rows`)),
    ordered.length
      ? el('div', { class: 'rules' }, ...ordered.map(ruleLine))
      : el('p', { class: 'muted' }, 'no rules'));
}

function ruleLine(rule) {
  const warn = rule.hits === 0
    ? (rule.shadowed > 0 ? `shadowed by #${rule.shadowedBy}` : 'never fires')
    : null;
  return el('div', { class: 'rule-line' },
    el('span', { class: 'mono' }, '#' + rule.index),
    el('span', { class: 'desc' }, rule.comment || ''),
    el('span', { class: 'muted' }, `${rule.hits} rows · ${rule.merchants} merchants`),
    warn ? el('span', { class: 'tag NONE' }, warn) : null);
}

function lintSection(report) {
  const findings = report.findings || [];
  return el('section', {}, el('h2', {}, `Lint (${findings.length})`),
    findings.length === 0
      ? el('p', { class: 'muted' }, 'No findings.')
      : el('div', {}, ...findings.map((f) =>
          el('div', {}, el('span', { class: 'tag NONE' }, f.kind), ' ', f.subject, ' — ', f.detail))));
}

function pinsSection(report) {
  const pins = report.pins || [];
  if (!pins.length) {
    return el('section', {}, el('h2', {}, 'Pins (0)'), el('p', { class: 'muted' }, 'No pins.'));
  }
  const head = el('tr', {}, el('th', {}, 'Id'), el('th', {}, 'Category'), el('th', {}, 'Rule'));
  const body = pins.map((p) => el('tr', {},
    el('td', { class: 'muted', title: p.externalId }, (p.externalId || '').slice(0, 8)),
    el('td', {}, p.category),
    el('td', { class: 'muted' }, p.redundant ? `${p.ruleId} covers it` : '\u2014')));
  return el('section', {}, el('h2', {}, `Pins (${pins.length})`),
    scroll(el('table', {}, el('thead', {}, head), el('tbody', {}, ...body))));
}

function suggestionsSection(report) {
  const all = report.suggestions || [];
  const shown = all.slice(0, 100);
  return el('section', {}, el('h2', {}, `Suggestions (${all.length})`),
    all.length === 0
      ? el('p', { class: 'muted' }, 'No suggestions.')
      : el('div', {}, ...shown.map((s) =>
          el('div', {},
            `${s.source === 'PIN' ? 'pin cluster' : 'uncategorised'}: ${s.stem}` +
            (s.category ? ` as ${s.category}` : '') +
            ` \u00d7${s.occurrences} \u2014 regex ${s.proposedRegex} matches ${s.regexMatches} ` +
            `(${s.regexNew} new, ${s.regexConflicts} already categorised)`)),
          all.length > shown.length
            ? el('p', { class: 'muted' }, `\u2026 and ${all.length - shown.length} more`)
            : null));
}

function rawSection() {
  return el('section', {}, el('h2', {}, 'Raw config (read-only)'),
    el('details', {}, el('summary', {}, 'categories.yaml'), el('pre', { class: 'raw' }, categoriesYaml)),
    el('details', {}, el('summary', {}, 'transfers.yaml'), el('pre', { class: 'raw' }, transfersYaml)));
}
