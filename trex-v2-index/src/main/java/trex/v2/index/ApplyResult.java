package trex.v2.index;

import java.util.Set;

/**
 * The outcome of {@link Indexer#apply}: whether the derived state moved, and when a row-scoped
 * patch did the moving, the ids it touched (V2-INCREMENTAL-REFRESH-PLAN.md §4). A full derive and a
 * no-op report no ids; the hub reads those ids back as rows to carry in the SSE delta instead of
 * making every client re-fetch the view.
 *
 * @param changed    the derived state was recomputed or patched (a no-op is {@code false})
 * @param patchedIds the row-scoped ids the patch changed, empty for a full derive or a no-op
 */
public record ApplyResult(boolean changed, Set<String> patchedIds) {

    public ApplyResult {
        patchedIds = patchedIds == null ? Set.of() : Set.copyOf(patchedIds);
    }

    /** Nothing moved: no new lines, no drift, nothing to publish. */
    public static ApplyResult noChange() {
        return new ApplyResult(false, Set.of());
    }

    /** A full derive ran; it may have moved rows the caller cannot enumerate, so it carries no ids. */
    public static ApplyResult derived() {
        return new ApplyResult(true, Set.of());
    }

    /** A row-scoped patch moved exactly {@code ids}; the caller reads those rows for the delta. */
    public static ApplyResult patched(Set<String> ids) {
        return new ApplyResult(true, ids);
    }
}
