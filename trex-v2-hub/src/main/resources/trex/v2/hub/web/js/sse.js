// The SSE stream: a snapshot at the current instant, then a delta whenever the index moves.

export function connect({ onSnapshot, onDelta, onState }) {
  const source = new EventSource('/api/events');
  source.addEventListener('snapshot', (event) => onSnapshot(JSON.parse(event.data)));
  source.addEventListener('delta', (event) => onDelta(JSON.parse(event.data)));
  source.addEventListener('open', () => onState && onState('live'));
  source.addEventListener('error', () => onState && onState('reconnecting'));
  return source;
}
