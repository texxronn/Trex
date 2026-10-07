// Transient notifications. A 409 (stale view) is called out specially, because the fix is to
// refresh rather than to retry.

let timer = null;

export function toast(message, kind = '') {
  const node = document.getElementById('toast');
  if (!node) return;
  node.textContent = message;
  node.className = 'toast ' + kind;
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
