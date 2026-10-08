package trex.v2.core.derive;

import java.time.Instant;

/**
 * One effective note (V2-PROPOSAL.md §6.2 {@code NOTE}, §7.2 {@code note_current}): a free
 * annotation a person left on a transaction. Unlike a {@link PinRow} it is not a classification, so
 * notes accumulate — every effective note is kept, ordered by the decision that wrote it. Ids are
 * resolved through the supersession map, so a {@code SUPERSEDE} carries its notes to the current
 * row. Never identity, never logic.
 */
public record NoteRow(String externalId, String text, long decisionN, String userId, Instant at) {}
