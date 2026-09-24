'use strict';

// trex categorize — the uncategorised worklist, and writing rules and pins from it.
//
// Nothing here decides anything. Every number on screen is computed by the gateway against the
// lines it holds (/api/proposal), so what the preview says and what the write does cannot drift
// apart: the write repeats the same dry run and refuses if it disagrees. All bank text is inserted
// with textContent, never as HTML.

const $ = (id) => document.getElementById(id);
const DEBOUNCE_MS = 180;
const SMALL = 2;                  // "one-offs": merchants seen once or twice

const model = {
  entries: [],
  categories: [],
  rulesRevision: null,
  hideSmall: false,
  onlyProblems: false,
  live: false,
  seenN: null,
  target: null,                   // the worklist row a sheet was opened from
  proposal: null,                 // the last coverage the gateway returned
};

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

function formatCents(cents) {
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100).toLocaleString('en-AU');
  return `${cents < 0 ? '−' : ''}$${whole}.${String(abs % 100).padStart(2, '0')}`;
}

/**
 * Whether a merchant recurs, which is the argument for a rule over a pin. Two transactions a
 * year apart are not a habit; twelve over the same span are.
 */
function cadence(entry) {
  const days = (Date.parse(entry.lastSeen) - Date.parse(entry.firstSeen)) / 86400000;
  if (entry.count < 2 || days < 25) return entry.count === 1 ? 'once' : `${entry.count}×`;
  const perMonth = entry.count / (days / 30.4);
  if (perMonth >= 3) return 'weekly-ish';
  if (perMonth >= 0.8) return 'monthly-ish';
  return 'occasional';
}

// ---------------------------------------------------------------- the worklist

function row(entry) {
  const tr = el('tr');

  const merchant = el('td', 'col-merchant');
  merchant.append(el('span', null, entry.stem));
  tr.append(merchant);

  tr.append(el('td', 'col-num rows', String(entry.count)));

  const total = el('td', 'col-num total');
  total.append(el('span', entry.total < 0 ? 'neg' : 'pos', formatCents(entry.total)));
  tr.append(total);

  const seen = el('td', 'col-seen');
  seen.append(el('span', null, `${entry.firstSeen} → ${entry.lastSeen}`));
  seen.append(document.createTextNode(' '));
  seen.append(el('span', 'cadence', `(${cadence(entry)})`));
  tr.append(seen);

  tr.append(el('td', 'col-acct', entry.accounts.join(', ')));

  const actions = el('td', 'col-act');
  const rule = el('button', 'btn primary', 'Rule…');
  rule.type = 'button';
  rule.title = 'Write a rule that catches this merchant from now on';
  rule.addEventListener('click', () => openCompose(entry));
  const pin = el('button', 'btn', 'Pin…');
  pin.type = 'button';
  pin.title = 'Pin these exact transactions, without writing a rule';
  pin.addEventListener('click', () => openPin(entry));
  actions.append(rule, pin);
  tr.append(actions);
  return tr;
}

function renderWorklist() {
  const shown = model.hideSmall ? model.entries.filter((e) => e.count > SMALL) : model.entries;
  $('worklist-body').replaceChildren(...shown.map(row));
  $('merchant-count').textContent = String(shown.length);
  $('worklist-empty').hidden = shown.length > 0;
}

function renderSummary(data) {
  const spend = model.entries.reduce((sum, e) => sum + e.total, 0);
  $('summary').textContent =
    `${data.uncategorized} of ${data.transactions} transactions, ${formatCents(spend)}, across ${data.merchants} merchants`;
}

function renderCategories() {
  for (const id of ['f-category', 'pin-category']) {
    const select = $(id);
    const chosen = select.value;
    select.replaceChildren(...model.categories.map((c) => el('option', null, c)));
    if (chosen) select.value = chosen;
  }
}

// ---------------------------------------------------------------- composing a rule

/**
 * A first pattern from the merchant stem: the literal text, with regex metacharacters escaped.
 * Escaping matters — a PayPal merchant reads "PAYPAL *AIAUMARKETS", and an unescaped `*` is a
 * quantifier, so the pattern silently matches nothing.
 */
function patternFor(stem) {
  return stem.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function openCompose(entry) {
  model.target = entry;
  model.proposal = null;
  $('compose-title').textContent = entry ? `New rule for ${entry.stem}` : 'New rule';
  $('f-match').value = entry ? patternFor(entry.stem) : '';
  $('f-comment').value = '';
  $('compose').showModal();
  $('f-match').focus();
  preview();
}

let timer = null;
function schedulePreview() {
  clearTimeout(timer);
  timer = setTimeout(preview, DEBOUNCE_MS);
}

/** Ask the gateway what this pattern would do. It holds the lines; the page holds no opinion. */
async function preview() {
  const match = $('f-match').value.trim();
  const category = $('f-category').value;
  $('compose-apply').disabled = true;
  model.proposal = null;
  if (!match) {
    setCoverage('Type a pattern to see what it would match.', 'muted');
    $('collisions').replaceChildren();
    $('yaml').hidden = true;
    return;
  }
  const params = new URLSearchParams({ category, match, comment: $('f-comment').value.trim() });
  try {
    const res = await fetch(`/api/proposal?${params}`, { cache: 'no-store' });
    const body = await res.json();
    if (!res.ok) {
      setCoverage(body.error || `HTTP ${res.status}`, 'bad');
      return;
    }
    model.proposal = body;
    renderCoverage(body);
  } catch (e) {
    setCoverage(e.message, 'bad');
  }
}

function setCoverage(text, cls) {
  const line = $('coverage-line');
  line.className = `coverage-line${cls ? ' ' + cls : ''}`;
  line.replaceChildren(document.createTextNode(text));
}

function renderCoverage(p) {
  const line = $('coverage-line');
  line.className = 'coverage-line';
  line.replaceChildren();

  if (!p.allowed) {
    line.classList.add('bad');
    line.append(document.createTextNode(p.refusal));
  } else {
    line.append(
      el('b', null, String(p.matched)),
      document.createTextNode(p.matched === 1 ? ' row · ' : ' rows · '),
      el('b', null, formatCents(p.total)),
      document.createTextNode(` · ${p.fromUncategorized} currently uncategorised`));
    if (p.blockedByPin > 0) {
      // A pinned row will not move whatever a rule says, so say so rather than imply it will.
      line.append(document.createTextNode(` · ${p.blockedByPin} pinned and unaffected`));
    }
  }

  // The collision warning: this is how `COSTCO GAS` vs `COSTCO WHOLESALE` gets caught before
  // it lands, rather than by noticing a category moved a month later.
  $('collisions').replaceChildren(...(p.takes || []).map((t) =>
    el('li', null, `takes ${t.count} row${t.count === 1 ? '' : 's'} from rule #${t.ruleIndex} (${t.category})`
      + (p.insertBefore ? ` — will be inserted before rule #${p.insertBefore} so it wins` : ''))));

  $('yaml').textContent = p.yaml;
  $('yaml').hidden = false;
  $('compose-apply').disabled = !p.allowed;
}

async function applyRule() {
  const p = model.proposal;
  if (!p || !p.allowed) return;
  const body = {
    category: $('f-category').value,
    comment: $('f-comment').value.trim() || null,
    when: { match: $('f-match').value.trim() },
    rulesRevision: model.rulesRevision,
  };
  if (p.insertBefore) body.at = p.insertBefore;
  await write('/api/rules', body, (out) =>
    `Rule added (${out.placement}): ${out.matched} rows now ${out.category}`);
}

// ---------------------------------------------------------------- pinning

function openPin(entry) {
  model.target = entry;
  $('pin-what').textContent =
    `${entry.count} transaction${entry.count === 1 ? '' : 's'} from ${entry.stem}`
    + (entry.count > entry.sampleIds.length
      ? ` — the first ${entry.sampleIds.length} will be pinned; a rule is the better tool above a handful.`
      : '.');
  $('pin-comment').value = '';
  $('pin').showModal();
}

async function applyPin() {
  await write('/api/pins', {
    category: $('pin-category').value,
    comment: $('pin-comment').value.trim() || null,
    externalIds: model.target.sampleIds,
    rulesRevision: model.rulesRevision,
  }, (out) => `Pinned to ${out.category}`);
}

/**
 * Every write goes the same way: same-origin POST with the admin header, then refresh from the
 * gateway rather than patching the page optimistically. What is on screen is what the service
 * says, which is the same rule the resolve page follows for decisions.
 */
async function write(path, body, describe) {
  try {
    const res = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Trex-Admin': '1' },
      body: JSON.stringify(body),
    });
    const out = await res.json();
    if (!res.ok) {
      // 409 means the rules moved under us — someone edited the file, or another tab wrote.
      toast(res.status === 409
        ? `${out.error} — reloading so you can look again`
        : out.error || `HTTP ${res.status}`, true);
      await load();
      return;
    }
    $('compose').close();
    $('pin').close();
    toast(describe(out));
    await load();
  } catch (e) {
    toast(e.message, true);
  }
}

function toast(message, bad) {
  const node = el('div', `toast${bad ? ' bad' : ''}`, message);
  $('toasts').append(node);
  setTimeout(() => node.remove(), bad ? 9000 : 5000);
}

// ---------------------------------------------------------------- the rules panel

/** A one-line summary of a `when` tree, enough to recognise a rule without opening it. */
function describeWhen(when, depth) {
  if (!when || depth > 3) return '…';
  if (when.match) return `/${when.match}/`;
  if (when.externalId) return `${when.externalId.length} id${when.externalId.length === 1 ? '' : 's'}`;
  if (when.all) return when.all.map((w) => describeWhen(w, depth + 1)).join(' and ');
  if (when.any) return when.any.map((w) => describeWhen(w, depth + 1)).join(' or ');
  if (when.not) return `not ${describeWhen(when.not, depth + 1)}`;
  if (when.direction) return when.direction === 'in' ? 'money in' : 'money out';
  if (when.accounts) return when.accounts.join(', ');
  if (when.amountMin != null || when.amountMax != null) return 'amount range';
  return '…';
}

function ruleRow(rule, isPin) {
  const tr = el('tr');
  if (rule.dead || rule.fullyShadowed || rule.stale) tr.className = 'problem';

  tr.append(el('td', 'col-num', (isPin ? 'pin ' : '') + rule.index));

  const category = el('td');
  category.append(el('span', null, rule.category));
  // Named apart because the fixes differ: a dead rule wants its pattern looked at, a shadowed
  // one wants moving or removing, a stale pin points at transactions that are no longer there.
  if (rule.dead) category.append(el('span', 'flag dead', 'never fires'));
  if (rule.fullyShadowed) category.append(el('span', 'flag shadowed', `shadowed by #${rule.shadowedBy}`));
  if (rule.stale) category.append(el('span', 'flag stale', 'matches nothing'));
  if (rule.comment) {
    category.append(el('div', 'col-comment', rule.comment));
  }
  tr.append(category);

  const pattern = el('td', 'col-pattern', describeWhen(rule.when, 0));
  pattern.title = JSON.stringify(rule.when);
  tr.append(pattern);

  tr.append(el('td', 'col-num', String(rule.hits)));
  const total = el('td', 'col-num');
  if (!isPin) total.append(el('span', rule.total < 0 ? 'neg' : 'pos', formatCents(rule.total || 0)));
  tr.append(total);

  const actions = el('td', 'col-act');
  const remove = el('button', 'btn', 'Delete');
  remove.type = 'button';
  remove.title = 'Remove this entry. Categories are derived, so this is reversible in git.';
  remove.addEventListener('click', () => removeEntry(isPin, rule.index, rule.category));
  actions.append(remove);
  tr.append(actions);
  return tr;
}

function renderRules(data) {
  const rules = (data.rules || []).map((r) => ({ ...r, isPin: false }));
  const pins = (data.pins || []).map((p) => ({ ...p, isPin: true }));
  const all = [...rules, ...pins];
  const shown = model.onlyProblems
    ? all.filter((r) => r.dead || r.fullyShadowed || r.stale)
    : all;

  const body = $('rules-body');
  body.replaceChildren(...shown.map((r) => ruleRow(r, r.isPin)));
  $('rules-count').textContent = String(shown.length);
  $('rules-empty').hidden = shown.length > 0;

  const problems = all.filter((r) => r.dead || r.fullyShadowed || r.stale).length;
  $('rules-summary').textContent =
    `${data.categorized} categorised, ${data.uncategorized} not, ${data.structural} transfers`
    + (problems ? ` · ${problems} need attention` : '');

  renderPromotions(data.promotions || []);
}

/** A merchant pinned three times is the file asking for one line instead of three. */
function renderPromotions(promotions) {
  const panel = $('rules-panel');
  panel.querySelectorAll('.promotion').forEach((n) => n.remove());
  const head = panel.querySelector('.panel-head');
  for (const p of promotions) {
    const note = el('p', 'promotion');
    note.append(document.createTextNode('Pinned '));
    note.append(el('b', null, String(p.pinned)));
    note.append(document.createTextNode(' times as '));
    note.append(el('b', null, p.category));
    note.append(document.createTextNode(`: ${p.stem}. A rule would cover it once.`));
    const write = el('button', 'btn', 'Write the rule');
    write.type = 'button';
    write.addEventListener('click', () => {
      $('f-category').value = p.category;
      openCompose({ stem: p.stem, count: p.pinned, sampleIds: p.externalIds });
    });
    note.append(write);
    head.after(note);
  }
}

async function removeEntry(isPin, index, category) {
  const what = `${isPin ? 'pin' : 'rule'} #${index} (${category})`;
  try {
    const res = await fetch(`/api/${isPin ? 'pins' : 'rules'}/${index}?rulesRevision=${model.rulesRevision}`, {
      method: 'DELETE',
      headers: { 'X-Trex-Admin': '1' },
    });
    const out = await res.json();
    if (!res.ok) {
      toast(out.error || `HTTP ${res.status}`, true);
    } else {
      toast(`Removed ${what}`);
    }
  } catch (e) {
    toast(e.message, true);
  }
  await load();
}

// ---------------------------------------------------------------- loading

async function load() {
  try {
    const res = await fetch('/api/worklist', { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    model.entries = data.entries;
    model.categories = data.categories;
    model.rulesRevision = data.rulesRevision;
    renderCategories();
    renderWorklist();
    renderSummary(data);
    renderStatus(res.headers.get('X-Trex-Stale') === '1' ? 'stale' : 'ok');

    // The rules and their health come from their own endpoint: the worklist is about what has
    // no rule, this is about what the rules that exist are doing.
    const rulesRes = await fetch('/api/rules', { cache: 'no-store' });
    if (rulesRes.ok) renderRules(await rulesRes.json());
  } catch (e) {
    renderStatus('bad', e.message);
  }
}

function renderStatus(state, detail) {
  const dot = $('status-dot');
  const box = $('status');
  const text = $('status-text');
  dot.className = `dot ${state === 'ok' ? 'ok' : state === 'bad' ? 'bad' : ''}`;
  box.classList.toggle('bad', state === 'bad');
  text.textContent = state === 'ok'
    ? `rules ${model.rulesRevision}`
    : state === 'stale' ? 'gateway unreachable — showing the last good answer' : (detail || 'error');
}

/**
 * The stream says what moved and carries no rows (SPEC §5.7), so a frame is a prompt to refetch.
 * A rule written in another tab moves the revision, and this page notices without being told.
 */
function connect() {
  const source = new EventSource('/api/events');
  source.addEventListener('open', () => { model.live = true; });
  source.addEventListener('head', (e) => {
    model.live = true;
    const head = JSON.parse(e.data);
    if (head.n !== model.seenN || head.rulesRevision !== model.rulesRevision) {
      model.seenN = head.n;
      load();
    }
  });
  source.addEventListener('error', () => { model.live = false; });
}

document.addEventListener('DOMContentLoaded', () => {
  $('hide-small').addEventListener('change', () => {
    model.hideSmall = $('hide-small').checked;
    renderWorklist();
  });
  $('only-problems').addEventListener('change', () => {
    model.onlyProblems = $('only-problems').checked;
    load();
  });
  $('f-match').addEventListener('input', schedulePreview);
  $('f-comment').addEventListener('input', schedulePreview);
  $('f-category').addEventListener('change', preview);
  $('compose-apply').addEventListener('click', applyRule);
  $('compose-cancel').addEventListener('click', () => $('compose').close());
  $('pin-apply').addEventListener('click', applyPin);
  $('pin-cancel').addEventListener('click', () => $('pin').close());
  load();
  connect();
});
