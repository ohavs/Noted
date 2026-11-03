package com.ohav.noted.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/** The phone published a new state: store it (the tile and an open app follow). A picture it sent: keep it. */
class StateService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        if (e.path == Store.PIC) store.picArrived(e.data)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val asset = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == Store.STATE }
            .map { DataMapItem.fromDataItem(it.dataItem).dataMap }
            .maxByOrNull { it.getLong("at") }?.getAsset("state") ?: return
        runBlocking {
            runCatching { store.accept(asset) }
            runCatching { store.flush() }
        }
    }
}
