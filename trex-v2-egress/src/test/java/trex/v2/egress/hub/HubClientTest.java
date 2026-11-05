package trex.v2.egress.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The service-read header (V2-SPEC.md §9.5): sent when a runner secret is set, absent otherwise. */
class HubClientTest {

    @Test
    void readsCarryTheRunnerSecretOnlyWhenSet() throws Exception {
        try (FakeHub hub = new FakeHub()) {
            new HubClient(hub.url(), "s3cret").units(0);
            assertEquals("s3cret", hub.lastServiceToken);

            new HubClient(hub.url()).units(0);
            assertNull(hub.lastServiceToken);

            new HubClient(hub.url(), " ").units(0);
            assertNull(hub.lastServiceToken);
        }
    }
}
