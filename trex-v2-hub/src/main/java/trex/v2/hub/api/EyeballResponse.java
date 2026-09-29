package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The eyeball walk for one period and user (V2-PROPOSAL.md §10.3): the open review items scoped to
 * the period, the anomaly checks, and the day-by-day transactions. {@code asOf} is an explicit
 * input, so the same call at the same {@code asOf} returns the same walk.
 */
public record EyeballResponse(String period, String user, LocalDate asOf,
                              List<ReviewRow> openItems, List<EyeballAnomaly> anomalies,
                              List<EyeballDay> days) {}
