// The hub's read and decision API. One place knows the URLs; modes never fetch directly.

async function request(method, path, body) {
  const options = { method, headers: {} };
  if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!response.ok) {
    const message = data && data.error ? data.error : (typeof data === 'string' ? data : response.statusText);
    const error = new Error(message || `HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data;
}

export const api = {
  status: () => request('GET', '/api/status'),
  refdata: () => request('GET', '/api/refdata'),
  ledger: (params = {}) => request('GET', '/api/ledger?' + new URLSearchParams(params)),
  review: (kind) => request('GET', '/api/review' + (kind ? '?' + new URLSearchParams({ kind }) : '')),
  transfers: () => request('GET', '/api/transfers'),
  units: () => request('GET', '/api/units'),
  reconcile: () => request('GET', '/api/reconcile'),
  acks: () => request('GET', '/api/acks'),
  postAck: (body) => request('POST', '/api/acks', body),
  ackDiff: (user, period) => request('GET', '/api/acks/diff?' + new URLSearchParams({ user, period })),
  decisions: (asOfN, decisions) => request('POST', '/api/decisions', { asOfN, decisions }),
  reflowPreview: (categories) => request('POST', '/api/reflow/preview', { categories }),
  categoriesYaml: () => request('GET', '/api/config/categories'),
  saveCategories: (categories) => request('PUT', '/api/config/categories', { categories }),
};
