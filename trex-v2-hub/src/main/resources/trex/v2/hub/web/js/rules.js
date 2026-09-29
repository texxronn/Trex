// Rules mode (V2-PROPOSAL.md §10.4): load the current rules, preview a candidate's blast radius,
// then save it. Preview and save both go through the hub.

import { api } from './api.js';
import { el, clear } from './dom.js';
import { reportError, toast } from './toast.js';

let host;
let editor;
let summary;
let errorBar;

export function mount(container) {
  host = container;
  render();
  load();
  return { refresh: () => {} };
}

function render() {
  clear(host);
  errorBar = el('div', { class: 'error', hidden: true });
  editor = el('textarea', { spellcheck: 'false' });
  summary = el('div', { class: 'muted' });
  host.append(errorBar,
    el('div', { class: 'toolbar' },
      button('Preview diff', preview, 'primary'),
      button('Save', save)),
    editor,
    summary);
}

async function load() {
  try {
    editor.value = await api.categoriesYaml();
  } catch (error) {
    errorBar.textContent = 'cannot load categories.yaml: ' + (error.message || error);
    errorBar.hidden = false;
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

function button(label, onClick, cls) {
  return el('button', { type: 'button', class: cls || '', onclick: onClick }, label);
}
