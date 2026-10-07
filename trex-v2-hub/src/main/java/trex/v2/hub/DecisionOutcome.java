package trex.v2.hub;

/** A hub mutation's outcome: an HTTP status and the body to write. */
record DecisionOutcome(int status, Object body) {}
