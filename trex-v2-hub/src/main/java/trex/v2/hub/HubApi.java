package trex.v2.hub;

import trex.v2.hub.api.AckDiff;
import trex.v2.hub.api.AckJson;
import trex.v2.hub.api.AckRequest;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.LedgerPage;
import trex.v2.hub.api.ReconcileResponse;
import trex.v2.hub.api.OpeningResponse;
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

    List<ReviewRow> review(String kind);

    List<TransferJson> transfers();

    UnitsResponse units();

    ReconcileResponse reconcile();

    OpeningResponse opening();

    List<AckJson> acks();

    DecisionOutcome postAck(AckRequest request);

    java.util.Optional<AckDiff> ackDiff(String user, String period);

    DecisionOutcome reflowPreview(String categoriesYaml);

    java.util.Optional<String> categoriesYaml();

    DecisionOutcome saveCategories(String categoriesYaml);

    Workbook.Report workbook();

    ProjectionStateResponse projection();

    DecisionOutcome putProjection(ProjectionRequest request);

    DecisionOutcome submitDecisions(DecisionRequest request);
}
