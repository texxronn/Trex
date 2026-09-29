package trex.v2.hub.api;

import java.time.LocalDate;

/**
 * One anomaly from the eyeball walk (V2-PROPOSAL.md §10.3 point 2). {@code kind} names the check;
 * {@code subject} is the external id the finding links to (an account ref for {@code ACCOUNT_SILENT},
 * or null when the finding is about a stem rather than a row).
 */
public record EyeballAnomaly(String kind, String subject, LocalDate date, String accountRef,
                             Long amount, String detail) {}
