package trex.v2.egress;

import java.io.PrintStream;

/**
 * A rate-and-ETA printer for a long apply loop (V2-SPEC.md §11), shared by the Firefly and
 * ezbookkeeping passes. It prints one line every {@value #EVERY} ticks and nothing at all for a
 * short run, so a small batch stays quiet. Extracted from {@code FireflyEgress} when the ezb view
 * needed the same thing; the API and output are unchanged.
 */
public final class Progress {

    private static final int EVERY = 50;

    private final int total;
    private final PrintStream out;
    private final long startedAt = System.nanoTime();
    private long windowAt = System.nanoTime();
    private int seen;

    public Progress(int total, PrintStream out) {
        this.total = total;
        this.out = out;
    }

    public void tick() {
        seen++;
        if (seen % EVERY != 0) {
            return;
        }
        long now = System.nanoTime();
        double windowSeconds = (now - windowAt) / 1e9;
        double rate = EVERY / Math.max(windowSeconds, 0.001);
        long remaining = Math.round((total - seen) / Math.max(rate, 0.001));
        windowAt = now;
        out.printf("  %d/%d  %.1f/s  about %d:%02d left%n", seen, total, rate,
            remaining / 60, remaining % 60);
        out.flush();
    }

    public void done() {
        if (seen >= EVERY) {
            out.printf("  %d/%d in %.0fs%n", seen, total, (System.nanoTime() - startedAt) / 1e9);
        }
    }
}
