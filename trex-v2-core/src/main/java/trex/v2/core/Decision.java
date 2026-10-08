package trex.v2.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A decision: what a human concluded, small, explicit, append-only and revocable
 * (V2-PROPOSAL.md §6.2). The complete action set is §6.7; anything absent is not in the journal.
 *
 * <p>Every decision carries {@code actor} ({@code user | migrated | system}) and, when a person
 * acted, {@code user}; the header (§6) lives in the {@link Envelope}, and {@code at} is
 * {@code envelope.atMs} — the instant the decision was issued. Nothing here is a full copy of
 * anything — the derivation reads these, never the other way round.
 *
 * <p>Each record also has a quick-construction constructor taking the legacy {@code (n, …, at)}
 * shape, which stamps a default header (§6). Tests and the v1 importer use it; production code
 * builds an {@link Envelope} explicitly.
 *
 * <p>Which decisions are effective is derived from their order and their {@code REVOKE}s, never
 * stored on a line and never evaluated by the sequencer (§6.3, §9.8).
 */
public sealed interface Decision extends LogLine
    permits Decision.Pair, Decision.Unpair, Decision.MarkExternal, Decision.Settle,
            Decision.Dismiss, Decision.Pin, Decision.Unpin, Decision.Supersede,
            Decision.Retire, Decision.MarkNoop, Decision.UnmarkNoop, Decision.AttachAccount,
            Decision.Revoke,
            Decision.UserAck, Decision.UserUnack, Decision.Note {

    /** The namespaced wire kind. */
    String KIND = "trex.decision";

    Action action();

    Actor actor();

    /** The acting user id, or null for {@code system}/{@code migrated}. */
    String user();

    /** The instant the decision was issued — the envelope's {@code atMs}. */
    default Instant at() {
        return envelope().instant();
    }

    /** This is a transfer between these two legs. Overrides the matcher. */
    record Pair(Envelope envelope, String legA, String legB, String comment, Actor actor, String user)
        implements Decision {
        public Pair {
            Envelope.require(envelope);
            require(legA, "legA");
            require(legB, "legB");
            require(actor, "actor");
        }

        public Pair(long n, String legA, String legB, String comment, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), legA, legB, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.PAIR;
        }
    }

    /** Not a transfer; never auto-match this pair. */
    record Unpair(Envelope envelope, String legA, String legB, String comment, Actor actor, String user)
        implements Decision {
        public Unpair {
            Envelope.require(envelope);
            require(legA, "legA");
            require(legB, "legB");
            require(actor, "actor");
        }

        public Unpair(long n, String legA, String legB, String comment, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), legA, legB, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.UNPAIR;
        }
    }

    /** An ordinary transaction, not a transfer leg; leave it alone. */
    record MarkExternal(Envelope envelope, String externalId, String comment, Actor actor, String user)
        implements Decision {
        public MarkExternal {
            Envelope.require(envelope);
            require(externalId, "externalId");
            require(actor, "actor");
        }

        public MarkExternal(long n, String externalId, String comment, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), externalId, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.MARK_EXTERNAL;
        }
    }

    /** This pending observation was settled by that posted row. */
    record Settle(Envelope envelope, String pendingId, String postedId, String comment, Actor actor, String user)
        implements Decision {
        public Settle {
            Envelope.require(envelope);
            require(pendingId, "pendingId");
            require(postedId, "postedId");
            require(actor, "actor");
        }

        public Settle(long n, String pendingId, String postedId, String comment, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), pendingId, postedId, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.SETTLE;
        }
    }

    /** Silence review item(s) of that kind for those ids; {@code item} is a review kind (§7.2). */
    record Dismiss(Envelope envelope, String item, List<String> externalIds, String comment, Actor actor, String user)
        implements Decision {
        public Dismiss {
            Envelope.require(envelope);
            require(item, "item");
            Objects.requireNonNull(externalIds, "externalIds");
            externalIds = List.copyOf(externalIds);
            require(!externalIds.isEmpty(), "externalIds must not be empty");
            require(actor, "actor");
        }

        public Dismiss(long n, String item, List<String> externalIds, String comment, Actor actor, String user,
                       Instant at) {
            this(Envelope.stamped(n, KIND, at), item, externalIds, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.DISMISS;
        }
    }

    /** Those ids are this category, regardless of the rules. The latest event mentioning an id wins. */
    record Pin(Envelope envelope, List<String> externalIds, String category, String comment, Actor actor, String user)
        implements Decision {
        public Pin {
            Envelope.require(envelope);
            Objects.requireNonNull(externalIds, "externalIds");
            externalIds = List.copyOf(externalIds);
            require(!externalIds.isEmpty(), "externalIds must not be empty");
            require(category, "category");
            require(actor, "actor");
        }

        public Pin(long n, List<String> externalIds, String category, String comment, Actor actor, String user,
                   Instant at) {
            this(Envelope.stamped(n, KIND, at), externalIds, category, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.PIN;
        }
    }

    /** Release those ids back to rule evaluation. */
    record Unpin(Envelope envelope, List<String> externalIds, String comment, Actor actor, String user)
        implements Decision {
        public Unpin {
            Envelope.require(envelope);
            Objects.requireNonNull(externalIds, "externalIds");
            externalIds = List.copyOf(externalIds);
            require(!externalIds.isEmpty(), "externalIds must not be empty");
            require(actor, "actor");
        }

        public Unpin(long n, List<String> externalIds, String comment, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), externalIds, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.UNPIN;
        }
    }

    /** A re-parse or correction replaces one fact with another. */
    record Supersede(Envelope envelope, String fromId, String toId, String reason, Actor actor, String user)
        implements Decision {
        public Supersede {
            Envelope.require(envelope);
            require(fromId, "fromId");
            require(toId, "toId");
            require(reason, "reason");
            require(actor, "actor");
        }

        public Supersede(long n, String fromId, String toId, String reason, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), fromId, toId, reason, actor, user);
        }

        @Override
        public Action action() {
            return Action.SUPERSEDE;
        }
    }

    /** The fact no longer counts and has no replacement. */
    record Retire(Envelope envelope, String externalId, String reason, Actor actor, String user)
        implements Decision {
        public Retire {
            Envelope.require(envelope);
            require(externalId, "externalId");
            require(reason, "reason");
            require(actor, "actor");
        }

        public Retire(long n, String externalId, String reason, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), externalId, reason, actor, user);
        }

        @Override
        public Action action() {
            return Action.RETIRE;
        }
    }

    /**
     * Recorded, but not a posting of this account: no chain edge, no unit, no transfer leg, no sum
     * (V2-PROPOSAL.md §6.9). A classification, never a correction — {@code RETIRE} stays reserved
     * for a source claim that must not count at all.
     */
    record MarkNoop(Envelope envelope, String externalId, String reason, Actor actor, String user)
        implements Decision {
        public MarkNoop {
            Envelope.require(envelope);
            require(externalId, "externalId");
            require(reason, "reason");
            require(actor, "actor");
        }

        public MarkNoop(long n, String externalId, String reason, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), externalId, reason, actor, user);
        }

        @Override
        public Action action() {
            return Action.MARK_NOOP;
        }
    }

    /** Return the row to its profile's default; the family inverse of {@code MARK_NOOP}. */
    record UnmarkNoop(Envelope envelope, String externalId, String comment, Actor actor, String user)
        implements Decision {
        public UnmarkNoop {
            Envelope.require(envelope);
            require(externalId, "externalId");
            require(actor, "actor");
        }

        public UnmarkNoop(long n, String externalId, String comment, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), externalId, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.UNMARK_NOOP;
        }
    }

    /** Undo decision {@code n = target}; the general escape hatch. */
    record Revoke(Envelope envelope, long target, String comment, Actor actor, String user)
        implements Decision {
        public Revoke {
            Envelope.require(envelope);
            require(target > 0, "target must be a positive decision n");
            require(actor, "actor");
        }

        public Revoke(long n, long target, String comment, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), target, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.REVOKE;
        }
    }

    /** "I have read this row; its derived content was X." The user is on the line. */
    record UserAck(Envelope envelope, String externalId, String configRevision, String deriveVersion,
                   String hashVersion, String stateHash, String comment, Actor actor, String user)
        implements Decision {
        public UserAck {
            Envelope.require(envelope);
            require(externalId, "externalId");
            require(configRevision, "configRevision");
            require(deriveVersion, "deriveVersion");
            require(hashVersion, "hashVersion");
            require(stateHash, "stateHash");
            require(actor, "actor");
            require(user, "user");
        }

        public UserAck(long n, String externalId, String configRevision, String deriveVersion, String hashVersion,
                       String stateHash, String comment, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), externalId, configRevision, deriveVersion, hashVersion,
                stateHash, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.USER_ACK;
        }
    }

    /** Release that row's read marker for this user; the family inverse of {@code USER_ACK}. */
    record UserUnack(Envelope envelope, String externalId, String comment, Actor actor, String user)
        implements Decision {
        public UserUnack {
            Envelope.require(envelope);
            require(externalId, "externalId");
            require(actor, "actor");
            require(user, "user");
        }

        public UserUnack(long n, String externalId, String comment, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), externalId, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.USER_UNACK;
        }
    }

    /** Free annotation; never identity, never logic. */
    record Note(Envelope envelope, String externalId, String text, Actor actor, String user)
        implements Decision {
        public Note {
            Envelope.require(envelope);
            require(text, "text");
            require(actor, "actor");
        }

        public Note(long n, String externalId, String text, Actor actor, String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), externalId, text, actor, user);
        }

        @Override
        public Action action() {
            return Action.NOTE;
        }
    }

    /**
     * These transfer-shaped legs are transfers to/from {@code account}; the contra is not held
     * (V2-PROPOSAL.md §6.10). A conclusion a person reaches when a counterparty's statements are
     * gone, so the leg is a transfer with an account side — never an invented fact. The account must
     * be a clearing account.
     */
    record AttachAccount(Envelope envelope, List<String> externalIds, String account, String comment,
                         Actor actor, String user) implements Decision {
        public AttachAccount {
            Envelope.require(envelope);
            Objects.requireNonNull(externalIds, "externalIds");
            externalIds = List.copyOf(externalIds);
            require(!externalIds.isEmpty(), "externalIds must not be empty");
            require(account, "account");
            require(actor, "actor");
        }

        public AttachAccount(long n, List<String> externalIds, String account, String comment, Actor actor,
                             String user, Instant at) {
            this(Envelope.stamped(n, KIND, at), externalIds, account, comment, actor, user);
        }

        @Override
        public Action action() {
            return Action.ATTACH_ACCOUNT;
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
