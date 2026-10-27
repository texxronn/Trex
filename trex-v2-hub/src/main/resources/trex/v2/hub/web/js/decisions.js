// Decision drafts, one function per action, so a mode never hand-writes a payload shape. Each draft
// declares the acting user it got from /api/whoami: with sign-in off the hub has no session and this
// declared user is the identity, exactly as the pre-sign-in UI sent it; with sign-in on the hub
// stamps the session's user and refuses a draft naming anyone else with a 403 (V2-SPEC.md §9.5), so
// the declared user is then redundant and `ctx` still earns its place in the (ctx, …) shape.

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
  // RETIRE carries its reason in `reason`, not `comment` (Decision.Retire has no comment).
  retire: (ctx, id, reason) => ({ ...envelope('RETIRE', ctx, null), externalId: id, reason }),
  note: (ctx, id, text) => ({ ...envelope('NOTE', ctx, null), externalId: id, text }),
  attachAccount: (ctx, ids, account, comment) => ({ ...envelope('ATTACH_ACCOUNT', ctx, comment),
    externalIds: ids, account }),
  // A statement account closed at a declared balance (V2-CLOSE-ACCOUNT-PLAN.md): the settling legs
  // pair with it directly, so the account's last movement is the one a person named.
  closeAccount: (ctx, account, closedAt, closingBalance, settles, comment) => ({
    ...envelope('CLOSE_ACCOUNT', ctx, comment), account, closedAt, closingBalance,
    externalIds: settles || [],
  }),
  declareCommitment: (ctx, fields) => {
    const payment = fields.payment || 'auto';
    return { ...envelope('DECLARE_COMMITMENT', ctx, fields.comment),
      commitmentId: fields.commitmentId, name: fields.name, direction: fields.direction,
      cadence: fields.cadence, amountKind: fields.amountKind, kind: fields.kind,
      matches: fields.matches, amount: fields.amount, anchor: fields.anchor,
      fromCandidate: fields.fromCandidate, payment,
      ...(payment === 'manual' && fields.remindDays != null ? { remindDays: fields.remindDays } : {}),
    };
  },
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
