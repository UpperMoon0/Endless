package com.nstut.endless.testing;

import org.junit.jupiter.api.Test;

import static com.nstut.endless.testing.LivePlacementSettlement.Result.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LivePlacementSettlementTest {
    @Test
    void transientAirAfterAcknowledgementCanSettleWithoutAnotherDispatch() {
        LivePlacementSettlement placement = new LivePlacementSettlement();
        placement.dispatched(0);
        assertEquals(WAIT, placement.poll(10, 1, false, true));
        assertEquals(WAIT, placement.poll(49, 1, false, true));
        assertEquals(ACCEPTED, placement.poll(50, 1, true, false));
        assertEquals(1, placement.attempts());
    }

    @Test
    void settlePeriodStartsAtAcknowledgementRatherThanDispatch() {
        LivePlacementSettlement placement = new LivePlacementSettlement();
        placement.dispatched(0);
        assertEquals(WAIT, placement.poll(500, 0, true, false));
        assertEquals(WAIT, placement.poll(600, 1, false, true));
        assertEquals(WAIT, placement.poll(639, 1, false, true));
        assertEquals(RETRY, placement.poll(640, 1, false, true));
    }

    @Test
    void retryRequiresFreshAcknowledgementEvenWhenLocallyStone() {
        LivePlacementSettlement placement = new LivePlacementSettlement();
        placement.dispatched(0);
        assertEquals(WAIT, placement.poll(10, 1, false, true));
        assertEquals(RETRY, placement.poll(50, 1, false, true));
        placement.dispatched(1);
        assertEquals(WAIT, placement.poll(51, 1, true, false));
        assertEquals(ACCEPTED, placement.poll(52, 2, true, false));
    }

    @Test
    void persistentAirFailsAfterThreeAcknowledgedAttempts() {
        LivePlacementSettlement placement = new LivePlacementSettlement();
        for (int attempt = 1; attempt <= 3; attempt++) {
            placement.dispatched(attempt - 1);
            int tick = attempt * 100;
            assertEquals(WAIT, placement.poll(tick, attempt, false, true));
            assertEquals(attempt < 3 ? RETRY : REJECTED,
                placement.poll(tick + 40, attempt, false, true));
        }
        assertEquals(3, placement.attempts());
    }

    @Test
    void unexpectedBlockIsNotOverwrittenByRetry() {
        LivePlacementSettlement placement = new LivePlacementSettlement();
        placement.dispatched(0);
        assertEquals(WAIT, placement.poll(10, 1, false, false));
        assertEquals(REJECTED, placement.poll(50, 1, false, false));
    }
}
