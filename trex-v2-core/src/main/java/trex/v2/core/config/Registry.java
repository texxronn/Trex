package trex.v2.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The account and user registries (V2-PROPOSAL.md §6.6, §9.9 P3). Pure lookup data; a load
 * failure (unknown ref, missing user) is a startup error, not a derivation result.
 *
 * <p>Iteration order is insertion order, and every derivation that emits a list sorts its keys
 * explicitly, so nothing depends on map internals.
 */
public record Registry(Map<String, Account> accounts, Map<String, User> users) {

    public Registry {
        accounts = Collections.unmodifiableMap(new LinkedHashMap<>(accounts));
        users = Collections.unmodifiableMap(new LinkedHashMap<>(users));
        if (users.isEmpty()) {
            throw new IllegalArgumentException("users.yaml declares no users (V2-PROPOSAL.md §6.6)");
        }
    }

    public Optional<Account> findAccount(String ref) {
        return Optional.ofNullable(accounts.get(ref));
    }

    public Account account(String ref) {
        return findAccount(ref).orElseThrow(
            () -> new IllegalArgumentException("unknown accountRef '" + ref + "'"));
    }

    public Optional<User> findUser(String id) {
        return Optional.ofNullable(users.get(id));
    }

    public User user(String id) {
        return findUser(id).orElseThrow(
            () -> new IllegalArgumentException("unknown user '" + id + "'"));
    }
}
