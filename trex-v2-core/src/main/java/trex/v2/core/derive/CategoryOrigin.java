package trex.v2.core.derive;

/** Where a current fact's category came from (V2-PROPOSAL.md §9.9.E): the transfer structure, a pin, a rule, or nothing. */
public enum CategoryOrigin {
    STRUCTURAL,
    PIN,
    RULE,
    NONE
}
