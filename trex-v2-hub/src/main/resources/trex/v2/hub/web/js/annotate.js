// The annotate popup (V2-PROPOSAL.md §6.2 NOTE): a free-text note appended as one decision per
// target row. A cluster or a multi-row selection fans out to one NOTE per id, in a single batch, so
// the group gesture is one append and each row's thread stays self-contained.

import { api } from './api.js';
import { decisions } from './decisions.js';
import { openPrompt } from './dialog.js';
import { reportError, toast } from './toast.js';

export function openAnnotate(ctx, targets, onDone) {
  const ids = (targets && targets.ids) || [];
  if (!ids.length) return;
  openPrompt({
    title: ids.length > 1 ? `Annotate ${ids.length} rows` : 'Annotate',
    summary: targets.summary,
    label: 'Note',
    placeholder: 'Add a note\u2026',
    multiline: true,
    confirm: 'Save',
  }, async (body) => {
    if (!body) {
      toast('A note needs some text', 'bad');
      return;
    }
    try {
      await api.decisions(ctx.n, ids.map((id) => decisions.note(ctx, id, body)));
      toast(ids.length > 1 ? `Noted ${ids.length} rows` : 'Note saved');
    } catch (error) {
      reportError(error);
    }
    if (onDone) onDone();
  });
}
