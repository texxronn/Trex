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
  note: (ctx, id, text) => ({ ...envelope('NOTE', ctx, null), externalId: id, text }),
  attachAccount: (ctx, ids, account, comment) => ({ ...envelope('ATTACH_ACCOUNT', ctx, comment),
    externalIds: ids, account }),
  declareCommitment: (ctx, fields) => ({ ...envelope('DECLARE_COMMITMENT', ctx, fields.comment),
    commitmentId: fields.commitmentId, name: fields.name, direction: fields.direction,
    cadence: fields.cadence, amountKind: fields.amountKind, kind: fields.kind,
    matches: fields.matches, amount: fields.amount, anchor: fields.anchor,
    fromCandidate: fields.fromCandidate }),
  retireCommitment: (ctx, commitmentId, endedAt, reason) => ({
    ...envelope('RETIRE_COMMITMENT', ctx, null), commitmentId, endedAt, reason }),
  ignoreRecurring: (ctx, candidate, reason) => ({
    ...envelope('IGNORE_RECURRING', ctx, null), candidate, reason }),
  pinCommitment: (ctx, commitmentId, externalIds, comment) => ({
    ...envelope('PIN_COMMITMENT', ctx, comment), commitmentId, externalIds }),
  unpinCommitment: (ctx, externalIds, comment) => ({
    ...envelope('UNPIN_COMMITMENT', ctx, comment), externalIds }),
  noteCommitment: (ctx, commitmentId, text) => ({
    ...envelope('NOTE_COMMITMENT', ctx, null), commitmentId, text }),
  settleOccurrence: (ctx, commitmentId, dueDates, comment) => ({
    ...envelope('SETTLE_OCCURRENCE', ctx, comment), commitmentId, dueDates }),
  excludeCommitment: (ctx, commitmentId, externalIds, comment) => ({
    ...envelope('EXCLUDE_COMMITMENT', ctx, comment), commitmentId, externalIds }),
  includeCommitment: (ctx, commitmentId, externalIds, comment) => ({
    ...envelope('INCLUDE_COMMITMENT', ctx, comment), commitmentId, externalIds }),
};
