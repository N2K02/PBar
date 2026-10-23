@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.n2k02.pbar

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Shows when an update is available, downloading or ready to install; hidden otherwise.
 * Put it near the top of the main screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val state by AppUpdater.state.collectAsState()
    var diagnosticsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state) { if (state !is AppUpdater.State.Failed) diagnosticsVisible = false }
    val activity = LocalContext.current.activity()
    // "You're up to date" (after a manual check) shows for a few seconds, then goes away.
    LaunchedEffect(state) {
        if (state is AppUpdater.State.UpToDate) {
            delay(5_000)
            if (AppUpdater.state.value is AppUpdater.State.UpToDate) AppUpdater.dismiss()
        }
    }
    val visible = state is AppUpdater.State.UpToDate || state is AppUpdater.State.Checking || state is AppUpdater.State.Available || state is AppUpdater.State.Downloading ||
        state is AppUpdater.State.NeedsPermission || state is AppUpdater.State.Ready ||
        state is AppUpdater.State.Failed
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state is AppUpdater.State.Failed) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = state) {
                    is AppUpdater.State.Checking -> {
                        Title("Checking for updates…")
                        LoadingIndicator(Modifier.size(36.dp))
                        Text("Contacting GitHub for the latest release.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is AppUpdater.State.UpToDate -> {
                        Title("You're up to date")
                        Text(
                            "Version ${AppUpdater.installedVersionName} is the latest.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is AppUpdater.State.Available -> {
                        Title("Update available: ${s.release.versionName}")
                        Notes(s.release)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { diagnosticsVisible = false; AppUpdater.update(s.release) }) { Text("Update now") }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") }
                            TextButton(onClick = { AppUpdater.skip(s.release) }) { Text("Skip this version") }
                        }
                    }
                    is AppUpdater.State.Downloading -> {
                        Title("Downloading ${s.release.versionName}…")
                        val p = s.progress
                        if (p == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { AppUpdater.dismiss() }) { Text("Cancel") }
                    }
                    is AppUpdater.State.NeedsPermission -> {
                        Title("One more step")
                        Text(
                            "Android needs your OK to let this app install its own updates. " +
                                "Turn on \"Allow from this source\", then come back.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { activity?.let { AppUpdater.openInstallPermission(it) } }) { Text("Open settings") }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") }
                        }
                    }
                    is AppUpdater.State.Ready -> {
                        Title("${s.release.versionName} is ready to install")
                        Text(
                            "Android's installer opens. If Play Protect asks, you can scan the app or choose to install anyway.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { AppUpdater.install(s.release) }) { Text("Install") }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") }
                        }
                    }
                    is AppUpdater.State.Failed -> {
                        Title("Update problem")
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { diagnosticsVisible = !diagnosticsVisible }) {
                            Text(if (diagnosticsVisible) "Hide details" else "Show details")
                        }
                        if (diagnosticsVisible) {
                            Text(
                                s.diagnostics.ifBlank { "No additional diagnostic details were captured." },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = {
                                val clipboard = activity?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Update diagnostics", s.diagnostics))
                            }) { Text("Copy diagnostics") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.release?.let { r -> Button(onClick = { diagnosticsVisible = false; AppUpdater.update(r) }) { Text("Try again") } }
                            TextButton(onClick = { activity?.let { AppUpdater.openReleasePage(it, s.release) } }) {
                                Text("Open release page")
                            }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Close") }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Notes(release: AppUpdater.Release) {
    if (release.notes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What's new", style = MaterialTheme.typography.labelLarge)
        release.notes.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
    }
}


/**
 * Three-dot menu in the top bar. Updates opens a dedicated update-management page.
 */
@Composable
fun SettingsMenuButton(onOpenUpdates: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val state by AppUpdater.state.collectAsState()
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Updates")
                        Text(
                            when (val u = state) {
                                is AppUpdater.State.Checking -> "Checking…"
                                is AppUpdater.State.Available -> "Version ${u.release.versionName} available"
                                is AppUpdater.State.Downloading -> "Downloading…"
                                is AppUpdater.State.Ready, is AppUpdater.State.NeedsPermission -> "Ready to install"
                                is AppUpdater.State.UpToDate -> "Up to date · v${AppUpdater.installedVersionName}"
                                else -> "v${AppUpdater.installedVersionName} · check for updates"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    open = false
                    onOpenUpdates()
                },
            )
        }
    }
}

 
/** Dedicated update page; the title is supplied by the app's top app bar. */
@Composable
fun UpdatesPage(modifier: Modifier = Modifier) {
    val state by AppUpdater.state.collectAsState()
    val experimentalEnabled by AppUpdater.experimentalEnabled.collectAsState()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Experimental updates", style = MaterialTheme.typography.titleSmall)
                Text("Opt in to preview builds. Stable releases stay the default.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = experimentalEnabled, onCheckedChange = AppUpdater::setExperimentalEnabled)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Installed version", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("PBar v${AppUpdater.installedVersionName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Check GitHub for the latest release, review what's new, and install updates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { AppUpdater.check(manual = true) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state !is AppUpdater.State.Checking && state !is AppUpdater.State.Downloading,
                ) {
                    if (state is AppUpdater.State.Checking) {
                        LoadingIndicator(Modifier.size(28.dp))
                    } else {
                        Text("Check for updates")
                    }
                }
            }
        }
        UpdateCard()
    }
}
