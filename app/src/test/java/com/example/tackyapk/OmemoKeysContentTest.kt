package com.example.tackyapk

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.tackyapk.feature.omemo.OmemoKeysContent
import com.example.tackyapk.feature.omemo.TrustEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the stateless key UI headlessly and drives the trust controls. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OmemoKeysContentTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun selectingTrustedInvokesCallback() {
        var lastDevice = -1L
        var lastState = ""
        rule.setContent {
            OmemoKeysContent(
                entries = listOf(
                    TrustEntry(device = 111, trust = "undecided", active = true, fingerprint = "aabb"),
                ),
                blindTrust = false,
                onSetTrust = { d, s -> lastDevice = d; lastState = s },
                onSetBlindTrust = {},
                onBack = {},
            )
        }
        rule.onNodeWithContentDescription("Trusted").performClick()
        assertEquals(111L, lastDevice)
        assertEquals("trusted", lastState)
    }

    @Test
    fun compromisedShowsLabelAndNoRadios() {
        rule.setContent {
            OmemoKeysContent(
                entries = listOf(
                    TrustEntry(device = 222, trust = "compromised", active = true, fingerprint = "ccdd"),
                ),
                blindTrust = false,
                onSetTrust = { _, _ -> },
                onSetBlindTrust = {},
                onBack = {},
            )
        }
        rule.onNodeWithText("Compromised - key changed").assertIsDisplayed()
        // No trust controls for a system-set compromised device.
        val trustedNodes = rule.onAllNodesWithContentDescription("Trusted").fetchSemanticsNodes().size
        assertTrue(trustedNodes == 0)
    }

    @Test
    fun encryptSwitchInvokesCallback() {
        var lastValue: Boolean? = null
        rule.setContent {
            OmemoKeysContent(
                entries = emptyList(),
                blindTrust = false,
                enabled = false,
                onSetTrust = { _, _ -> },
                onSetBlindTrust = {},
                onSetEnabled = { lastValue = it },
                onBack = {},
            )
        }
        rule.onNodeWithText("Encrypt messages in this chat (OMEMO)").assertIsDisplayed()
        rule.onNodeWithTag("encryptSwitch").performClick()
        assertEquals(true, lastValue)
    }

    @Test
    fun blindTrustSwitchInvokesCallback() {
        var lastValue: Boolean? = null
        rule.setContent {
            OmemoKeysContent(
                entries = emptyList(),
                blindTrust = false,
                onSetTrust = { _, _ -> },
                onSetBlindTrust = { lastValue = it },
                onBack = {},
            )
        }
        rule.onNodeWithText("Trust new devices automatically").assertIsDisplayed()
        rule.onNodeWithTag("blindTrustSwitch").performClick()
        assertEquals(true, lastValue)
    }
}
