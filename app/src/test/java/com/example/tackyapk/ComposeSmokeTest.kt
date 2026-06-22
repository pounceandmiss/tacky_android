package com.example.tackyapk

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Smoke test: confirms Robolectric can render Compose headlessly in the build
 *  container before we build out the real conversation UI tests. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ComposeSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rendersText() {
        rule.setContent { Text("hello-robolectric") }
        rule.onNodeWithText("hello-robolectric").assertIsDisplayed()
    }
}
