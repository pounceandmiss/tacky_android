package com.example.tackyapk.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.example.tackyapk.R

/**
 * The standard top-bar back affordance: an arrow that pops the back stack. Shared
 * across every screen's TopAppBar so they stay identical - hand-copying this row
 * is how the OMEMO screen once drifted to a "Back" text button.
 */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
    }
}
