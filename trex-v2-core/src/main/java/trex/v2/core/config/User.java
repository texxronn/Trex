package trex.v2.core.config;

/**
 * One person in {@code users.yaml} (V2-PROPOSAL.md §6.6). Attribution, not authorization:
 * the id is stamped into decisions forever and is never reused. {@code cadence} phrases a
 * reminder only and never gates anything; it may be null.
 */
public record User(String id, String name, boolean active, String cadence) {

    public User {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("user id is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("user " + id + ": name is required");
        }
    }
}
