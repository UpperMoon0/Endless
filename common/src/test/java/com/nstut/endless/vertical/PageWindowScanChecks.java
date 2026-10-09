package com.nstut.endless.vertical;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free checks, also callable when the Gradle distribution is unavailable. */
public final class PageWindowScanChecks {
    private record Page(int x, int y, int z) {}

    public static void main(String[] args) {
        for (int distance : new int[]{4, 8, 12}) stationarySaturation(distance);
        notificationAtCapacity(256);
        notificationAtCapacity(255);
        notificationAtCapacity(254);
        notificationAfterUnloadedScan();
        staleNotificationsAreDiscarded();
        chunkInspectionBudget();
        System.out.println("PageWindowScan: 9 regression cases passed");
    }

    static void stationarySaturation(int distance) {
        var scan = new PageWindowScan(9, -13, distance, -5, 1);
        var queue = new FairWorkQueue<String, Page, Page>();
        var delivered = new ArrayList<Page>();
        var expected = new HashSet<Page>();
        for (int x = 9 - distance; x <= 9 + distance; x++) {
            for (int z = -13 - distance; z <= -13 + distance; z++) {
                for (int y = -6; y <= -4; y++) expected.add(new Page(x, y, z));
            }
        }
        finish(scan, queue, delivered);
        check(new HashSet<>(delivered).equals(expected), "Stationary player lost pages at distance " + distance);
        check(delivered.size() == expected.size(), "Partial scan replayed accepted pages at distance " + distance);
        check(delivered.subList(0, 3).equals(List.of(new Page(9, -6, -13),
            new Page(9, -5, -13), new Page(9, -4, -13))), "Near-first order changed");
    }

    static void notificationAtCapacity(int prefilled) {
        var scan = new PageWindowScan(0, 0, 0, 4, 1);
        // Initial discovery is already complete when a late chunk-send callback arrives.
        scan.advance(16, (x, z) -> false, (x, y, z) -> { throw new AssertionError("Unloaded chunk offered"); });
        var queue = new FairWorkQueue<String, Page, Page>();
        for (int i = 0; i < prefilled; i++) {
            var page = new Page(100, i, 100);
            queue.offer("player", page, page);
        }
        scan.notifyChunk(1, 0);
        scan.advance(16, (x, z) -> true, (x, y, z) -> offer(queue, x, y, z));
        check(!scan.isComplete(), "Saturated notification was discarded");
        // Duplicate callbacks must preserve the partially admitted page cursor.
        scan.notifyChunk(1, 0);
        var delivered = new ArrayList<Page>();
        finish(scan, queue, delivered);
        var notificationPages = delivered.stream().filter(page -> page.x == 1).toList();
        check(notificationPages.equals(List.of(new Page(1, 3, 0), new Page(1, 4, 0), new Page(1, 5, 0))),
            "Notification lost or replayed a page at queue size " + prefilled);
    }

    static void notificationAfterUnloadedScan() {
        var scan = new PageWindowScan(0, 0, 0, 0, 1);
        scan.advance(16, (x, z) -> false, (x, y, z) -> false);
        check(scan.isComplete(), "Unloaded scan did not advance");
        scan.notifyChunk(0, 0);
        var delivered = new ArrayList<Page>();
        finish(scan, new FairWorkQueue<>(), delivered);
        check(delivered.size() == 3, "Later loaded-chunk notification was lost");
    }

    static void staleNotificationsAreDiscarded() {
        var scan = new PageWindowScan(0, 0, 0, 0, 1);
        scan.notifyChunk(-1, 0);
        scan.notifyChunk(1, 0);
        scan.discardNotificationsOutside(1, 0, 0);
        var delivered = new ArrayList<Page>();
        scan.advance(16, (x, z) -> x != 0, (x, y, z) -> { delivered.add(new Page(x, y, z)); return true; });
        check(delivered.size() == 3 && delivered.stream().allMatch(page -> page.x == 1), "Stale notification retained");
        check(scan.isComplete(), "Pruned notifications blocked completion");
    }

    static void chunkInspectionBudget() {
        var scan = new PageWindowScan(0, 0, 12, 0, 1);
        var inspected = new AtomicInteger();
        scan.advance(16, (x, z) -> { inspected.incrementAndGet(); return false; }, (x, y, z) -> true);
        check(inspected.get() == 16, "Discovery exceeded its chunk-inspection budget");
        check(!scan.isComplete(), "Discovery skipped remaining chunks");
    }

    private static boolean offer(FairWorkQueue<String, Page, Page> queue, int x, int y, int z) {
        var page = new Page(x, y, z);
        return queue.offerBounded("player", page, page, 256);
    }

    private static void finish(PageWindowScan scan, FairWorkQueue<String, Page, Page> queue, List<Page> delivered) {
        for (int tick = 0; tick < 1000 && (!scan.isComplete() || queue.size() != 0); tick++) {
            scan.advance(16, (x, z) -> true, (x, y, z) -> offer(queue, x, y, z));
            check(queue.ownerSize("player") <= 256, "Page backlog exceeded its bound");
            queue.drain(64, 8, delivered::add);
        }
        check(scan.isComplete() && queue.size() == 0, "Stationary discovery did not drain");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
