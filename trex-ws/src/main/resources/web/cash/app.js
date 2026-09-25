'use strict';

// The cash entry page (SPEC §5.4, §5.7).
//
// Two shapes, one per submission: a purchase, or an attestation of what is left. Never both —
// a declaration is its own event and arrives on its own schedule, which is why the API refuses
// a request carrying both.

const $ = (id) => document.getElementById(id);

// The entry's identity, minted ONCE per filled-in form and sent as `ref`.
//
// This is not decoration. `occ` is assigned per batch, so a hand-entered line is always occ 0,
// and two genuinely different cash purchases sharing account, date, amount and description would
// mint the same content hash — the second silently dropped as a duplicate. A client-supplied ref
// makes identity a natural key instead. Minting it once per entry rather than per click is what
// makes a double-submit an idempotent retry instead of two lines.
function mintRef() {
  const rand = crypto.getRandomValues(new Uint8Array(8));
  return 'MAN-' + Date.now().toString(36) + '-' +
    Array.from(rand, (b) => b.toString(16).padStart(2, '0')).join('');
}
let ref = mintRef();

let mode = 'spend';

function setMode(next) {
  mode = next;
  $('mode-spend').classList.toggle('current', next === 'spend');
  $('mode-attest').classList.toggle('current', next === 'attest');
  $('mode-spend').setAttribute('aria-selected', String(next === 'spend'));
  $('mode-attest').setAttribute('aria-selected', String(next === 'attest'));
  $('spend-fields').hidden = next !== 'spend';
  $('attest-fields').hidden = next !== 'attest';
  say('');
}

function say(text, ok) {
  const el = $('result');
  el.textContent = text;
  el.className = 'result' + (text ? (ok ? ' ok' : ' bad') : '');
}

// Cents, via a string, never a float: 40.10 * 100 is 4009.999... and this is money.
function cents(value) {
  const m = String(value).trim().match(/^(-?)(\d+)(?:\.(\d{1,2}))?$/);
  if (!m) return null;
  const frac = (m[3] || '').padEnd(2, '0');
  return (m[1] === '-' ? -1 : 1) * (Number(m[2]) * 100 + Number(frac));
}

async function loadAccounts() {
  const res = await fetch('/api/accounts');
  if (!res.ok) throw new Error('accounts unavailable');
  // Only declared accounts can take a hand-entered line; the API refuses the rest, and offering
  // them here would just move that refusal later.
  const declared = (await res.json()).filter((a) => a.balanceSource === 'declared');
  const select = $('account');
  select.innerHTML = '';
  for (const a of declared) {
    const opt = document.createElement('option');
    opt.value = a.ref;
    opt.textContent = a.ref + '  (' + a.currency + ')';
    select.appendChild(opt);
  }
  if (!declared.length) {
    select.innerHTML = '<option value="">no cash accounts — add one with balanceSource: declared</option>';
    $('submit').disabled = true;
  }
}

async function submit(event) {
  event.preventDefault();
  const body = { ref, accountRef: $('account').value, date: $('date').value };

  if (mode === 'spend') {
    const amount = cents($('amount').value);
    if (amount === null || amount <= 0) return say('amount must be a number like 40.00', false);
    body.amount = -amount;                       // money out
    body.description = $('description').value.trim();
    if (!body.description) return say('say what it was — nothing else will identify this later', false);
  } else {
    const attested = cents($('attested').value);
    if (attested === null || attested < 0) return say('how much is left? 0 is a real answer', false);
    body.attestedBalance = attested;
  }

  $('submit').disabled = true;
  try {
    const res = await fetch('/api/cash', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Trex-Admin': '1' },
      body: JSON.stringify(body),
    });
    const text = await res.text();
    if (!res.ok) {
      // 404 means this page is on the read listener: it can show, it cannot write (§5.7).
      say(res.status === 404
        ? 'this is the read-only view — record from the admin listener on loopback'
        : (JSON.parse(text).error || text), false);
      return;
    }
    say(mode === 'spend' ? 'recorded' : 'noted', true);
    ref = mintRef();                              // a new entry, so a new identity
    $('amount').value = '';
    $('description').value = '';
    $('attested').value = '';
    refresh();
  } catch (e) {
    say(String(e), false);
  } finally {
    $('submit').disabled = false;
  }
}

async function refresh() {
  try {
    const res = await fetch('/api/snapshot?view=transactions&sort=n:desc&size=12&page=1');
    if (!res.ok) return;
    const rows = (await res.json()).rows.filter((r) => r.sourceType === 'manual');
    $('recent').innerHTML = rows.map((r) => {
      const attesting = r.typeHint === 'ATTESTATION';
      const value = attesting ? r.balance : r.amount;
      return '<tr><td>' + r.date + '</td>' +
        '<td>' + (attesting ? 'had' : '') + '</td>' +
        '<td class="num">' + (value / 100).toFixed(2) + '</td>' +
        '<td>' + (attesting ? '' : escapeHtml(r.description || r.rawDescription)) + '</td></tr>';
    }).join('');
  } catch { /* the list is a convenience; the form is the page */ }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

$('mode-spend').addEventListener('click', () => setMode('spend'));
$('mode-attest').addEventListener('click', () => setMode('attest'));
$('form').addEventListener('submit', submit);
$('date').value = new Date().toISOString().slice(0, 10);
$('status-text').textContent = 'ready';
loadAccounts().then(refresh).catch((e) => say(String(e), false));
