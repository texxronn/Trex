// The app shell: load the reference data and the acting user, mount one mode at a time, and keep
// the status strip fresh from the SSE stream.

import { api } from './api.js';
import { connect } from './sse.js';
import { toast } from './toast.js';
import * as status from './status.js';
import * as blotter from './blotter.js';
import * as review from './review.js';
import * as eyeball from './eyeball.js';
import * as rules from './rules.js';
import * as accounts from './accounts.js';
import * as chains from './chains.js';
import * as jobs from './jobs.js';

const modes = { blotter, review, eyeball, rules, accounts, chains, jobs };

const ctx = {
  n: 0,
  configRevision: null,
  user: localStorage.getItem('trex.user') || 'ron',
  refdata: { users: [], accounts: [], categories: [] },
  onUserChange: () => {},
};

let activeMode = null;

async function boot() {
  initTheme();
  try {
    ctx.refdata = await api.refdata();
  } catch (error) {
    toast('Cannot reach the hub: ' + (error.message || error), 'bad');
  }
  if (ctx.refdata.users.length && !ctx.refdata.users.some((u) => u.id === ctx.user)) {
    ctx.user = ctx.refdata.users[0].id;
  }
  ctx.onUserChange = refreshActive;

  status.mount(document.getElementById('status'), ctx);
  await status.refresh();

  window.addEventListener('hashchange', route);
  route();

  connect({
    onSnapshot: (head) => {
      ctx.n = head.n;
      status.refresh();
      refreshActive();
    },
    onDelta: (change) => {
      ctx.n = change.n;
      ctx.configRevision = change.configRevision;
      status.refresh();
      refreshActive();
    },
  });
}

function route() {
  const raw = location.hash.slice(1) || 'blotter';
  const [requested, query] = raw.split('?');
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
