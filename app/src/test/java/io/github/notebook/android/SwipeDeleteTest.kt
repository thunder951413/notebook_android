package io.github.notebook.android

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeDeleteTest {
    private val actionWidth = 96f

    @Test fun shortSlowSwipeClosesAction() {
        assertEquals(0f, swipeDeleteTargetOffset(-20f, actionWidth, 0f))
    }

    @Test fun swipePastThresholdRevealsDeleteAction() {
        assertEquals(-actionWidth, swipeDeleteTargetOffset(-40f, actionWidth, 0f))
    }

    @Test fun fastSwipeLeftRevealsDeleteAction() {
        assertEquals(-actionWidth, swipeDeleteTargetOffset(-10f, actionWidth, -800f))
    }

    @Test fun fastSwipeRightClosesRevealedAction() {
        assertEquals(0f, swipeDeleteTargetOffset(-actionWidth, actionWidth, 800f))
    }
}
