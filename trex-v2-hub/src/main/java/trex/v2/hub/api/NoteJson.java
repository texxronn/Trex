package trex.v2.hub.api;

import java.time.Instant;

/** One note in a row's thread (V2-PROPOSAL.md §6.2 {@code NOTE}): the text, who wrote it, and when. */
public record NoteJson(String externalId, String text, long decisionN, String user, Instant at) {}
