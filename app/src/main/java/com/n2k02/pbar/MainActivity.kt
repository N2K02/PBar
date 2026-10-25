package com.n2k02.pbar

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home as HomeRounded
import androidx.compose.material.icons.rounded.Palette as PaletteRounded
import androidx.compose.material.icons.rounded.Tune as TuneRounded
import androidx.compose.material.icons.rounded.SystemUpdate as SystemUpdateRounded
import androidx.compose.material.icons.rounded.Settings as SettingsRounded
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color as ComposeColor
import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.n2k02.theme.N2K02Theme
import dev.n2k02.updater.hasPendingUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tab(val label: String) { HOME("Home"), STYLE("Appearance"), APPS("Apps"), SETTINGS("Settings") }
private enum class SettingsPage(val title: String) { MAIN("Settings"), PERMISSIONS("Permissions"), APP_UPDATES("About & updates") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveUpdater.init(this)
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        // Only initialize the app allowlist for new installs. Existing preferences,
        // including apps users have enabled or disabled themselves, are untouched.
        if (!prefs.contains("default_ignored_v1") && !prefs.contains("ignored_packages")) {
            @Suppress("DEPRECATION")
            val installed = packageManager.getInstalledApplications(0).map { it.packageName }
            val mediaPlayers = setOf(
                "com.google.android.youtube", "com.google.android.apps.youtube.music",
                "com.spotify.music", "com.amazon.mp3", "com.apple.android.music",
                "com.soundcloud.android", "com.pandora.android", "com.tidal",
                "com.deezer.android.app", "com.google.android.apps.podcasts",
                "com.pocketcasts", "com.bambuna.podcastaddict",
                "org.videolan.vlc", "com.mxtech.videoplayer.ad",
                "com.mxtech.videoplayer.pro", "com.plexapp.android",
                "tv.twitch.android.app", "com.netflix.mediaclient",
                "com.disney.disneyplus", "com.google.android.videos"
            )
            prefs.edit()
                .putStringSet("ignored_packages", (installed - mediaPlayers - packageName).toSet())
                .putBoolean("default_ignored_v1", true)
                .apply()
        }
        val legacy = prefs.edit().putBoolean("overlay_enabled", true).putString("style", "line")
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("app_") && key.endsWith("_style") && value == "ring") legacy.remove(key)
            if (key.endsWith("_position") && value == "camera") legacy.putString(key, "top")
        }
        legacy.apply()
        if (!prefs.getBoolean("camera_ring_defaults_v2", false)) {
            val editor = prefs.edit().putBoolean("camera_ring_defaults_v2", true)
            if (prefs.getInt("ring_size", 68) == 68) editor.putInt("ring_size", 36)
            if (prefs.getInt("ring_offset_y", 28) == 28) editor.putInt("ring_offset_y", 0)
            // Migrate only untouched legacy per-app defaults; preserve user-tuned values.
            prefs.all.forEach { (key, value) ->
                if (key.startsWith("app_") && key.endsWith("_ring_size") && value == 68) editor.putInt(key, 36)
                if (key.startsWith("app_") && key.endsWith("_ring_offset_y") && value == 28) editor.putInt(key, 0)
            }
            editor.apply()
        }
        setContent { PBarApp() }
        if (getSharedPreferences("pbar", MODE_PRIVATE).getBoolean("overlay_enabled", false) && Settings.canDrawOverlays(this)) {
            refreshOverlay()
        }
    }

    override fun onResume() {
        super.onResume()
        LiveUpdater.onResume(this)
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        prefs.edit().putBoolean("overlay_enabled", true).apply()
        if (Settings.canDrawOverlays(this)) {
            refreshOverlay()
        }
    }

    /** Ask the overlay (hosted by the notification listener) to re-read settings and redraw. */
    private fun refreshOverlay() {
        sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(packageName))
    }

    private fun quitPBar() {
        getSharedPreferences("pbar", MODE_PRIVATE).edit().putBoolean("overlay_enabled", false).apply()
        refreshOverlay()
        finishAndRemoveTask()
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun PBarApp() {
        val context = LocalContext.current
        val updateStatus by LiveUpdater.controller.status.collectAsState()
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val prefs = remember { getSharedPreferences("pbar", MODE_PRIVATE) }
        var tab by remember { mutableStateOf(Tab.HOME) }
        var settingsPage by remember { mutableStateOf(SettingsPage.MAIN) }
        // Refresh permission indicators when returning from Android Settings.
        var permissionRevision by remember { mutableIntStateOf(0) }
        DisposableEffect(this@MainActivity) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val overlayReady = remember(permissionRevision) { Settings.canDrawOverlays(this@MainActivity) }
        val listenerReady = remember(permissionRevision) {
            runCatching {
                val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
                enabled.split(':').any {
                    it.equals(ComponentName(this@MainActivity, ProgressNotificationListener::class.java).flattenToString(), ignoreCase = true)
                }
            }.getOrDefault(false)
        }
        val pbarIcon = remember { loadAppIcon(packageManager, packageName) }
        var showTrackingDetails by remember { mutableStateOf(false) }
        var overlayEnabled by remember { mutableStateOf(true) }
        var style by remember { mutableStateOf("line") }
        // Both orientations use horizontal lines, with independent positions when rotating.
        var portraitPosition by remember {
            mutableStateOf(
                PBarPositions.portrait(prefs.getString("position_portrait", prefs.getString("position", "top")))
            )
        }
        var landscapePosition by remember {
            mutableStateOf(
                PBarPositions.landscape(prefs.getString("position_landscape", prefs.getString("position", "top")))
            )
        }
        var thickness by remember { mutableIntStateOf(prefs.getInt("thickness", 4).coerceIn(2, 12)) }
        var appColor by remember { mutableStateOf(prefs.getBoolean("app_color", true)) }
        var customColorHex by remember { mutableStateOf(prefs.getString("indicator_custom_color", "") ?: "") }
        var customColorMessage by remember { mutableStateOf("") }
        var pickerHue by remember { mutableFloatStateOf(265f) }
        var pickerSaturation by remember { mutableFloatStateOf(0.7f) }
        var pickerBrightness by remember { mutableFloatStateOf(0.95f) }
        var previewTestKey by remember { mutableIntStateOf(0) }
        var previewProgress by remember { mutableFloatStateOf(0.42f) }
        LaunchedEffect(previewTestKey, tab) {
            if (tab != Tab.STYLE || previewTestKey == 0) return@LaunchedEffect
            val start = SystemClock.elapsedRealtime()
            do {
                previewProgress = ((SystemClock.elapsedRealtime() - start) / 10_000f).coerceIn(0f, 1f)
                if (previewProgress >= 1f) break
                withFrameNanos { }
            } while (true)
            previewProgress = 0.42f
        }
        var hideFullscreen by remember { mutableStateOf(prefs.getBoolean("hide_fullscreen", false)) }
        var showOnLockScreen by remember { mutableStateOf(prefs.getBoolean("show_on_lock_screen", false)) }
        var showForDownloads by remember { mutableStateOf(prefs.getBoolean("show_for_downloads", true)) }
        var showForMedia by remember { mutableStateOf(prefs.getBoolean("show_for_media", true)) }
        var showForTimers by remember { mutableStateOf(prefs.getBoolean("show_for_timers", true)) }
        var ringSize by remember { mutableIntStateOf(prefs.getInt("ring_size", 36).coerceIn(24, 80)) }
        var ringOffsetX by remember { mutableIntStateOf(prefs.getInt("ring_offset_x", 0).coerceIn(-150, 150)) }
        var ringOffsetY by remember { mutableIntStateOf(prefs.getInt("ring_offset_y", 0).coerceIn(0, 80)) }
        var ringStroke by remember { mutableIntStateOf(prefs.getInt("ring_stroke", 5).coerceIn(2, 10)) }
        var indicatorOpacity by remember { mutableIntStateOf(prefs.getInt("indicator_opacity", 100).coerceIn(20, 100)) }
        var roundedCorners by remember { mutableStateOf(prefs.getBoolean("rounded_corners", true)) }
        var emphasiseProgress by remember { mutableStateOf(prefs.getBoolean("emphasise_progress", true)) }
        var knownPackages by remember { mutableStateOf(prefs.getStringSet("known_packages", emptySet())?.toSet() ?: emptySet()) }
        var appsSearch by remember { mutableStateOf("") }
        var appsFilter by remember { mutableStateOf("All") }
        var multiSelectApps by remember { mutableStateOf(false) }
        var selectedPackages by remember { mutableStateOf(setOf<String>()) }
        var selectedApp by remember { mutableStateOf<String?>(null) }
        BackHandler(enabled = selectedApp != null || tab != Tab.HOME) {
            when {
                selectedApp != null -> selectedApp = null
                tab == Tab.SETTINGS && settingsPage != SettingsPage.MAIN -> settingsPage = SettingsPage.MAIN
                tab == Tab.STYLE || tab == Tab.APPS -> tab = Tab.SETTINGS
                else -> tab = Tab.HOME
            }
        }
        var installedAppsRevision by remember { mutableIntStateOf(0) }
        var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var appsLoading by remember { mutableStateOf(false) }
        var appsLoaded by remember { mutableStateOf(false) }
        var appsLoadError by remember { mutableStateOf<String?>(null) }
        var ignoredPackages by remember { mutableStateOf(prefs.getStringSet("ignored_packages", emptySet())?.toSet() ?: emptySet()) }
        var appColorOverrides by remember {
            mutableStateOf(
                prefs.getStringSet("app_color_overrides", emptySet()).orEmpty().mapNotNull { entry ->
                    val split = entry.split("=", limit = 2)
                    if (split.size == 2) split[0] to split[1] else null
                }.toMap()
            )
        }
        val appColorOptions = listOf(
            "Automatic" to "",
            "Mint" to "#55D6BE",
            "Blue" to "#60A5FA",
            "Purple" to "#B39DDB",
            "Green" to "#A6E3A1",
            "Orange" to "#FFB86C"
        )
        var latestVersion by remember { mutableStateOf<String?>(null) }
        var apkUrl by remember { mutableStateOf<String?>(null) }
        var releaseUrl by remember { mutableStateOf("https://github.com/N2K02/PBar/releases/latest") }
        var updateMessage by remember { mutableStateOf("Check GitHub for the latest PBar release.") }
        var checking by remember { mutableStateOf(false) }
        var downloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf(0) }
        var downloadedUri by remember { mutableStateOf<Uri?>(null) }

        var signalHas by remember { mutableStateOf(prefs.getBoolean("has_signal", false)) }
        var signalPackage by remember { mutableStateOf(prefs.getString("signal_package", "").orEmpty()) }
        var signalMedia by remember { mutableStateOf(prefs.getBoolean("signal_media", false)) }
        var signalPlaying by remember { mutableStateOf(prefs.getBoolean("signal_playing", false)) }
        var signalIndeterminate by remember { mutableStateOf(prefs.getBoolean("signal_indeterminate", false)) }
        var signalProgress by remember { mutableIntStateOf(prefs.getInt("signal_progress", 0)) }
        var signalDuration by remember { mutableStateOf(prefs.getLong("signal_duration", 0L)) }
        var signalPosition by remember { mutableStateOf(prefs.getLong("signal_position", 0L)) }
        var signalUpdated by remember { mutableStateOf(prefs.getLong("signal_updated", 0L)) }
        var diagnosticSessionCount by remember { mutableIntStateOf(prefs.getInt("diagnostic_session_count", 0)) }
        var diagnosticSessionSummary by remember { mutableStateOf(prefs.getString("diagnostic_session_summary", "Waiting for media-session check").orEmpty()) }
        var diagnosticListenerConnected by remember { mutableStateOf(prefs.getBoolean("diagnostic_listener_connected", false)) }
        var diagnosticSessionError by remember { mutableStateOf(prefs.getString("diagnostic_session_error", "").orEmpty()) }
        var diagnosticNotificationSummary by remember { mutableStateOf(prefs.getString("diagnostic_notification_summary", "").orEmpty()) }
        fun refreshSignalStatus() {
            signalHas = prefs.getBoolean("has_signal", false)
            signalPackage = prefs.getString("signal_package", "").orEmpty()
            signalMedia = prefs.getBoolean("signal_media", false)
            signalPlaying = prefs.getBoolean("signal_playing", false)
            signalIndeterminate = prefs.getBoolean("signal_indeterminate", false)
            signalProgress = prefs.getInt("signal_progress", 0)
            signalDuration = prefs.getLong("signal_duration", 0L)
            signalPosition = prefs.getLong("signal_position", 0L)
            signalUpdated = prefs.getLong("signal_updated", 0L)
            diagnosticSessionCount = prefs.getInt("diagnostic_session_count", 0)
            diagnosticSessionSummary = prefs.getString("diagnostic_session_summary", "Waiting for media-session check").orEmpty()
            diagnosticListenerConnected = prefs.getBoolean("diagnostic_listener_connected", false)
            diagnosticSessionError = prefs.getString("diagnostic_session_error", "").orEmpty()
            diagnosticNotificationSummary = prefs.getString("diagnostic_notification_summary", "").orEmpty()
        }
        // The button used to only re-read saved values, so a stale reading (e.g. 8%) never cleared.
        // Now it also asks Android to rebind the listener (a no-op if bound) and makes it re-query
        // media sessions straight away.
        fun recheckSignalStatus() {
            runCatching {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(this@MainActivity, ProgressNotificationListener::class.java)
                )
            }
            sendBroadcast(Intent(ProgressNotificationListener.ACTION_REFRESH).setPackage(this@MainActivity.packageName))
            refreshSignalStatus()
        }
        // Keep the Home status live while it is showing instead of a one-time snapshot.
        LaunchedEffect(tab) {
            while (tab == Tab.HOME) {
                refreshSignalStatus()
                kotlinx.coroutines.delay(1000)
            }
        }

        // Query package metadata off the UI thread; tab changes and refreshes cancel stale results.
        LaunchedEffect(tab, installedAppsRevision, knownPackages) {
            if (tab == Tab.APPS) {
                appsLoading = true
                appsLoadError = null
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        val installed = packageManager.getInstalledApplications(0)
                            .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || packageManager.getLaunchIntentForPackage(it.packageName) != null) }
                            .map { it.packageName }
                        (installed + knownPackages).distinct().map { pkg ->
                            val label = runCatching {
                                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                            }.getOrDefault(pkg)
                            pkg to label
                        }.sortedWith(compareBy<Pair<String, String>> { it.first !in knownPackages }.thenBy { it.second.lowercase() })
                    }
                    installedApps = loaded
                    appsLoaded = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    appsLoadError = "Could not load apps. Tap Refresh list to try again."
                } finally {
                    appsLoading = false
                }
            }
        }

        N2K02Theme {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(
                                    when {
                                        selectedApp != null -> Icons.Rounded.TuneRounded
                                        tab == Tab.SETTINGS && settingsPage == SettingsPage.APP_UPDATES -> Icons.Rounded.SystemUpdateRounded
                                        tab == Tab.SETTINGS && settingsPage == SettingsPage.PERMISSIONS -> Icons.Rounded.Security
                                        tab == Tab.STYLE -> Icons.Rounded.PaletteRounded
                                        tab == Tab.APPS -> Icons.Rounded.TuneRounded
                                        tab == Tab.SETTINGS -> Icons.Rounded.SettingsRounded
                                        else -> Icons.Rounded.HomeRounded
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    if (selectedApp != null) "Customize app" else if (tab == Tab.SETTINGS) settingsPage.title else tab.label,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        },
                        actions = {
                            Row(
                                modifier = Modifier.padding(end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (pbarIcon != null) {
                                    Image(bitmap = pbarIcon, contentDescription = null, modifier = Modifier.size(26.dp))
                                } else {
                                    dev.n2k02.updater.N2K02AppIcon(Modifier.size(26.dp))
                                }
                                Text("PBar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = tab == Tab.HOME, onClick = { selectedApp = null; tab = Tab.HOME }, icon = { Icon(Icons.Rounded.HomeRounded, null) }, label = { Text("Home") })
                        NavigationBarItem(
                            selected = tab != Tab.HOME,
                            onClick = { selectedApp = null; settingsPage = SettingsPage.MAIN; tab = Tab.SETTINGS },
                            icon = {
                                dev.n2k02.updater.N2K02UpdateBadgedIcon(updateStatus) {
                                    Icon(Icons.Rounded.SettingsRounded, contentDescription = "Settings")
                                }
                            },
                            label = { Text("Settings") },
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = { quitPBar() },
                            icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Quit") },
                            label = { Text("Quit") },
                            alwaysShowLabel = true,
                        )
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding)
                        .then(if (tab == Tab.APPS && selectedApp == null) Modifier else Modifier.verticalScroll(rememberScrollState()))
                        .padding(if (tab == Tab.SETTINGS) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(if (tab == Tab.SETTINGS) 4.dp else 12.dp)
                ) {
                    when (tab) {
                        Tab.HOME -> {
                            Text(
                                if (overlayReady && listenerReady) "PBar runs automatically" else "Finish setting up PBar",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Shows media and download progress over other apps.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!overlayReady || !listenerReady) {
                                FilledTonalButton(
                                    onClick = { settingsPage = SettingsPage.PERMISSIONS; tab = Tab.SETTINGS },
                                    modifier = Modifier.fillMaxWidth(),
                                    shapes = ButtonDefaults.shapes()
                                ) {
                                    Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Set up permissions")
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Live status", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        when {
                                            !signalHas -> "No active progress"
                                            signalMedia && signalIndeterminate -> "Media · tracking playback"
                                            signalMedia && signalDuration > 0L -> "Media · " + (if (signalPlaying) "Playing" else "Paused") + " · " + signalProgress + "%"
                                            signalMedia -> "Media · timing unavailable"
                                            signalIndeterminate -> "Tracking progress"
                                            else -> "Progress · " + signalProgress + "%"
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { recheckSignalStatus() }) { Text("Refresh") }
                                        TextButton(onClick = { showTrackingDetails = !showTrackingDetails }) {
                                            Text(if (showTrackingDetails) "Hide details" else "Details")
                                        }
                                    }
                                    if (showTrackingDetails) {
                                            if (signalPackage.isNotBlank()) {
                                            Text("Source: " + signalPackage, style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (signalMedia && signalDuration > 0L) {
                                            Text("Position: " + (signalPosition / 1000L) + "s / " + (signalDuration / 1000L) + "s", style = MaterialTheme.typography.bodySmall)
                                        }
                                        HorizontalDivider()
                                        Text("Media-session diagnostics", style = MaterialTheme.typography.titleMedium)
                                        Text("Listener: " + if (diagnosticListenerConnected) "connected" else "not currently connected", style = MaterialTheme.typography.bodySmall)
                                        Text("Active sessions reported by Android: " + diagnosticSessionCount, style = MaterialTheme.typography.bodySmall)
                                        Text(diagnosticSessionSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (diagnosticNotificationSummary.isNotBlank()) {
                                            Text("Notifications PBar can see:", style = MaterialTheme.typography.bodySmall)
                                            Text(diagnosticNotificationSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (diagnosticSessionError.isNotBlank()) {
                                            Text("Session error: " + diagnosticSessionError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                        }
                                        if (signalUpdated > 0L) {
                                            Text("Last signal: " + ((System.currentTimeMillis() - signalUpdated).coerceAtLeast(0L) / 1000L) + "s ago", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        }
                                }
                            }
                        }
                        Tab.STYLE -> {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Live preview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text("See the global bar style immediately. Apps may apply their own overrides.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val previewColour = runCatching {
                                        ComposeColor(android.graphics.Color.parseColor(customColorHex))
                                    }.getOrNull() ?: MaterialTheme.colorScheme.primary
                                    ProgressLinePreview(
                                        if (landscape) landscapePosition else portraitPosition, thickness, previewColour,
                                        indicatorOpacity, roundedCorners, emphasiseProgress, previewProgress)
                                    FilledTonalButton(
                                        onClick = {
                                            previewTestKey++
                                            if (overlayReady && listenerReady) {
                                                sendBroadcast(Intent(OverlayController.ACTION_TEST_PROGRESS)
                                                    .setPackage(this@MainActivity.packageName))
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shapes = ButtonDefaults.shapes()
                                    ) { Text("Test progress for 10 seconds") }
                                    Text("The button also tests the actual overlay when display and notification permissions are enabled. Active playback settings are not modified.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Layout & position", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text("Choose where the progress line sits and how thick it is.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = {
                                            portraitPosition = "top"; landscapePosition = "top"; thickness = 4
                                            prefs.edit()
                                                .putString("position_portrait", portraitPosition)
                                                .putString("position_landscape", landscapePosition)
                                                .putInt("thickness", thickness).apply()
                                            refreshOverlay()
                                        }) { Text("Reset layout") }
                                    }
                                    Text("Automatic orientation", style = MaterialTheme.typography.titleMedium)
                                    Text("Horizontal line in portrait and landscape. The position switches automatically when you rotate your phone.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Thickness: $thickness dp", style = MaterialTheme.typography.titleMedium)
                                    Slider(value = thickness.toFloat(), onValueChange = {
                                        thickness = it.toInt().coerceIn(2, 12)
                                        prefs.edit().putInt("thickness", thickness).apply()
                                        if (overlayEnabled) refreshOverlay()
                                    }, valueRange = 2f..12f, steps = 9)
                                    Text("Portrait position", style = MaterialTheme.typography.titleMedium)
                                    PBarPositions.portraitChoices.forEach { (value, label) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = portraitPosition == value, onClick = {
                                                portraitPosition = value
                                                prefs.edit().putString("position_portrait", value).apply()
                                                if (overlayEnabled) refreshOverlay()
                                            })
                                            Text(label)
                                        }
                                    }
                                    Text("Landscape position", style = MaterialTheme.typography.titleMedium)
                                    PBarPositions.landscapeChoices.forEach { (value, label) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = landscapePosition == value, onClick = {
                                                landscapePosition = value
                                                prefs.edit().putString("position_landscape", value).apply()
                                                if (overlayEnabled) refreshOverlay()
                                            })
                                            Text(label)
                                        }
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Colour", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text("Choose an automatic app colour or your own fallback.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = {
                                            appColor = true; customColorHex = ""; customColorMessage = ""
                                            prefs.edit().putBoolean("app_color", true).remove("indicator_custom_color").apply()
                                            refreshOverlay()
                                        }) { Text("Reset colour") }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Use app colour", style = MaterialTheme.typography.titleMedium)
                                            Text("Uses the source notification's colour when available. A custom colour is used when no source colour is available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = appColor, onCheckedChange = { appColor = it; prefs.edit().putBoolean("app_color", it).apply(); if (overlayEnabled) refreshOverlay() })
                                    }
                                    OutlinedTextField(
                                        value = customColorHex,
                                        onValueChange = { customColorHex = it; customColorMessage = "" },
                                        label = { Text("Fallback colour (hex)") },
                                        placeholder = { Text("#8B5CF6") },
                                        supportingText = { Text(customColorMessage.ifBlank { "Used when the source app has no colour. Leave unset to use the system colour." }) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(onClick = {
                                        val normalized = if (customColorHex.startsWith("#")) customColorHex else "#$customColorHex"
                                        val valid = runCatching { android.graphics.Color.parseColor(normalized) }.isSuccess
                                        if (valid) {
                                            customColorHex = normalized
                                            prefs.edit().putString("indicator_custom_color", normalized).apply()
                                            customColorMessage = "Saved"
                                            if (overlayEnabled) refreshOverlay()
                                        } else customColorMessage = "Enter a valid hex colour, such as #8B5CF6"
                                    }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text("Apply custom colour") }
                                    OutlinedButton(onClick = {
                                        appColor = true
                                        customColorHex = ""
                                        customColorMessage = "Using app colours (system colour fallback)"
                                        prefs.edit().putBoolean("app_color", true).remove("indicator_custom_color").apply()
                                        if (overlayEnabled) refreshOverlay()
                                    }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                                        Text("Reset to app colours")
                                    }
                                    Text("Quick colours", style = MaterialTheme.typography.titleMedium)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("#8B5CF6", "#60A5FA", "#55D6BE", "#A6E3A1", "#FFB86C", "#F472B6")
                                            .forEach { hex ->
                                                FilterChip(
                                                    selected = customColorHex.equals(hex, ignoreCase = true) && !appColor,
                                                    onClick = {
                                                        appColor = false
                                                        customColorHex = hex
                                                        customColorMessage = "Colour applied"
                                                        prefs.edit().putBoolean("app_color", false).putString("indicator_custom_color", hex).apply()
                                                        refreshOverlay()
                                                    },
                                                    label = { Text(hex) },
                                                    leadingIcon = {
                                                        Box(Modifier.size(16.dp).background(
                                                            ComposeColor(android.graphics.Color.parseColor(hex)), CircleShape))
                                                    }
                                                )
                                            }
                                    }
                                    Text("Custom colour picker", style = MaterialTheme.typography.titleMedium)
                                    val pickedHex = String.format(java.util.Locale.US, "#%06X",
                                        android.graphics.Color.HSVToColor(floatArrayOf(
                                            pickerHue, pickerSaturation, pickerBrightness)) and 0xFFFFFF)
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Box(Modifier.size(36.dp).background(
                                            ComposeColor(android.graphics.Color.parseColor(pickedHex)), CircleShape))
                                        Text(pickedHex, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text("Hue: ${pickerHue.toInt()}°", style = MaterialTheme.typography.bodySmall)
                                    Slider(value = pickerHue, onValueChange = { pickerHue = it }, valueRange = 0f..360f)
                                    Text("Saturation: ${(pickerSaturation * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    Slider(value = pickerSaturation, onValueChange = { pickerSaturation = it }, valueRange = 0f..1f)
                                    Text("Brightness: ${(pickerBrightness * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    Slider(value = pickerBrightness, onValueChange = { pickerBrightness = it }, valueRange = 0f..1f)
                                    FilledTonalButton(onClick = {
                                        appColor = false
                                        customColorHex = pickedHex
                                        customColorMessage = "Colour applied"
                                        prefs.edit().putBoolean("app_color", false).putString("indicator_custom_color", pickedHex).apply()
                                        refreshOverlay()
                                    }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                                        Text("Use picked colour")
                                    }
                                    Text("Individual app colour overrides can be cleared under Apps → Colour → Automatic.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Bar finish", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text("Adjust opacity and the ends of the line.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = {
                                            indicatorOpacity = 100; roundedCorners = true; emphasiseProgress = true
                                            prefs.edit().putInt("indicator_opacity", 100).putBoolean("rounded_corners", true)
                                                .putBoolean("emphasise_progress", true).apply()
                                            refreshOverlay()
                                        }) { Text("Reset bar finish") }
                                    }
                                    Text("Opacity: $indicatorOpacity%", style = MaterialTheme.typography.bodyMedium)
                                    Slider(value = indicatorOpacity.toFloat(), onValueChange = { indicatorOpacity = it.toInt().coerceIn(20, 100); prefs.edit().putInt("indicator_opacity", indicatorOpacity).apply(); if (overlayEnabled) refreshOverlay() }, valueRange = 20f..100f, steps = 39)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Rounded ends", style = MaterialTheme.typography.titleMedium)
                                            Text("Use soft rounded edges on the progress line.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = roundedCorners, onCheckedChange = { roundedCorners = it; prefs.edit().putBoolean("rounded_corners", it).apply(); if (overlayEnabled) refreshOverlay() })
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("High contrast progress", style = MaterialTheme.typography.titleMedium)
                                            Text("Make the coloured portion clearer with a dimmer unfilled track and a bright progress tip.",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = emphasiseProgress, onCheckedChange = { value ->
                                            emphasiseProgress = value
                                            prefs.edit().putBoolean("emphasise_progress", value).apply()
                                            refreshOverlay()
                                        })
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Tracking sources", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text("Select which activities PBar should follow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = {
                                            showForDownloads = true; showForMedia = true; showForTimers = true
                                            prefs.edit().putBoolean("show_for_downloads", true)
                                                .putBoolean("show_for_media", true).putBoolean("show_for_timers", true).apply()
                                            sendBroadcast(Intent(ProgressNotificationListener.ACTION_REFRESH)
                                                .setPackage(this@MainActivity.packageName))
                                        }) { Text("Reset tracking") }
                                    }
                                    listOf(
                                        Triple("Downloads and transfers", "Read notification progress when apps expose it.", showForDownloads),
                                        Triple("Music and media", "Track supported playback sessions.", showForMedia),
                                        Triple("Google Clock timers", "Track timer progress when Clock exposes it.", showForTimers)
                                    ).forEach { (label, summary, enabled) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(label, style = MaterialTheme.typography.titleSmall)
                                                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Switch(
                                                checked = enabled,
                                                onCheckedChange = { value ->
                                                    when (label) {
                                                        "Downloads and transfers" -> {
                                                            showForDownloads = value
                                                            prefs.edit().putBoolean("show_for_downloads", value).apply()
                                                            if (!value && prefs.getBoolean("signal_download", false)) prefs.edit().putBoolean("has_signal", false).apply()
                                                        }
                                                        "Music and media" -> {
                                                            showForMedia = value
                                                            prefs.edit().putBoolean("show_for_media", value).apply()
                                                            if (!value && prefs.getBoolean("signal_media", false)) prefs.edit().putBoolean("has_signal", false).apply()
                                                        }
                                                        else -> {
                                                            showForTimers = value
                                                            prefs.edit().putBoolean("show_for_timers", value).apply()
                                                            if (!value && prefs.getBoolean("signal_timer", false)) prefs.edit().putBoolean("has_signal", false).putBoolean("signal_timer", false).apply()
                                                        }
                                                    }
                                                    sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Screen behaviour", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text("Control fullscreen and lock-screen visibility.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = {
                                            hideFullscreen = false; showOnLockScreen = false
                                            prefs.edit().putBoolean("hide_fullscreen", false).putBoolean("show_on_lock_screen", false).apply()
                                            refreshOverlay()
                                        }) { Text("Reset screen behaviour") }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Hide in fullscreen", style = MaterialTheme.typography.titleSmall)
                                            Text("Hide the indicator in immersive apps and fullscreen video. Requires the optional PBar accessibility helper.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = hideFullscreen, onCheckedChange = { value ->
                                            hideFullscreen = value
                                            prefs.edit().putBoolean("hide_fullscreen", value).apply()
                                            if (value) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                            if (overlayEnabled) refreshOverlay()
                                        })
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Show on lock screen", style = MaterialTheme.typography.titleSmall)
                                            Text("Allow the indicator on the lock screen. Android may restrict overlays on some secure lock screens.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = showOnLockScreen, onCheckedChange = { value ->
                                            showOnLockScreen = value
                                            prefs.edit().putBoolean("show_on_lock_screen", value).apply()
                                            if (overlayEnabled) refreshOverlay()
                                        })
                                    }
                                    Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                                        Text("Open accessibility settings")
                                    }
                                }
                            }
                        }
                        Tab.APPS -> {
                            if (selectedApp == null) {
                                val visibleApps = installedApps.filter { (appPackage, label) ->
                                    (appsSearch.isBlank() || label.contains(appsSearch, ignoreCase = true) || appPackage.contains(appsSearch, ignoreCase = true)) &&
                                        (appsFilter == "All" || (appsFilter == "Enabled" && appPackage !in ignoredPackages) || (appsFilter == "Disabled" && appPackage in ignoredPackages) || (appsFilter == "Detected" && appPackage in knownPackages))
                                }
                                // A bounded LazyColumn avoids composing every installed app and icon up front.
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("Choose apps to track. Tap one to customize.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Button(onClick = {
                                                knownPackages = prefs.getStringSet("known_packages", emptySet())?.toSet() ?: emptySet()
                                                ignoredPackages = prefs.getStringSet("ignored_packages", emptySet())?.toSet() ?: emptySet()
                                                installedAppsRevision++
                                                appColorOverrides = prefs.getStringSet("app_color_overrides", emptySet()).orEmpty().mapNotNull { entry ->
                                                    val split = entry.split("=", limit = 2)
                                                    if (split.size == 2) split[0] to split[1] else null
                                                }.toMap()
                                            }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text("Refresh list") }

                                            if (appsLoaded) {
                                                Text("${installedApps.count { it.first !in ignoredPackages }} of ${installedApps.size} apps enabled", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            OutlinedTextField(value = appsSearch, onValueChange = { appsSearch = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                            // Keep all four filters visible in a single, balanced row.
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                listOf("All", "Enabled", "Disabled", "Detected").forEach { option ->
                                                    FilterChip(
                                                        selected = appsFilter == option,
                                                        onClick = { appsFilter = option },
                                                        modifier = Modifier.weight(1f),
                                                        label = {
                                                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                                Text(
                                                                    option,
                                                                    style = MaterialTheme.typography.labelMedium,
                                                                    maxLines = 1,
                                                                    softWrap = false
                                                                )
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(onClick = { multiSelectApps = !multiSelectApps; selectedPackages = emptySet() }) { Text(if (multiSelectApps) "Done" else "Select multiple") }
                                                if (multiSelectApps) Text("${selectedPackages.size} selected", style = MaterialTheme.typography.labelMedium)
                                            }
                                            if (multiSelectApps && selectedPackages.isNotEmpty()) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(onClick = {
                                                        ignoredPackages = ignoredPackages - selectedPackages
                                                        prefs.edit().putStringSet("ignored_packages", ignoredPackages).apply()
                                                        refreshOverlay()
                                                        selectedPackages = emptySet()
                                                    }, modifier = Modifier.weight(1f)) { Text("Enable selected") }
                                                    OutlinedButton(onClick = {
                                                        ignoredPackages = ignoredPackages + selectedPackages
                                                        prefs.edit().putStringSet("ignored_packages", ignoredPackages).apply()
                                                        refreshOverlay()
                                                        selectedPackages = emptySet()
                                                    }, modifier = Modifier.weight(1f)) { Text("Disable selected") }
                                                }
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = {
                                                    ignoredPackages = emptySet()
                                                    prefs.edit().putStringSet("ignored_packages", emptySet()).apply()
                                                    refreshOverlay()
                                                }, enabled = appsLoaded, modifier = Modifier.weight(1f)) { Text("Enable all") }
                                                OutlinedButton(onClick = {
                                                    ignoredPackages = installedApps.map { it.first }.toSet()
                                                    prefs.edit().putStringSet("ignored_packages", ignoredPackages).putBoolean("has_signal", false).apply()
                                                    refreshOverlay()
                                                }, enabled = appsLoaded, modifier = Modifier.weight(1f)) { Text("Disable all") }
                                            }
                                        }
                                    }
                                    if (appsLoading) {
                                        item {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                                    Text(if (appsLoaded) "Refreshing apps…" else "Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                            }
                                        }
                                    }
                                    if (appsLoadError != null) {
                                        item { Text(appsLoadError!!, color = MaterialTheme.colorScheme.error) }
                                    }
                                    if (appsLoaded && !appsLoading && visibleApps.isEmpty()) {
                                        item { Text("No apps match your filter.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    items(visibleApps, key = { it.first }) { (appPackage, label) ->
                                        ElevatedCard(onClick = {
                                            if (multiSelectApps) selectedPackages = if (appPackage in selectedPackages) selectedPackages - appPackage else selectedPackages + appPackage
                                            else selectedApp = appPackage
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                if (multiSelectApps) Checkbox(checked = appPackage in selectedPackages, onCheckedChange = { checked ->
                                                    selectedPackages = if (checked) selectedPackages + appPackage else selectedPackages - appPackage
                                                })
                                                val iconBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, key1 = appPackage) {
                                                    value = withContext(Dispatchers.IO) { loadAppIcon(packageManager, appPackage) }
                                                }
                                                if (iconBitmap != null) Image(bitmap = iconBitmap!!, contentDescription = null, modifier = Modifier.size(44.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                                    Text(
                                                        if (appPackage in knownPackages) "Detected · " + if (appPackage in ignoredPackages) "Disabled" else "Enabled"
                                                        else if (appPackage in ignoredPackages) "Disabled" else "Enabled",
                                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (!multiSelectApps) Switch(checked = appPackage !in ignoredPackages, onCheckedChange = { enabled ->
                                                    ignoredPackages = if (enabled) ignoredPackages - appPackage else ignoredPackages + appPackage
                                                    prefs.edit().putStringSet("ignored_packages", ignoredPackages).apply()
                                                    if (!enabled && prefs.getString("signal_package", null) == appPackage) prefs.edit().putBoolean("has_signal", false).apply()
                                                    refreshOverlay()
                                                })
                                            }
                                        }
                                    }
                                }
                            } else {
                                TextButton(onClick = { selectedApp = null }) { Text("‹ Back to apps") }
                                Text("Changes save automatically. Default values follow your global appearance settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        installedApps.filter { it.first == selectedApp }.forEach { (packageName, label) ->
                                            ElevatedCard(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(14.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                if (multiSelectApps) {
                                                    Checkbox(checked = packageName in selectedPackages, onCheckedChange = { checked ->
                                                        selectedPackages = if (checked) selectedPackages + packageName else selectedPackages - packageName
                                                    })
                                                }
                                                val appIcon = remember(packageName) { loadAppIcon(packageManager, packageName) }
                                                if (appIcon != null) {
                                                    Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(44.dp))
                                                    Spacer(Modifier.width(12.dp))
                                                }
                                                Column(Modifier.weight(1f)) {
                                                    val advancedExpanded = true
                                                    var styleMenuExpanded by remember(packageName) { mutableStateOf(false) }
                                                    var portraitMenuExpanded by remember(packageName) { mutableStateOf(false) }
                                                    var landscapeMenuExpanded by remember(packageName) { mutableStateOf(false) }
                                                    var appStyle by remember(packageName) { mutableStateOf(prefs.getString("app_${packageName}_style", "default") ?: "default") }
                                                    var appPortraitPosition by remember(packageName) {
                                                        mutableStateOf(
                                                            PBarPositions.portrait(
                                                                prefs.getString("app_${packageName}_position_portrait",
                                                                    prefs.getString("app_${packageName}_position", "default")),
                                                                fallback = "default",
                                                            )
                                                        )
                                                    }
                                                    var appLandscapePosition by remember(packageName) {
                                                        mutableStateOf(
                                                            PBarPositions.landscape(
                                                                prefs.getString("app_${packageName}_position_landscape",
                                                                    prefs.getString("app_${packageName}_position", "default")),
                                                                fallback = "default",
                                                            )
                                                        )
                                                    }
                                                    var appCustomHex by remember(packageName) { mutableStateOf(appColorOverrides[packageName] ?: "") }
                                                    var appThickness by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_thickness", prefs.getInt("thickness", 4)).coerceIn(2, 12)) }
                                                    var appRingSize by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_size", prefs.getInt("ring_size", 36)).coerceIn(24, 80)) }
                                                    var appRingStroke by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_stroke", prefs.getInt("ring_stroke", 5)).coerceIn(2, 10)) }
                                                    var appRingOffsetX by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_offset_x", prefs.getInt("ring_offset_x", 0)).coerceIn(-150, 150)) }
                                                    var appRingOffsetY by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_offset_y", prefs.getInt("ring_offset_y", 0)).coerceIn(0, 80)) }
                                                    Text(label, style = MaterialTheme.typography.titleLarge)
                                                    Text(if (packageName in knownPackages) "Detected progress source" else "Installed app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    
                                                    if (advancedExpanded) Row(verticalAlignment = Alignment.CenterVertically) {
                                                        var colorMenuExpanded by remember(packageName) { mutableStateOf(false) }
                                                        val currentColorName = appColorOptions.firstOrNull { it.second == appColorOverrides[packageName] }?.first ?: if (appColorOverrides[packageName].isNullOrBlank()) "Automatic" else "Custom"
                                                        TextButton(onClick = { colorMenuExpanded = true }, shapes = ButtonDefaults.shapes()) { Text("Colour: $currentColorName") }
                                                        DropdownMenu(expanded = colorMenuExpanded, onDismissRequest = { colorMenuExpanded = false }) {
                                                            appColorOptions.forEach { (optionName, hex) ->
                                                                DropdownMenuItem(text = { Text(optionName) }, leadingIcon = { Icon(Icons.Rounded.PaletteRounded, null) }, onClick = {
                                                                    val updated = appColorOverrides.toMutableMap()
                                                                    if (hex.isBlank()) updated.remove(packageName) else updated[packageName] = hex
                                                                    appCustomHex = hex
                                                                    appColorOverrides = updated
                                                                    prefs.edit().putStringSet("app_color_overrides", updated.map { (pkg, color) -> "$pkg=$color" }.toSet()).apply()
                                                                    colorMenuExpanded = false
                                                                    sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                                })
                                                            }
                                                        }
                                                        TextButton(onClick = { selectedApp = if (advancedExpanded) null else packageName }, shapes = ButtonDefaults.shapes()) { Text(if (advancedExpanded) "Less" else "Customize") }
                                                    }
                                                    if (advancedExpanded && !multiSelectApps) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            OutlinedTextField(
                                                                value = appCustomHex,
                                                                onValueChange = { appCustomHex = it },
                                                                label = { Text("Custom hex") },
                                                                placeholder = { Text("#8B5CF6") },
                                                                singleLine = true,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            TextButton(onClick = {
                                                                val normalized = if (appCustomHex.startsWith("#")) appCustomHex else "#$appCustomHex"
                                                                if (runCatching { android.graphics.Color.parseColor(normalized) }.isSuccess) {
                                                                    val updated = appColorOverrides.toMutableMap()
                                                                    updated[packageName] = normalized
                                                                    appColorOverrides = updated
                                                                    appCustomHex = normalized
                                                                    prefs.edit().putStringSet("app_color_overrides", updated.map { (pkg, color) -> "$pkg=$color" }.toSet()).apply()
                                                                    sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                                }
                                                            }, shapes = ButtonDefaults.shapes()) { Text("Apply") }
                                                        }
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text("Style", modifier = Modifier.weight(1f))
                                                            TextButton(onClick = { styleMenuExpanded = true }, shapes = ButtonDefaults.shapes()) { Text(when (appStyle) { "line" -> "Line"; else -> "Default" }) }
                                                            DropdownMenu(expanded = styleMenuExpanded, onDismissRequest = { styleMenuExpanded = false }) {
                                                                listOf("default" to "Default", "line" to "Line").forEach { (value, label) ->
                                                                    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(Icons.Rounded.TuneRounded, null) }, onClick = {
                                                                        appStyle = value
                                                                        val edit = prefs.edit()
                                                                        if (value == "default") edit.remove("app_${packageName}_style") else edit.putString("app_${packageName}_style", value)
                                                                        edit.apply()
                                                                        styleMenuExpanded = false
                                                                        sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                                    })
                                                                }
                                                            }
                                                        }
                                                        Text("Orientation: Automatic (horizontal in portrait and landscape)",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text("Portrait position", modifier = Modifier.weight(1f))
                                                            TextButton(onClick = { portraitMenuExpanded = true }, shapes = ButtonDefaults.shapes()) {
                                                                Text(if (appPortraitPosition == "default") "Global default"
                                                                    else appPortraitPosition.replaceFirstChar { it.uppercase() })
                                                            }
                                                            DropdownMenu(expanded = portraitMenuExpanded, onDismissRequest = { portraitMenuExpanded = false }) {
                                                                (listOf("default" to "Global default") + PBarPositions.portraitChoices).forEach { (value, label) ->
                                                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                                                        appPortraitPosition = value
                                                                        val edit = prefs.edit()
                                                                        if (value == "default") edit.remove("app_${packageName}_position_portrait")
                                                                        else edit.putString("app_${packageName}_position_portrait", value)
                                                                        // Older single-position overrides must not override a reset.
                                                                        edit.remove("app_${packageName}_position").apply()
                                                                        portraitMenuExpanded = false
                                                                        refreshOverlay()
                                                                    })
                                                                }
                                                            }
                                                        }
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text("Landscape position", modifier = Modifier.weight(1f))
                                                            TextButton(onClick = { landscapeMenuExpanded = true }, shapes = ButtonDefaults.shapes()) {
                                                                Text(if (appLandscapePosition == "default") "Global default"
                                                                    else PBarPositions.landscapeChoices.firstOrNull { it.first == appLandscapePosition }?.second
                                                                        ?: "Global default")
                                                            }
                                                            DropdownMenu(expanded = landscapeMenuExpanded, onDismissRequest = { landscapeMenuExpanded = false }) {
                                                                (listOf("default" to "Global default") + PBarPositions.landscapeChoices).forEach { (value, label) ->
                                                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                                                        appLandscapePosition = value
                                                                        val edit = prefs.edit()
                                                                        if (value == "default") edit.remove("app_${packageName}_position_landscape")
                                                                        else edit.putString("app_${packageName}_position_landscape", value)
                                                                        edit.remove("app_${packageName}_position").apply()
                                                                        landscapeMenuExpanded = false
                                                                        refreshOverlay()
                                                                    })
                                                                }
                                                            }
                                                        }
                                                        Text("Thickness: $appThickness dp", style = MaterialTheme.typography.bodySmall)
                                                        Slider(value = appThickness.toFloat(), onValueChange = { appThickness = it.toInt().coerceIn(2, 12); prefs.edit().putInt("app_${packageName}_thickness", appThickness).apply(); sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName)) }, valueRange = 2f..12f, steps = 9)

                                                        TextButton(onClick = {
                                                            prefs.edit().remove("app_${packageName}_style").remove("app_${packageName}_orientation").remove("app_${packageName}_position").remove("app_${packageName}_position_portrait").remove("app_${packageName}_position_landscape").remove("app_${packageName}_thickness").remove("app_${packageName}_ring_size").remove("app_${packageName}_ring_offset_x").remove("app_${packageName}_ring_offset_y").remove("app_${packageName}_ring_stroke").apply()
                                                            appStyle = "default"; appPortraitPosition = "default"; appLandscapePosition = "default"; appThickness = prefs.getInt("thickness", 4); appRingSize = prefs.getInt("ring_size", 36); appRingOffsetX = prefs.getInt("ring_offset_x", 0); appRingOffsetY = prefs.getInt("ring_offset_y", 0); appRingStroke = prefs.getInt("ring_stroke", 5)
                                                            sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                        }, shapes = ButtonDefaults.shapes()) { Text("Reset app style") }
                                                    }
                                                }
                                                Switch(
                                                    checked = packageName !in ignoredPackages,
                                                    onCheckedChange = { track ->
                                                        val updated = if (track) ignoredPackages - packageName else ignoredPackages + packageName
                                                        ignoredPackages = updated
                                                        prefs.edit().putStringSet("ignored_packages", updated).apply()
                                                        refreshOverlay()
                                                        if (!track && prefs.getString("signal_package", null) == packageName) {
                                                            prefs.edit().putBoolean("has_signal", false).apply()
                                                            sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                        }
                                                    }
                                                )
                                            }
                                            }
                                            }
                                        }

                                }
                            }
                        }
                        Tab.SETTINGS -> {
                            when (settingsPage) {
                                SettingsPage.MAIN -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            "Make PBar your own",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 12.dp),
                                        )
                                        ListItem(
                                            headlineContent = { Text("Appearance") },
                                            supportingContent = { Text("Progress bar style and layout") },
                                            leadingContent = { Icon(Icons.Rounded.PaletteRounded, contentDescription = null) },
                                            trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                                            modifier = Modifier.clickable { selectedApp = null; tab = Tab.STYLE },
                                        )
                                        ListItem(
                                            headlineContent = { Text("Apps") },
                                            supportingContent = { Text("Choose and customize tracked apps") },
                                            leadingContent = { Icon(Icons.Rounded.TuneRounded, contentDescription = null) },
                                            trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                                            modifier = Modifier.clickable {
                                                selectedApp = null
                                                knownPackages = prefs.getStringSet("known_packages", emptySet())?.toSet() ?: emptySet()
                                                ignoredPackages = prefs.getStringSet("ignored_packages", emptySet())?.toSet() ?: emptySet()
                                                tab = Tab.APPS
                                            },
                                        )
                                        ListItem(
                                            headlineContent = { Text("Permissions") },
                                            supportingContent = {
                                                Text(if (overlayReady && listenerReady) "All permissions granted" else "Permissions needed")
                                            },
                                            leadingContent = { Icon(Icons.Rounded.Security, contentDescription = null) },
                                            trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                                            modifier = Modifier.clickable { settingsPage = SettingsPage.PERMISSIONS }
                                        )
                                        HorizontalDivider()
                                        ListItem(
                                            headlineContent = { Text("About & updates") },
                                            supportingContent = {
                                                Text(if (updateStatus.hasPendingUpdate()) "A new update is available"
                                                    else "Version ${BuildConfig.VERSION_NAME} · update options")
                                            },
                                            leadingContent = { Icon(Icons.Rounded.SystemUpdateRounded, contentDescription = null) },
                                            trailingContent = { dev.n2k02.updater.N2K02UpdateBadge(updateStatus) },
                                            modifier = Modifier.clickable { settingsPage = SettingsPage.APP_UPDATES }
                                        )
                                    }
                                }
                                SettingsPage.PERMISSIONS -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        ListItem(
                                            headlineContent = { Text("Display over other apps") },
                                            supportingContent = {
                                                Text(if (overlayReady) "Permission granted" else "Needed to display the progress bar")
                                            },
                                            leadingContent = { Icon(Icons.Rounded.Security, contentDescription = null) }
                                        )
                                        FilledTonalButton(
                                            onClick = {
                                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shapes = ButtonDefaults.shapes()
                                        ) {
                                            Text(if (overlayReady) "Manage display permission" else "Allow display over apps")
                                        }
                                        HorizontalDivider()
                                        ListItem(
                                            headlineContent = { Text("Notification access") },
                                            supportingContent = {
                                                Text(if (listenerReady) "Permission granted" else "Needed to detect media and downloads")
                                            },
                                            leadingContent = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null) }
                                        )
                                        FilledTonalButton(
                                            onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            shapes = ButtonDefaults.shapes()
                                        ) {
                                            Text(if (listenerReady) "Manage notification access" else "Allow notification access")
                                        }
                                    }
                                }
                                SettingsPage.APP_UPDATES -> UpdatesPage()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressLinePreview(
    position: String,
    thickness: Int,
    colour: ComposeColor,
    opacity: Int,
    rounded: Boolean,
    emphasiseProgress: Boolean,
    fraction: Float
) {
    val lineShape = if (rounded) RoundedCornerShape(50) else RoundedCornerShape(0.dp)
    val alignment = if (PBarPositions.anchoredAtBottom(position)) Alignment.BottomCenter else Alignment.TopCenter
    Box(
        Modifier.fillMaxWidth().height(112.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(thickness.dp)
                .align(alignment).background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (emphasiseProgress) 0.12f else 0.24f), lineShape)
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .align(Alignment.CenterStart)
                    .background(colour.copy(alpha = opacity / 100f), lineShape)
            ) {
                if (emphasiseProgress && fraction > 0f && fraction < 1f) {
                    Box(
                        Modifier.fillMaxHeight().width(2.dp)
                            .align(Alignment.CenterEnd)
                            .background(ComposeColor.White.copy(alpha = opacity / 100f))
                    )
                }
            }
        }
    }
}
