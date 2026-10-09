// Transient notifications. A 409 (stale view) is called out specially, because the fix is to
// refresh rather than to retry. A third argument makes the toast a button that opens somewhere
// (V2-QOL-IMPROVEMENTS-PLAN.md §1) — one click target at a time, cleared whenever a toast is shown.

let timer = null;
let clickHandler = null;

export function toast(message, kind = '', onClick = null) {
  const node = document.getElementById('toast');
  if (!node) return;
  node.textContent = message;
  if (clickHandler) {
    node.removeEventListener('click', clickHandler);
  }
  clickHandler = typeof onClick === 'function' ? onClick : null;
  if (clickHandler) {
    node.addEventListener('click', clickHandler);
  }
  node.className = 'toast ' + kind + (clickHandler ? ' clickable' : '');
  node.hidden = false;
  clearTimeout(timer);
  timer = setTimeout(() => { node.hidden = true; }, kind === 'bad' ? 6000 : 3000);
}

export function reportError(error) {
  if (error && error.status === 409) {
    toast('The view moved while you were deciding — refreshed. Try again.', 'bad');
  } else if (error && error.status === 422 && error.message) {
    toast('Rejected: ' + error.message, 'bad');
  } else {
    toast((error && error.message) || 'Something went wrong', 'bad');
  }
}
