package trex.v2.hub;

import trex.v2.hub.api.AckDiff;
import trex.v2.hub.api.AckJson;
import trex.v2.hub.api.AckRequest;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.LedgerPage;
import trex.v2.hub.api.ReconcileResponse;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.hub.api.ReviewRow;
import trex.v2.hub.api.StatusResponse;
import trex.v2.hub.api.TransferJson;
import trex.v2.hub.api.UnitJson;

import java.util.List;

/** The hub's read surface, implemented by {@link HubService} and served by the HTTP layer. */
interface HubApi {

    HeadResponse head();

    StatusResponse status();

    RefdataResponse refdata();

    LedgerPage ledger(BlotterQuery query);

    List<ReviewRow> review(String kind);

    List<TransferJson> transfers();

    List<UnitJson> units();

    ReconcileResponse reconcile();

    List<AckJson> acks();

    DecisionOutcome postAck(AckRequest request);

    java.util.Optional<AckDiff> ackDiff(String user, String period);

    DecisionOutcome reflowPreview(String categoriesYaml);

    java.util.Optional<String> categoriesYaml();

    DecisionOutcome saveCategories(String categoriesYaml);

    DecisionOutcome submitDecisions(DecisionRequest request);
}
