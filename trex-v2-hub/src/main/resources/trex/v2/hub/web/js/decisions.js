// Decision drafts, one function per action, so a mode never hand-writes a payload shape.

const envelope = (action, ctx, comment) => ({
  action,
  actor: 'user',
  user: ctx.user,
  comment: comment || null,
});

export const decisions = {
  markExternal: (ctx, id, comment) => ({ ...envelope('MARK_EXTERNAL', ctx, comment), externalId: id }),
  pin: (ctx, ids, category, comment) => ({ ...envelope('PIN', ctx, comment), externalIds: ids, category }),
  unpin: (ctx, ids, comment) => ({ ...envelope('UNPIN', ctx, comment), externalIds: ids }),
  dismiss: (ctx, item, ids, comment) => ({ ...envelope('DISMISS', ctx, comment), item, externalIds: ids }),
  pair: (ctx, legA, legB, comment) => ({ ...envelope('PAIR', ctx, comment), legA, legB }),
  unpair: (ctx, legA, legB, comment) => ({ ...envelope('UNPAIR', ctx, comment), legA, legB }),
  markNoop: (ctx, id, reason) => ({ ...envelope('MARK_NOOP', ctx, reason), externalId: id, reason }),
  unmarkNoop: (ctx, id, comment) => ({ ...envelope('UNMARK_NOOP', ctx, comment), externalId: id }),
};
