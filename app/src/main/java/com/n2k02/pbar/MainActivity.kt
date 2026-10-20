package com.n2k02.pbar

import android.app.DownloadManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private enum class Tab(val label: String) { HOME("Home"), STYLE("Appearance"), APPS("Apps"), UPDATES("Updates") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
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
            startService(Intent(this, FloatingIndicatorService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("pbar", MODE_PRIVATE)
        prefs.edit().putBoolean("overlay_enabled", true).apply()
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingIndicatorService::class.java))
        }
    }

    private fun quitPBar() {
        getSharedPreferences("pbar", MODE_PRIVATE).edit().putBoolean("overlay_enabled", false).apply()
        stopService(Intent(this, FloatingIndicatorService::class.java))
        finishAndRemoveTask()
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun PBarApp() {
        val context = LocalContext.current
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val prefs = remember { getSharedPreferences("pbar", MODE_PRIVATE) }
        var tab by remember { mutableStateOf(Tab.HOME) }
        var overlayEnabled by remember { mutableStateOf(true) }
        var style by remember { mutableStateOf("line") }
        var position by remember { mutableStateOf(prefs.getString("position", "top") ?: "top") }
        var orientation by remember { mutableStateOf(prefs.getString("orientation", "horizontal") ?: "horizontal") }
        var thickness by remember { mutableIntStateOf(prefs.getInt("thickness", 4).coerceIn(2, 12)) }
        var appColor by remember { mutableStateOf(prefs.getBoolean("app_color", true)) }
        var customColorHex by remember { mutableStateOf(prefs.getString("indicator_custom_color", "#8B5CF6") ?: "#8B5CF6") }
        var customColorMessage by remember { mutableStateOf("") }
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
        var knownPackages by remember { mutableStateOf(prefs.getStringSet("known_packages", emptySet())?.toSet() ?: emptySet()) }
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
        }

        val dark = isSystemInDarkTheme()
        val scheme = when {
            Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive()) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = { Column { Text("PBar", fontWeight = FontWeight.Bold); Text(tab.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = tab == Tab.HOME, onClick = { tab = Tab.HOME }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                        NavigationBarItem(selected = tab == Tab.STYLE, onClick = { tab = Tab.STYLE }, icon = { Icon(Icons.Default.Palette, null) }, label = { Text("Appearance") })
                        NavigationBarItem(selected = tab == Tab.APPS, onClick = { tab = Tab.APPS; knownPackages = prefs.getStringSet("known_packages", emptySet())?.toSet() ?: emptySet(); ignoredPackages = prefs.getStringSet("ignored_packages", emptySet())?.toSet() ?: emptySet(); appColorOverrides = prefs.getStringSet("app_color_overrides", emptySet()).orEmpty().mapNotNull { entry -> val split = entry.split("=", limit = 2); if (split.size == 2) split[0] to split[1] else null }.toMap() }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Apps") })
                        NavigationBarItem(selected = tab == Tab.UPDATES, onClick = { tab = Tab.UPDATES }, icon = { Icon(Icons.Default.SystemUpdate, null) }, label = { Text("Updates") })
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (tab) {
                        Tab.HOME -> {
                            val overlayReady = Settings.canDrawOverlays(this@MainActivity)
                            val listenerReady = runCatching {
                                val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
                                enabled.split(':').any { it.equals(ComponentName(this@MainActivity, ProgressNotificationListener::class.java).flattenToString(), ignoreCase = true) }
                            }.getOrDefault(false)
                            Text("PBar runs in the background", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Once permissions are granted, PBar watches supported notifications and shows a progress bar automatically. No on/off switch is needed.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Palette, contentDescription = null, tint = if (overlayReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Display over other apps", style = MaterialTheme.typography.titleMedium)
                                            Text(if (overlayReady) "Permission granted" else "Required to show the progress bar", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(if (overlayReady) "Ready" else "Needed", style = MaterialTheme.typography.labelMedium)
                                    }
                                    if (!overlayReady) {
                                        Button(onClick = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Grant overlay permission")
                                        }
                                    }
                                    HorizontalDivider()
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Tune, contentDescription = null, tint = if (listenerReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Notification access", style = MaterialTheme.typography.titleMedium)
                                            Text(if (listenerReady) "PBar can read supported progress and media notifications" else "Required to detect progress and media", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(if (listenerReady) "Ready" else "Needed", style = MaterialTheme.typography.labelMedium)
                                    }
                                    if (!listenerReady) {
                                        OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Grant notification access")
                                        }
                                    }
                                    Text("PBar uses a regular background service without a permanent PBar notification. Android may still stop it under battery or memory pressure.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Live tracking status", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        when {
                                            !signalHas -> "No progress signal is currently saved. Check notification access and whether the source app exposes an active media session or progress notification."
                                            signalMedia && signalIndeterminate -> "Media detected — timing data is unavailable, so PBar should show a moving indeterminate segment."
                                            signalMedia && signalDuration > 0L -> "Media detected — " + (if (signalPlaying) "playing" else "paused") + " at " + signalProgress + "%."
                                            signalMedia -> "Media detected — playback timing is not available."
                                            signalIndeterminate -> "An indeterminate progress signal is active."
                                            else -> "Progress signal active: " + signalProgress + "%."
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (signalPackage.isNotBlank()) {
                                        Text("Source: " + signalPackage, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (signalMedia && signalDuration > 0L) {
                                        Text("Position: " + (signalPosition / 1000L) + "s / " + (signalDuration / 1000L) + "s", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (signalUpdated > 0L) {
                                        Text("Last signal: " + ((System.currentTimeMillis() - signalUpdated).coerceAtLeast(0L) / 1000L) + "s ago", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(onClick = { refreshSignalStatus() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Refresh status")
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Make it yours", style = MaterialTheme.typography.titleLarge)
                                    Text("Change bar position, orientation, thickness, colour, opacity, and corner style in Appearance. Choose which apps PBar watches in Apps.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { tab = Tab.STYLE }) { Text("Appearance") }
                                        OutlinedButton(onClick = { tab = Tab.APPS }) { Text("Choose apps") }
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Quit PBar", style = MaterialTheme.typography.titleLarge)
                                    Text("Stops the floating progress bar and closes PBar. Open PBar again to resume automatic tracking.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedButton(onClick = { quitPBar() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Quit PBar")
                                    }
                                }
                            }
                        }
                        Tab.STYLE -> {
                            Text("Choose your indicator", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Changes are saved immediately.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Progress bar", style = MaterialTheme.typography.titleMedium)
                                    Text("Bar only — ring mode has been removed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Orientation", style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = orientation == "horizontal", onClick = { orientation = "horizontal"; if (position !in listOf("top", "bottom")) position = "top"; prefs.edit().putString("orientation", orientation).putString("position", position).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, label = { Text("Horizontal") })
                                        FilterChip(selected = orientation == "vertical", onClick = { orientation = "vertical"; if (position !in listOf("left", "center", "right")) position = "left"; prefs.edit().putString("orientation", orientation).putString("position", position).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, label = { Text("Vertical") })
                                    }
                                    Text("Thickness: $thickness dp", style = MaterialTheme.typography.titleMedium)
                                    Slider(value = thickness.toFloat(), onValueChange = { thickness = it.toInt().coerceIn(2, 12); prefs.edit().putInt("thickness", thickness).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, valueRange = 2f..12f, steps = 9)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Use app colour", style = MaterialTheme.typography.titleMedium)
                                            Text("Uses the source notification's colour when available. A custom colour is used when no source colour is available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = appColor, onCheckedChange = { appColor = it; prefs.edit().putBoolean("app_color", it).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) })
                                    }
                                    OutlinedTextField(
                                        value = customColorHex,
                                        onValueChange = { customColorHex = it; customColorMessage = "" },
                                        label = { Text("Default colour (hex)") },
                                        supportingText = { Text(customColorMessage.ifBlank { "Example: #8B5CF6" }) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(onClick = {
                                        val normalized = if (customColorHex.startsWith("#")) customColorHex else "#$customColorHex"
                                        val valid = runCatching { android.graphics.Color.parseColor(normalized) }.isSuccess
                                        if (valid) {
                                            customColorHex = normalized
                                            prefs.edit().putString("indicator_custom_color", normalized).apply()
                                            customColorMessage = "Saved"
                                            if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java))
                                        } else customColorMessage = "Enter a valid hex colour, such as #8B5CF6"
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Apply custom colour") }
                                    Text("Position", style = MaterialTheme.typography.titleMedium)
                                    val positionOptions = if (orientation == "vertical") {
                                        listOf("left" to "Left edge", "center" to "Centre", "right" to "Right edge")
                                    } else {
                                        listOf("top" to "Top edge", "bottom" to "Bottom edge")
                                    }
                                    positionOptions.forEach { (value, label) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = position == value, onClick = { position = value; prefs.edit().putString("position", value).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) })
                                            Text(label)
                                        }
                                    }

                                    HorizontalDivider()
                                    Text("Appearance details", style = MaterialTheme.typography.titleMedium)
                                    Text("Opacity: $indicatorOpacity%", style = MaterialTheme.typography.bodyMedium)
                                    Slider(value = indicatorOpacity.toFloat(), onValueChange = { indicatorOpacity = it.toInt().coerceIn(20, 100); prefs.edit().putInt("indicator_opacity", indicatorOpacity).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, valueRange = 20f..100f, steps = 39)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Rounded ends", style = MaterialTheme.typography.titleMedium)
                                            Text("Use soft rounded edges on the progress line.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = roundedCorners, onCheckedChange = { roundedCorners = it; prefs.edit().putBoolean("rounded_corners", it).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) })
                                    }
                                    HorizontalDivider()
                                    Text("Tracking", style = MaterialTheme.typography.titleMedium)
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
                                    HorizontalDivider()
                                    Text("Screen behaviour", style = MaterialTheme.typography.titleMedium)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Hide in fullscreen", style = MaterialTheme.typography.titleSmall)
                                            Text("Hide the indicator in immersive apps and fullscreen video. Requires the optional PBar accessibility helper.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = hideFullscreen, onCheckedChange = { value ->
                                            hideFullscreen = value
                                            prefs.edit().putBoolean("hide_fullscreen", value).apply()
                                            if (value) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                            if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java))
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
                                            if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java))
                                        })
                                    }
                                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Open accessibility settings")
                                    }
                                }
                            }
                        }
                        Tab.APPS -> {
                            Text("Choose which apps PBar tracks", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("PBar remembers apps that have published a progress or media notification. Turn tracking off for unwanted sources, or choose a colour for each app. Settings stay on your device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedButton(onClick = {
                                            knownPackages = prefs.getStringSet("known_packages", emptySet())?.toSet() ?: emptySet()
                                            ignoredPackages = prefs.getStringSet("ignored_packages", emptySet())?.toSet() ?: emptySet()
                                            appColorOverrides = prefs.getStringSet("app_color_overrides", emptySet()).orEmpty().mapNotNull { entry -> val split = entry.split("=", limit = 2); if (split.size == 2) split[0] to split[1] else null }.toMap()
                                        }, modifier = Modifier.weight(1f)) { Text("Refresh list") }
                                        OutlinedButton(onClick = {
                                            ignoredPackages = emptySet()
                                            prefs.edit().putStringSet("ignored_packages", emptySet()).apply()
                                        }, modifier = Modifier.weight(1f)) { Text("Track all") }
                                    }
                                    if (knownPackages.isEmpty()) {
                                        Text("No source apps detected yet. Enable notification access, then play media or start a download and return here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        val detectedApps = knownPackages.map { packageName ->
                                            val label = runCatching {
                                                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
                                            }.getOrDefault(packageName)
                                            packageName to label
                                        }.sortedBy { it.second.lowercase() }
                                        detectedApps.forEach { (packageName, label) ->
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    var advancedExpanded by remember(packageName) { mutableStateOf(false) }
                                                    var styleMenuExpanded by remember(packageName) { mutableStateOf(false) }
                                                    var orientationMenuExpanded by remember(packageName) { mutableStateOf(false) }
                                                    var positionMenuExpanded by remember(packageName) { mutableStateOf(false) }
                                                    var appStyle by remember(packageName) { mutableStateOf(prefs.getString("app_${packageName}_style", "default") ?: "default") }
                                                    var appOrientation by remember(packageName) { mutableStateOf(prefs.getString("app_${packageName}_orientation", "default") ?: "default") }
                                                    var appPosition by remember(packageName) { mutableStateOf(prefs.getString("app_${packageName}_position", "default") ?: "default") }
                                                    var appCustomHex by remember(packageName) { mutableStateOf(appColorOverrides[packageName] ?: "") }
                                                    var appThickness by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_thickness", prefs.getInt("thickness", 4)).coerceIn(2, 12)) }
                                                    var appRingSize by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_size", prefs.getInt("ring_size", 36)).coerceIn(24, 80)) }
                                                    var appRingStroke by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_stroke", prefs.getInt("ring_stroke", 5)).coerceIn(2, 10)) }
                                                    var appRingOffsetX by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_offset_x", prefs.getInt("ring_offset_x", 0)).coerceIn(-150, 150)) }
                                                    var appRingOffsetY by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_offset_y", prefs.getInt("ring_offset_y", 0)).coerceIn(0, 80)) }
                                                    Text(label, style = MaterialTheme.typography.titleMedium)
                                                    Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        var colorMenuExpanded by remember(packageName) { mutableStateOf(false) }
                                                        val currentColorName = appColorOptions.firstOrNull { it.second == appColorOverrides[packageName] }?.first ?: if (appColorOverrides[packageName].isNullOrBlank()) "Automatic" else "Custom"
                                                        TextButton(onClick = { colorMenuExpanded = true }) { Text("Colour: $currentColorName") }
                                                        DropdownMenu(expanded = colorMenuExpanded, onDismissRequest = { colorMenuExpanded = false }) {
                                                            appColorOptions.forEach { (optionName, hex) ->
                                                                DropdownMenuItem(text = { Text(optionName) }, onClick = {
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
                                                        TextButton(onClick = { advancedExpanded = !advancedExpanded }) { Text(if (advancedExpanded) "Less" else "Customize") }
                                                    }
                                                    if (advancedExpanded) {
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
                                                            }) { Text("Apply") }
                                                        }
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text("Style", modifier = Modifier.weight(1f))
                                                            TextButton(onClick = { styleMenuExpanded = true }) { Text(when (appStyle) { "line" -> "Line"; else -> "Default" }) }
                                                            DropdownMenu(expanded = styleMenuExpanded, onDismissRequest = { styleMenuExpanded = false }) {
                                                                listOf("default" to "Default", "line" to "Line").forEach { (value, label) ->
                                                                    DropdownMenuItem(text = { Text(label) }, onClick = {
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
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text("Orientation", modifier = Modifier.weight(1f))
                                                            TextButton(onClick = { orientationMenuExpanded = true }) { Text(if (appOrientation == "default") "Default" else appOrientation.replaceFirstChar { it.uppercase() }) }
                                                            DropdownMenu(expanded = orientationMenuExpanded, onDismissRequest = { orientationMenuExpanded = false }) {
                                                                listOf("default" to "Default", "horizontal" to "Horizontal", "vertical" to "Vertical").forEach { (value, label) ->
                                                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                                                        appOrientation = value
                                                                        val edit = prefs.edit()
                                                                        if (value == "default") edit.remove("app_${packageName}_orientation") else edit.putString("app_${packageName}_orientation", value)
                                                                        edit.apply()
                                                                        orientationMenuExpanded = false
                                                                        sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                                    })
                                                                }
                                                            }
                                                        }
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text("Position", modifier = Modifier.weight(1f))
                                                            TextButton(onClick = { positionMenuExpanded = true }) { Text(if (appPosition == "default") "Default" else appPosition.replaceFirstChar { it.uppercase() }) }
                                                            DropdownMenu(expanded = positionMenuExpanded, onDismissRequest = { positionMenuExpanded = false }) {
                                                                (if ((appOrientation == "default" && orientation != "vertical") || appOrientation == "horizontal") {
                                                                    listOf("default" to "Default", "top" to "Top", "bottom" to "Bottom")
                                                                } else {
                                                                    listOf("default" to "Default", "left" to "Left", "center" to "Centre", "right" to "Right")
                                                                }).forEach { (value, label) ->
                                                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                                                        appPosition = value
                                                                        val edit = prefs.edit()
                                                                        if (value == "default") edit.remove("app_${packageName}_position") else edit.putString("app_${packageName}_position", value)
                                                                        edit.apply()
                                                                        positionMenuExpanded = false
                                                                        sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                                    })
                                                                }
                                                            }
                                                        }
                                                        Text("Thickness: $appThickness dp", style = MaterialTheme.typography.bodySmall)
                                                        Slider(value = appThickness.toFloat(), onValueChange = { appThickness = it.toInt().coerceIn(2, 12); prefs.edit().putInt("app_${packageName}_thickness", appThickness).apply(); sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName)) }, valueRange = 2f..12f, steps = 9)

                                                        TextButton(onClick = {
                                                            prefs.edit().remove("app_${packageName}_style").remove("app_${packageName}_orientation").remove("app_${packageName}_position").remove("app_${packageName}_thickness").remove("app_${packageName}_ring_size").remove("app_${packageName}_ring_offset_x").remove("app_${packageName}_ring_offset_y").remove("app_${packageName}_ring_stroke").apply()
                                                            appStyle = "default"; appOrientation = "default"; appPosition = "default"; appThickness = prefs.getInt("thickness", 4); appRingSize = prefs.getInt("ring_size", 36); appRingOffsetX = prefs.getInt("ring_offset_x", 0); appRingOffsetY = prefs.getInt("ring_offset_y", 0); appRingStroke = prefs.getInt("ring_stroke", 5)
                                                            sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                        }) { Text("Reset app style") }
                                                    }
                                                }
                                                Switch(
                                                    checked = packageName !in ignoredPackages,
                                                    onCheckedChange = { track ->
                                                        val updated = if (track) ignoredPackages - packageName else ignoredPackages + packageName
                                                        ignoredPackages = updated
                                                        prefs.edit().putStringSet("ignored_packages", updated).apply()
                                                        if (!track && prefs.getString("signal_package", null) == packageName) {
                                                            prefs.edit().putBoolean("has_signal", false).apply()
                                                            sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName))
                                                        }
                                                    }
                                                )
                                            }
                                            if (packageName != detectedApps.last().first) HorizontalDivider()
                                        }
                                    }
                                }
                            }
                            Text("Unlike a global on/off switch, app filtering lets downloads and media from your preferred apps remain visible while unwanted sources stay hidden.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Tab.UPDATES -> {
                            Text("App updates", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("PBar", style = MaterialTheme.typography.titleLarge)
                                    Text("Installed version: ${BuildConfig.VERSION_NAME}")
                                    Text(updateMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(
                                        enabled = !checking,
                                        onClick = {
                                            checking = true
                                            updateMessage = "Checking GitHub Releases…"
                                            lifecycleScope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        val connection = URL("https://api.github.com/repos/N2K02/PBar/releases/latest").openConnection() as HttpURLConnection
                                                        connection.connectTimeout = 10000
                                                        connection.readTimeout = 15000
                                                        try {
                                                            connection.setRequestProperty("Accept", "application/vnd.github+json")
                                                            if (connection.responseCode == 404) null else {
                                                                if (connection.responseCode != 200) error("GitHub returned HTTP ${connection.responseCode}")
                                                                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                                                                val tag = json.optString("tag_name")
                                                                val asset = (0 until (json.optJSONArray("assets")?.length() ?: 0)).map { json.getJSONArray("assets").getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk", true) }
                                                                Triple(tag.removePrefix("v"), asset?.optString("browser_download_url"), json.optString("html_url"))
                                                            }
                                                        } finally { connection.disconnect() }
                                                    }
                                                }
                                                result.onSuccess { release ->
                                                    if (release == null) updateMessage = "No public release is available yet."
                                                    else {
                                                        latestVersion = release.first
                                                        apkUrl = release.second
                                                        releaseUrl = release.third
                                                        updateMessage = if (release.first == BuildConfig.VERSION_NAME) "You're up to date." else "Latest release: ${release.first}. Open the release to download and install it."
                                                    }
                                                }.onFailure { updateMessage = "Couldn't check for updates. Check your connection and try again." }
                                                checking = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text(if (checking) "Checking…" else "Check for updates") }
                                    if (latestVersion != null && latestVersion != BuildConfig.VERSION_NAME) {
                                        Button(
                                            enabled = !downloading && apkUrl != null,
                                            onClick = {
                                                val url = apkUrl ?: return@Button
                                                lifecycleScope.launch {
                                                    downloading = true
                                                    downloadProgress = 0
                                                    downloadedUri = null
                                                    try {
                                                        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                                                        val request = DownloadManager.Request(Uri.parse(url))
                                                            .setTitle("PBar $latestVersion")
                                                            .setDescription("Downloading app update")
                                                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                            .setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, "PBar-$latestVersion.apk")
                                                        val id = manager.enqueue(request)
                                                        var finished = false
                                                        while (!finished) {
                                                            val cursor = manager.query(DownloadManager.Query().setFilterById(id))
                                                            cursor.use {
                                                                if (!it.moveToFirst()) error("Download could not be found.")
                                                                when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                                                                    DownloadManager.STATUS_SUCCESSFUL -> {
                                                                        downloadedUri = manager.getUriForDownloadedFile(id) ?: error("Downloaded APK is unavailable.")
                                                                        downloadProgress = 100
                                                                        finished = true
                                                                    }
                                                                    DownloadManager.STATUS_FAILED -> {
                                                                        val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                                                        error("Download failed (code $reason).")
                                                                    }
                                                                    else -> {
                                                                        val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                                                        val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                                                        if (total > 0) downloadProgress = ((done * 100) / total).toInt().coerceIn(0, 100)
                                                                    }
                                                                }
                                                            }
                                                            if (!finished) delay(500)
                                                        }
                                                        updateMessage = "Download complete. Install the update when ready."
                                                    } catch (e: Exception) {
                                                        updateMessage = e.message ?: "Download failed. Please try again."
                                                    } finally {
                                                        downloading = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(if (downloading) "Downloading $downloadProgress%" else "Download version $latestVersion") }
                                        if (downloading) {
                                            LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                                        }
                                        val installUri = downloadedUri
                                    if (installUri != null) {
                                            Button(
                                                onClick = {
                                                    if (!packageManager.canRequestPackageInstalls()) {
                                                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                                                    } else {
                                                        startActivity(Intent(Intent.ACTION_VIEW)
                                                            .setDataAndType(installUri, "application/vnd.android.package-archive")
                                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("Install update") }
                                        }
                                    }
                                    OutlinedButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))) }, modifier = Modifier.fillMaxWidth()) { Text("View GitHub release") }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("PBar • Material 3 Expressive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
}
