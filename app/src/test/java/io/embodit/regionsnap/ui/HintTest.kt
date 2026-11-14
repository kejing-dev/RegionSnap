package io.embodit.regionsnap.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA #1 q1-4 (D38): a hint goes as soon as what it says is no longer true, whatever its timer or `bar long`. */
class HintTest {
    @Test fun savedOnlyWhileSaved() {
        assertTrue(RsUiState(hint = Hint.SAVED, phase = Phase.SAVED).hintStillTrue())
        for (p in listOf(Phase.FRAMED, Phase.IDLE, Phase.DRAWING, Phase.PREVIEW)) assertFalse("$p", RsUiState(hint = Hint.SAVED, phase = p).hintStillTrue())
    }

    @Test fun anchorsLostOnlyWhileANoteIsLost() {
        assertTrue(RsUiState(hint = Hint.ANCHORS_LOST, hintN = 1, lostPins = setOf("a")).hintStillTrue())
        assertFalse(RsUiState(hint = Hint.ANCHORS_LOST, hintN = 1, lostPins = emptySet()).hintStillTrue())
    }

    @Test fun calibrationHintsOnlyOnCalibrate() {
        assertTrue(RsUiState(hint = Hint.CALIB_PRE, calib = CalibUi()).hintStillTrue())
        assertFalse(RsUiState(hint = Hint.CALIB_PRE, calib = null).hintStillTrue())
    }

    @Test fun cameraStartingOnlyWithItsCard() {
        assertTrue(RsUiState(hint = Hint.CAMERA_STARTING, card = Card.CAMERA_STARTING).hintStillTrue())
        assertFalse(RsUiState(hint = Hint.CAMERA_STARTING, card = Card.NONE).hintStillTrue())
    }

    @Test fun tuckedWindowWithAFrameIsStillTheFramingState() {
        // q1-1: the app put the window away; the design state stays S06, not S22
        assertTrue(RsUiState(panelOpen = false, panelEverOpened = true, panelTucked = true, hasFrame = true, phase = Phase.FRAMED).designState == "S06")
        assertTrue(RsUiState(panelOpen = false, panelEverOpened = true, panelTucked = false, hasFrame = true, phase = Phase.FRAMED).designState == "S22")
    }
}
