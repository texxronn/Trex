package trex.v2.core.derive;

/**
 * One link of the supersession map (V2-PROPOSAL.md §7.2, §9.9 P5): a superseded or retired fact
 * ({@code fromId}) and the replacing fact ({@code toId}, null when retired), with the decision
 * that established it. Supersession never rewrites an id.
 */
public record Supersession(String fromId, String toId, long decisionN, String reason) {

    public boolean retired() {
        return toId == null;
    }
}
