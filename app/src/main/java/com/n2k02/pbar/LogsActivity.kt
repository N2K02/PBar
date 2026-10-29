package com.n2k02.pbar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Uses the existing PBar Settings header and shared n2k02theme. */
@Composable
internal fun PBarLogsPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    N2K02UnifiedLogsPage(modifier = modifier, appName = "PBar", onBack = onBack)
}
