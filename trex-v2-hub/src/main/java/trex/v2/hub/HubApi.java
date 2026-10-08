package trex.v2.hub;

import trex.v2.hub.api.AckJson;
import trex.v2.hub.api.AckRequest;
import trex.v2.hub.api.AccountsResponse;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.LedgerPage;
import trex.v2.hub.api.ReconcileResponse;
import trex.v2.hub.api.OpeningResponse;
import trex.v2.hub.api.CursorRequest;
import trex.v2.hub.api.CursorResponse;
import trex.v2.hub.api.ProjectionRequest;
import trex.v2.hub.api.ProjectionStateResponse;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.hub.api.ReviewRow;
import trex.v2.hub.api.StatusResponse;
import trex.v2.hub.api.TransferJson;
import trex.v2.hub.api.UnitsResponse;
import trex.v2.core.derive.Opening;
import trex.v2.core.workbook.Workbook;

import java.util.List;

/** The hub's read surface, implemented by {@link HubService} and served by the HTTP layer. */
interface HubApi {

    HeadResponse head();

    StatusResponse status();

    RefdataResponse refdata();

    LedgerPage ledger(BlotterQuery query);

    List<ReviewRow> review(String kind, String account);

    List<TransferJson> transfers();

    /** The commitment registry (V2-COMMITMENTS-PLAN.md §2.8): candidates and declared rows. */
    List<trex.v2.hub.api.CommitmentJson> commitments();

    /**
     * A commitment's activity, by its id (V2-EXPECTED-UX-PLAN.md §7 Stage 2): a candidate's
     * series facts, or a declared commitment's occurrences with the fact each carries.
     */
    List<trex.v2.hub.api.ActivityJson> activity(String commitmentId);

    /**
     * The Expected view (V2-COMMITMENTS-PLAN.md §2.8): a calendar window's occurrences, the
     * arrears backlog and the committed totals by direction. {@code window} is {@code today},
     * {@code week} or {@code month} (the default); {@code asOf} names the day the window is
     * measured from, or null for today.
     */
    trex.v2.hub.api.ExpectedResponse expected(String window, java.time.LocalDate asOf);

    /** The note thread on one id (or every note), oldest first (§6.2 {@code NOTE}). */
    List<trex.v2.hub.api.NoteJson> notes(String externalId);

    /** Effective DISMISS decisions with their reasons, newest first (§9.9.F). */
    List<trex.v2.hub.api.DismissalJson> dismissals();

    UnitsResponse units();

    ReconcileResponse reconcile();

    /** The §6.9 chain health: forks per account and a per-side noop preview. */
    trex.v2.hub.api.ChainsResponse chains(String account);

    OpeningResponse opening();

    List<AckJson> acks();

    trex.v2.hub.api.EyeballResponse eyeball(String period, String user, java.time.LocalDate asOf,
                                            String granularity);

    DecisionOutcome postAck(AckRequest request);

    DecisionOutcome reflowPreview(String categoriesYaml);

    /** The §9.9.C counterpart: what a candidate {@code transfers.yaml} would pot or pair. */
    DecisionOutcome transfersPreview(String transfersYaml);

    java.util.Optional<String> categoriesYaml();

    DecisionOutcome saveCategories(String categoriesYaml);

    java.util.Optional<String> transfersYaml();

    DecisionOutcome saveTransfers(String transfersYaml);

    Workbook.Report workbook();

    ProjectionStateResponse projection();

    DecisionOutcome putProjection(ProjectionRequest request);

    CursorResponse cursors();

    DecisionOutcome putCursors(CursorRequest request);

    DecisionOutcome submitDecisions(DecisionRequest request);

    /** Evidence ids present on facts, so the Jobs view can tick the files already in. */
    java.util.Set<String> ingestedEvidenceIds();

    /** The ingest history, paired from the log's markers (§12.6). */
    trex.v2.hub.api.IngestsResponse ingests();

    /** The Accounts overview (§10.1, §10.5): per-account first/last and the coverage strip. */
    AccountsResponse accounts(String window, String granularity, java.time.LocalDate asOf);
}
