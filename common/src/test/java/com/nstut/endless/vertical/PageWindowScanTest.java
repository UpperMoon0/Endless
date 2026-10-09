package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;

class PageWindowScanTest {
    @Test void stationaryDistanceFourDeliversEveryPage() { PageWindowScanChecks.stationarySaturation(4); }
    @Test void stationaryDistanceEightDeliversEveryPage() { PageWindowScanChecks.stationarySaturation(8); }
    @Test void stationaryDistanceTwelveDeliversEveryPage() { PageWindowScanChecks.stationarySaturation(12); }
    @Test void fullQueueRetainsChunkNotification() { PageWindowScanChecks.notificationAtCapacity(256); }
    @Test void oneFreeSlotRetainsRemainingNotificationPages() { PageWindowScanChecks.notificationAtCapacity(255); }
    @Test void twoFreeSlotsRetainLastNotificationPage() { PageWindowScanChecks.notificationAtCapacity(254); }
    @Test void unloadedChunkCanBeDiscoveredAfterScanCompletes() { PageWindowScanChecks.notificationAfterUnloadedScan(); }
    @Test void staleNotificationsDoNotConsumeCapacity() { PageWindowScanChecks.staleNotificationsAreDiscarded(); }
    @Test void discoveryRetainsChunkInspectionBudget() { PageWindowScanChecks.chunkInspectionBudget(); }
}
