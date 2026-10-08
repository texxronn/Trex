package trex.v2.hub.api;

import java.util.List;

/**
 * An effective {@code DISMISS} decision (V2-PROPOSAL.md §6.2, §9.9.F), so the reason a review item
 * was silenced stays visible after the item itself has left the queue.
 */
public record DismissalJson(String item, List<String> externalIds, String comment, String user,
                            String at, long n) {}
