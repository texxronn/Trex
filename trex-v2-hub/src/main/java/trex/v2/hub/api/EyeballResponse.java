package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The eyeball walk for one period and user (V2-PROPOSAL.md §10.3): the open review items scoped to
 * the period, the anomaly checks, and the transactions bucketed by {@code granularity}
 * ({@code day}, {@code week} or {@code month}). {@code asOf} is an explicit input, so the same call
 * at the same {@code asOf} returns the same walk.
 */
public record EyeballResponse(String period, String user, LocalDate asOf, String granularity,
                              List<ReviewRow> openItems, List<EyeballAnomaly> anomalies,
                              List<EyeballBucket> buckets) {}
