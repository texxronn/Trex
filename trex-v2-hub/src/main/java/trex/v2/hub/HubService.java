package trex.v2.hub;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Registry;
import trex.v2.core.config.User;
import trex.v2.core.derive.Reconciliation;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.LedgerPage;
import trex.v2.hub.api.ReconcileResponse;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.hub.api.ReviewRow;
import trex.v2.hub.api.StatusResponse;
import trex.v2.hub.api.TransferJson;
import trex.v2.hub.api.UnitJson;
import trex.v2.index.IndexLock;
import trex.v2.index.Indexer;
import trex.v2.log.ConfigLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The index owner (V2-PROPOSAL.md §7.4). One process holds the {@link IndexLock}, applies the
 * journal to the mirror, re-derives on any journal or config change, and serves reads through a
 * pool. It writes the index and never the journal; the sequencer remains the only writer of the log.
 *
 * <p>Restart is safe: it catches up from the persisted offset. A missing, corrupt or
 * schema-mismatched index is rebuilt rather than repaired.
 */
public final class HubService implements HubApi, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HubService.class);

    private final HubConfig config;
    private final IndexLock lock;
    private final Indexer indexer;
    private final HubQueries reads;
    private final IndexRefresher refresher;
    private final AtomicBoolean closed = new AtomicBoolean();
    private HttpServer server;

    private HubService(HubConfig config, IndexLock lock, Indexer indexer, HubQueries reads,
                       IndexRefresher refresher) {
        this.config = config;
        this.lock = lock;
        this.indexer = indexer;
        this.reads = reads;
        this.refresher = refresher;
    }

    public static HubService start(HubConfig config) {
        ConfigLoader.Loaded loaded = ConfigLoader.load(config.configDir());
        IndexLock lock = IndexLock.acquire(config.index());
        Indexer indexer = null;
        HubQueries reads = null;
        IndexRefresher refresher = null;
        try {
            indexer = Indexer.open(config.index(), loaded.config());
            reads = new HubQueries(config.index(), 4);
            refresher = new IndexRefresher(config.journal(), config.configDir(), indexer,
                loaded.config(), config.refreshDebounceMs());
            HubService service = new HubService(config, lock, indexer, reads, refresher);
            service.server = HubHttpApi.start(config.host(), config.port(), service);
            log.info("trex hub listening on {}:{}; journal {}; index {}",
                config.host(), service.server.getAddress().getPort(), config.journal(), config.index());
            return service;
        } catch (IOException e) {
            closeQuietly(lock, indexer, reads, refresher);
            throw new UncheckedIOException("cannot start the hub", e);
        } catch (RuntimeException e) {
            closeQuietly(lock, indexer, reads, refresher);
            throw e;
        }
    }

    private static void closeQuietly(IndexLock lock, Indexer indexer, HubQueries reads, IndexRefresher refresher) {
        try {
            if (refresher != null) {
                refresher.close();
            }
            if (reads != null) {
                reads.close();
            }
            if (indexer != null) {
                indexer.close();
            }
        } finally {
            lock.close();
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public HeadResponse head() {
        long head = journalSize();
        long offset = reads.offset();
        return new HeadResponse(reads.logHeadN(), offset, head, Math.max(0, head - offset));
    }

    @Override
    public StatusResponse status() {
        DeriveConfig c = refresher.config();
        long head = journalSize();
        long offset = reads.offset();
        return new StatusResponse(reads.logHeadN(), offset, head, Math.max(0, head - offset),
            reads.counts(), reads.reviewByKind(), c.configRevision(),
            DeriveConfig.DERIVE_VERSION, DeriveConfig.HASH_VERSION);
    }

    @Override
    public RefdataResponse refdata() {
        DeriveConfig c = refresher.config();
        Registry registry = c.registry();
        List<RefdataResponse.AccountJson> accounts = registry.accounts().values().stream()
            .map(a -> new RefdataResponse.AccountJson(a.ref(), a.currency(), a.balanceSource().wire(),
                a.settlementWindowDays()))
            .toList();
        List<RefdataResponse.UserJson> users = registry.users().values().stream()
            .map(u -> new RefdataResponse.UserJson(u.id(), u.name(), u.active(), u.cadence()))
            .toList();
        return new RefdataResponse(accounts, users, c.categories().declared(), c.configRevision(),
            DeriveConfig.DERIVE_VERSION, DeriveConfig.HASH_VERSION);
    }

    @Override
    public LedgerPage ledger(BlotterQuery query) {
        return reads.ledger(query);
    }

    @Override
    public List<ReviewRow> review(String kind) {
        return reads.review(kind);
    }

    @Override
    public List<TransferJson> transfers() {
        return reads.transfers();
    }

    @Override
    public List<UnitJson> units() {
        return reads.units();
    }

    @Override
    public ReconcileResponse reconcile() {
        DeriveConfig c = refresher.config();
        Set<String> declared = c.registry().accounts().values().stream()
            .filter(a -> a.balanceSource() == BalanceSource.DECLARED)
            .map(Account::ref)
            .collect(Collectors.toCollection(TreeSet::new));
        Map<String, Reconciliation.AccountResult> results = Reconciliation.reconcile(reads.currentFacts(), declared);
        List<ReconcileResponse.AccountJson> accounts = results.values().stream()
            .map(r -> new ReconcileResponse.AccountJson(r.accountRef(),
                r.status().name().toLowerCase(java.util.Locale.ROOT), r.reconcilable(), r.balances(),
                r.opening(), r.closing(), r.sum(), r.gap()))
            .toList();
        boolean ok = accounts.stream().allMatch(ReconcileResponse.AccountJson::balances);
        return new ReconcileResponse(ok, accounts);
    }

    private long journalSize() {
        try {
            return Files.exists(config.journal()) ? Files.size(config.journal()) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        refresher.close();
        reads.close();
        indexer.close();
        lock.close();
    }
}
