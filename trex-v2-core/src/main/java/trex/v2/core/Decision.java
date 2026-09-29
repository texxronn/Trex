package trex.v2.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A decision: what a human concluded, small, explicit, append-only and revocable
 * (V2-PROPOSAL.md §6.2). The complete action set is §6.7; anything absent is not in the journal.
 *
 * <p>Every decision carries {@code actor} ({@code user | migrated | system}) and, when a person
 * acted, {@code user}; {@code at} is the wall-clock instant the decision was issued. Nothing here
 * is a full copy of anything — the derivation reads these, never the other way round.
 *
 * <p>Which decisions are effective is derived from their order and their {@code REVOKE}s, never
 * stored on a line and never evaluated by the sequencer (§6.3, §9.8).
 */
public sealed interface Decision extends LogLine
    permits Decision.Pair, Decision.Unpair, Decision.MarkExternal, Decision.Settle,
            Decision.Dismiss, Decision.Pin, Decision.Unpin, Decision.Supersede,
            Decision.Retire, Decision.Revoke, Decision.UserAck, Decision.Note {

    long n();

    Action action();

    Actor actor();

    /** The acting user id, or null for {@code system}/{@code migrated}. */
    String user();

    Instant at();

    @Override
    default String kind() {
        return "decision";
    }

    /** This is a transfer between these two legs. Overrides the matcher. */
    record Pair(long n, String legA, String legB, String comment, Actor actor, String user, Instant at)
        implements Decision {
        public Pair {
            require(legA, "legA");
            require(legB, "legB");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.PAIR;
        }
    }

    /** Not a transfer; never auto-match this pair. */
    record Unpair(long n, String legA, String legB, String comment, Actor actor, String user, Instant at)
        implements Decision {
        public Unpair {
            require(legA, "legA");
            require(legB, "legB");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.UNPAIR;
        }
    }

    /** An ordinary transaction, not a transfer leg; leave it alone. */
    record MarkExternal(long n, String externalId, String comment, Actor actor, String user, Instant at)
        implements Decision {
        public MarkExternal {
            require(externalId, "externalId");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.MARK_EXTERNAL;
        }
    }

    /** This pending observation was settled by that posted row. */
    record Settle(long n, String pendingId, String postedId, String comment, Actor actor, String user, Instant at)
        implements Decision {
        public Settle {
            require(pendingId, "pendingId");
            require(postedId, "postedId");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.SETTLE;
        }
    }

    /** Silence review item(s) of that kind for those ids; {@code item} is a review kind (§7.2). */
    record Dismiss(long n, String item, List<String> externalIds, String comment, Actor actor, String user, Instant at)
        implements Decision {
        public Dismiss {
            require(item, "item");
            Objects.requireNonNull(externalIds, "externalIds");
            externalIds = List.copyOf(externalIds);
            require(!externalIds.isEmpty(), "externalIds must not be empty");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.DISMISS;
        }
    }

    /** Those ids are this category, regardless of the rules. The latest event mentioning an id wins. */
    record Pin(long n, List<String> externalIds, String category, String comment, Actor actor, String user, Instant at)
        implements Decision {
        public Pin {
            Objects.requireNonNull(externalIds, "externalIds");
            externalIds = List.copyOf(externalIds);
            require(!externalIds.isEmpty(), "externalIds must not be empty");
            require(category, "category");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.PIN;
        }
    }

    /** Release those ids back to rule evaluation. */
    record Unpin(long n, List<String> externalIds, String comment, Actor actor, String user, Instant at)
        implements Decision {
        public Unpin {
            Objects.requireNonNull(externalIds, "externalIds");
            externalIds = List.copyOf(externalIds);
            require(!externalIds.isEmpty(), "externalIds must not be empty");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.UNPIN;
        }
    }

    /** A re-parse or correction replaces one fact with another. */
    record Supersede(long n, String fromId, String toId, String reason, Actor actor, String user, Instant at)
        implements Decision {
        public Supersede {
            require(fromId, "fromId");
            require(toId, "toId");
            require(reason, "reason");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.SUPERSEDE;
        }
    }

    /** The fact no longer counts and has no replacement. */
    record Retire(long n, String externalId, String reason, Actor actor, String user, Instant at)
        implements Decision {
        public Retire {
            require(externalId, "externalId");
            require(reason, "reason");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.RETIRE;
        }
    }

    /** Undo decision {@code n = target}; the general escape hatch. */
    record Revoke(long n, long target, String comment, Actor actor, String user, Instant at)
        implements Decision {
        public Revoke {
            require(target > 0, "target must be a positive decision n");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.REVOKE;
        }
    }

    /** "I have eyeballed this period; the derived state was X." The user is on the line. */
    record UserAck(long n, String period, long throughN, String configRevision, String deriveVersion,
                   String hashVersion, String stateHash, String comment, Actor actor, String user, Instant at)
        implements Decision {
        public UserAck {
            require(period, "period");
            require(configRevision, "configRevision");
            require(deriveVersion, "deriveVersion");
            require(hashVersion, "hashVersion");
            require(stateHash, "stateHash");
            require(actor, "actor");
            require(user, "user");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.USER_ACK;
        }
    }

    /** Free annotation; never identity, never logic. */
    record Note(long n, String externalId, String text, Actor actor, String user, Instant at)
        implements Decision {
        public Note {
            require(text, "text");
            require(actor, "actor");
            require(at, "at");
        }

        @Override
        public Action action() {
            return Action.NOTE;
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required on a decision");
        }
    }

    private static void require(Object value, String name) {
        Objects.requireNonNull(value, name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
