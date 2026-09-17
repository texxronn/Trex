package trex.sequencer.ingest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The account registry (the spine). The sequencer uses it only to validate and stamp currency. */
public final class AccountRegistry {

    private final Map<String, Account> accounts;

    public AccountRegistry(List<Account> accounts) {
        Map<String, Account> byRef = new LinkedHashMap<>();
        for (Account a : accounts) {
            if (byRef.putIfAbsent(a.ref(), a) != null) {
                throw new IllegalArgumentException("duplicate account ref: " + a.ref());
            }
        }
        this.accounts = Map.copyOf(byRef);
    }

    public Optional<Account> find(String ref) {
        return ref == null ? Optional.empty() : Optional.ofNullable(accounts.get(ref));
    }
}
