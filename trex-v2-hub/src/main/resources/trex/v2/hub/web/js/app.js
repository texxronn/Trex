// The app shell: load the reference data and the signed-in identity, mount one mode at a time,
// and keep the status strip fresh from the SSE stream.

import { api } from './api.js';
import { connect } from './sse.js';
import { toast } from './toast.js';
import * as status from './status.js';
import * as since from './since.js';
import * as blotter from './blotter.js';
import * as review from './review.js';
import * as expected from './expected.js';
import * as cashflow from './cashflow.js';
import * as eyeball from './eyeball.js';
import * as rules from './rules.js';
import * as accounts from './accounts.js';
import * as mortgage from './mortgage.js';
import * as cash from './cash.js';
import * as chains from './chains.js';
import * as jobs from './jobs.js';
import * as ingestToast from './ingestToast.js';

// #budget is the old name of the Cash flow mode (C-1); old links keep working.
const modeAliases = { budget: 'cashflow' };
const modes = { blotter, review, expected, cashflow, eyeball, rules, accounts, mortgage, cash, chains, jobs };

const ctx = {
  n: 0,
  configRevision: null,
  // The identity is a fact of the connection now (V2-SPEC.md §9.5): it arrives from GET
  // /api/whoami at boot, never from localStorage. With a session the hub stamps it onto every
  // write; with sign-in off this declared user is the identity, exactly as the pre-sign-in UI
  // sent it. `signedIn` tells the strip which of the two it is looking at.
  user: null,
  userName: null,
  signedIn: false,
  refdata: { users: [], accounts: [], categories: [], mortgages: [] },
};

let activeMode = null;

async function boot() {
  initTheme();
  try {
    const who = await api.whoami();
    ctx.user = who.user;
    ctx.userName = who.name;
    ctx.signedIn = Boolean(who.signedIn);
  } catch (error) {
    // A failed read: the shell still renders. A 401 has already sent the browser to the form
    // inside request(); anything else leaves the identity unresolved and this toast speaks.
    toast('Cannot read whoami: ' + (error.message || error), 'bad');
  }
  try {
    ctx.refdata = await api.refdata();
  } catch (error) {
    toast('Cannot reach the hub: ' + (error.message || error), 'bad');
  }

  status.mount(document.getElementById('status'), ctx);
  // The marker order matters (QOL §5): since.js captured the stored marker at module load, and
  // this fetch runs before status.refresh() so the all-clear write has a left to store.
  await since.load(ctx);
  await status.refresh();

  window.addEventListener('hashchange', route);
  route();

  connect({
    onSnapshot: (head) => {
      ctx.n = head.n;
      ingestToast.onSnapshot(head.n);
      status.refresh();
      refreshActive();
    },
    onDelta: (change) => {
      ctx.n = change.n;
      ctx.configRevision = change.configRevision;
      status.refresh();
      // A row-scoped patch carries the changed rows (V2-INCREMENTAL-REFRESH-PLAN.md §4): a mode
      // that can apply it replaces just those rows instead of re-fetching the whole view. A full
      // derive (no patch) or a mode without a patch falls back to the full reload.
      if (change.patch && change.patch.length && activeMode && activeMode.patch) {
        activeMode.patch(change.patch);
      } else {
        refreshActive();
      }
      ingestToast.onDelta(change);
    },
    onState: (state) => {
      // The stream cannot read a 401: on a drop, probe whoami once. A 401 redirects inside
      // request(); every other failure (the hub restarting, a network blip) keeps today's
      // reconnect and must not sign anyone out (V2-AUTH-PLAN.md §4.4).
      if (state === 'reconnecting') api.whoami().catch(() => {});
    },
  });
}

function route() {
  const raw = location.hash.slice(1) || 'blotter';
  const [asked, query] = raw.split('?');
  const requested = modeAliases[asked] || asked;
  const name = modes[requested] ? requested : 'blotter';
  ctx.modeQuery = new URLSearchParams(query || '');
  document.querySelectorAll('nav a').forEach((a) => a.classList.toggle('active', a.dataset.mode === name));
  const main = document.getElementById('main');
  main.replaceChildren();
  try {
    activeMode = modes[name].mount(main, ctx) || null;
  } catch (error) {
    main.textContent = 'Failed to render: ' + (error.message || error);
  }
}

function refreshActive() {
  if (activeMode && activeMode.refresh) {
    activeMode.refresh();
  }
}

/** The theme select mirrors data-theme (set before paint) and persists the choice. */
function initTheme() {
  const select = document.getElementById('theme');
  if (!select) return;
  select.value = document.documentElement.dataset.theme || 'light';
  select.addEventListener('change', () => {
    document.documentElement.dataset.theme = select.value;
    localStorage.setItem('trex.theme', select.value);
  });
}

boot();
