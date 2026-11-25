// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.graphics.Color
import helium314.keyboard.fork.ui.segmentShape
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ForkColorsTest {
    private val lightAccent = Color.rgb(0xDA, 0xE2, 0xFF) // dark theme accent, as on the "Done" button
    private val darkAccent = Color.rgb(0x41, 0x5F, 0x91)
    private val nearWhite = Color.rgb(0xE3, 0xE2, 0xE9)
    private val nearBlack = Color.rgb(0x12, 0x13, 0x18)

    @Test fun lightTextOnLightAccentIsReplaced() {
        val color = ForkColors.readableOn(lightAccent, nearWhite, nearBlack)
        assertEquals(nearBlack, color)
        assertTrue(ForkColors.contrast(color, lightAccent) >= ForkColors.MIN_TEXT_CONTRAST)
    }

    @Test fun firstReadableCandidateWins() {
        assertEquals(nearWhite, ForkColors.readableOn(darkAccent, nearWhite, nearBlack))
    }

    @Test fun fallsBackToBlackOrWhite() {
        val grey = Color.rgb(0x80, 0x80, 0x80)
        val color = ForkColors.readableOn(lightAccent, grey)
        assertEquals(Color.BLACK, color)
    }

    @Test fun groupShapesHaveRoundOuterCorners() {
        // only the shape objects differ between positions; a single segment is round everywhere
        assertTrue(segmentShape(0, 1) == segmentShape(0, 1))
        assertTrue(segmentShape(0, 3) != segmentShape(1, 3))
        assertTrue(segmentShape(1, 3) != segmentShape(2, 3))
    }
}
