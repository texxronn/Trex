package trex.v2.core.derive;

import java.time.Instant;

/**
 * One effective {@code NOTE_COMMITMENT} (V2-COMMITMENTS-PLAN.md §2.6, §2.7
 * {@code commitment_note}): a free annotation on a commitment. Like {@link NoteRow} it is not a
 * classification, so notes accumulate — every effective decision is kept, ordered by {@code n},
 * and only {@code REVOKE} removes one. Never identity, never logic; notes on a retired commitment
 * remain part of its history.
 */
public record CommitmentNote(long decisionN, String commitmentId, String text, String userId, Instant at) {}
