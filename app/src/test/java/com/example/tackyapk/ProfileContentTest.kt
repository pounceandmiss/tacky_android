package com.example.tackyapk

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.tackyapk.feature.omemo.TrustEntry
import com.example.tackyapk.feature.profile.ProfileContent
import com.example.tackyapk.util.formatFingerprint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real profile UI headlessly: display name, the this-device
 *  fingerprint header, and a trust selector for each *other* own device (this
 *  device is excluded from the controls), mirroring the desktop omemokeyspanel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ProfileContentTest {

    @get:Rule
    val rule = createComposeRule()

    private val fp = "ab".repeat(64)
    private val otherFp = "cd".repeat(64)

    @Test
    fun rendersHeaderAndTrustControlsForOtherDevices() {
        var trustedDevice = -1L
        var trustedState = ""
        rule.setContent {
            ProfileContent(
                acc = "me@x",
                displayName = "Tacky Me",
                onSaveName = {},
                ownFingerprint = fp,
                deviceId = 4242L,
                ownDevices = listOf(
                    // This device: present in the raw list but must be filtered out.
                    TrustEntry(device = 4242L, trust = "trusted", active = true, fingerprint = fp),
                    TrustEntry(device = 99L, trust = "undecided", active = false, fingerprint = otherFp),
                ),
                blindTrust = true,
                onSetBlindTrust = {},
                onSetOwnDeviceTrust = { d, s -> trustedDevice = d; trustedState = s },
                avatarSlot = { Text("avatar") },
                onBack = {},
            )
        }

        rule.onNodeWithText("Tacky Me").assertIsDisplayed()
        rule.onNodeWithText("me@x").assertIsDisplayed()
        // This device's key shows once, as the header (no right-side badge).
        rule.onNodeWithText(formatFingerprint(fp)).assertIsDisplayed()
        // The other device (further down the list) shows its key plus a working
        // trust selector; scroll the lazy list to it first.
        rule.onNodeWithTag("profileList").performScrollToNode(hasContentDescription("Not trusted"))
        rule.onNodeWithText(formatFingerprint(otherFp)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Not trusted").performClick()
        assertEquals(99L, trustedDevice)
        assertEquals("untrusted", trustedState)
    }
}
