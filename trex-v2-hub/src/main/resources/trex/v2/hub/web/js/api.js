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
  projection: () => request('GET', '/api/projection'),
  reconcile: () => request('GET', '/api/reconcile'),
  chains: (account) => request('GET', '/api/chains'
    + (account ? '?' + new URLSearchParams({ account }) : '')),
  acks: () => request('GET', '/api/acks'),
  eyeball: (period, user, opts = {}) => request('GET', '/api/eyeball?' + new URLSearchParams({
    period,
    user,
    ...(opts.asOf ? { asOf: opts.asOf } : {}),
    ...(opts.bucket ? { bucket: opts.bucket } : {}),
  })),
  postAck: (body) => request('POST', '/api/acks', body),
  decisions: (asOfN, decisions) => request('POST', '/api/decisions', { asOfN, decisions }),
  reflowPreview: (categories) => request('POST', '/api/reflow/preview', { categories }),
  categoriesYaml: () => request('GET', '/api/config/categories'),
  saveCategories: (categories) => request('PUT', '/api/config/categories', { categories }),
  workbook: () => request('GET', '/api/workbook'),
  ingests: () => request('GET', '/api/ingests'),
  accounts: (params = {}) => request('GET', '/api/accounts?' + new URLSearchParams(params)),
  jobs: () => request('GET', '/api/jobs'),
  jobAdapters: () => request('GET', '/api/jobs/adapters'),
  staging: () => request('GET', '/api/jobs/staging'),
  jobRuns: () => request('GET', '/api/jobs/runs'),
  runDetail: (id) => request('GET', '/api/jobs/runs/' + encodeURIComponent(id)),
  runJob: (name, params) => request('POST', `/api/jobs/${encodeURIComponent(name)}/runs`, { params }),
  cancelRun: (id) => request('POST', '/api/jobs/runs/' + encodeURIComponent(id) + '/cancel', {}),
  clearStaged: (name) => request('POST', '/api/jobs/staging/clear?name=' + encodeURIComponent(name), {}),
};

/** Upload one file to the staging inbox, with progress. XHR, because fetch cannot report it. */
export function uploadStaged(file, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/jobs/staging?name=' + encodeURIComponent(file.name));
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = (event) => {
      if (onProgress && event.lengthComputable) onProgress(event.loaded / event.total);
    };
    xhr.onload = () => {
      let data = null;
      try { data = JSON.parse(xhr.responseText); } catch { /* empty */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else reject(new Error((data && data.error) || `HTTP ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error('upload failed'));
    xhr.send(file);
  });
}
