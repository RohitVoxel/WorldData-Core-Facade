package com.example.worlddatafacade;

import net.wdc.core.api.WDC;
import net.wdc.core.api.changes.WDCBlockChange;
import net.wdc.core.api.changes.WDCChangeBatch;
import net.wdc.core.api.changes.WDCChangeFilter;
import net.wdc.core.api.changes.WDCSubscription;

/**
 * The push pipe. Replaces: block update events and polling a region every tick to see what changed.
 *
 * The feed delivers one batch per dimension at the end of the tick. Several edits to the same block in one
 * tick arrive as one change holding the first old state and the last new state, so you never see
 * intermediate states. Pulling data never notifies listeners.
 *
 * Use it to react to changes: invalidate your own cache, update a map, recompute a structure. Do not use it
 * for same-instant machine logic, because the batch arrives after the tick's work by design.
 *
 * Two ways to listen:
 * - subscribe in code, as register() does here.
 * - declare a class implementing WDCChangeEntrypoint under the key "worlddata_core:changes" in
 *   fabric.mod.json or neoforge.mods.toml. WDC creates it for you and calls it with every batch.
 *
 * Listener rules:
 * - Keep it fast. It runs on the game thread inside the end-of-tick flush.
 * - batch.reset() or batch.truncated() means detail was dropped under load. Pull fresh data with
 *   WDC.requests() instead of trusting the list.
 * - Close the subscription when your mod stops needing it.
 */
public final class ChangeFeedExample {

    private static WDCSubscription subscription;

    private ChangeFeedExample() {}

    public static void register() {
        if (subscription != null) return;
        subscription = WDC.changes().subscribe(WDCChangeFilter.all(), ChangeFeedExample::onChanges);
    }

    public static void unregister() throws Exception {
        if (subscription == null) return;
        subscription.close();
        subscription = null;
    }

    private static void onChanges(WDCChangeBatch batch) {
        if (batch.reset() || batch.truncated()) {
            return;
        }
        batch.chunks().forEach(chunk -> chunk.blockChanges().forEach(ChangeFeedExample::onBlockChange));
    }

    private static void onBlockChange(WDCBlockChange change) {
        boolean removed = WDCBlockChange.AIR.equals(change.newBlockId());
        if (removed) {
            return;
        }
    }
}
