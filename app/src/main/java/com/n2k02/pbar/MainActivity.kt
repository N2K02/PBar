package com.n2k02.pbar

import android.app.DownloadManager
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
        setContent { PBarApp() }
        if (getSharedPreferences("pbar", MODE_PRIVATE).getBoolean("overlay_enabled", false) && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingIndicatorService::class.java))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun PBarApp() {
        val context = LocalContext.current
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val prefs = remember { getSharedPreferences("pbar", MODE_PRIVATE) }
        var tab by remember { mutableStateOf(Tab.HOME) }
        var overlayEnabled by remember { mutableStateOf(prefs.getBoolean("overlay_enabled", false)) }
        var style by remember { mutableStateOf(prefs.getString("style", "line") ?: "line") }
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
        var ringSize by remember { mutableIntStateOf(prefs.getInt("ring_size", 68).coerceIn(40, 120)) }
        var ringOffsetX by remember { mutableIntStateOf(prefs.getInt("ring_offset_x", 0).coerceIn(-150, 150)) }
        var ringOffsetY by remember { mutableIntStateOf(prefs.getInt("ring_offset_y", 28).coerceIn(0, 160)) }
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
                            Text("Your progress, at a glance", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("PBar can show progress from apps that publish it in notifications, plus a small indicator for media notifications.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                maxItemsInEachRow = if (landscape) 2 else 1
                            ) {
                                ElevatedCard(Modifier.fillMaxWidth(if (landscape) 0.48f else 1f)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("Floating indicator", style = MaterialTheme.typography.titleLarge)
                                        Text(if (overlayEnabled) "Enabled — the indicator can appear over other apps." else "Off — turn it on to show the indicator outside PBar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Button(
                                            onClick = {
                                                if (!Settings.canDrawOverlays(this@MainActivity)) {
                                                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                                } else {
                                                    overlayEnabled = !overlayEnabled
                                                    prefs.edit().putBoolean("overlay_enabled", overlayEnabled).apply()
                                                    if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java))
                                                    else stopService(Intent(this@MainActivity, FloatingIndicatorService::class.java))
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(if (!Settings.canDrawOverlays(this@MainActivity)) "Grant overlay permission" else if (overlayEnabled) "Turn indicator off" else "Turn indicator on") }
                                        OutlinedButton(
                                            onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Set up notification access") }
                                    }
                                }
                                ElevatedCard(Modifier.fillMaxWidth(if (landscape) 0.48f else 1f)) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("What PBar can detect", style = MaterialTheme.typography.titleMedium)
                                        Text("• Download or transfer progress when the source notification exposes progress.")
                                        Text("• Media notifications, shown as a music indicator.")
                                        Text("Some apps do not publish exact progress. PBar cannot show data that Android or the source app does not provide.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Tab.STYLE -> {
                            Text("Choose your indicator", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Changes are saved immediately.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Shape", style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = style == "line", onClick = { style = "line"; prefs.edit().putString("style", style).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, label = { Text("Progress line") })
                                        FilterChip(selected = style == "ring", onClick = { style = "ring"; prefs.edit().putString("style", style).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, label = { Text("Ring") })
                                    }
                                    Text("Orientation", style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = orientation == "horizontal", onClick = { orientation = "horizontal"; if (position !in listOf("top", "camera", "bottom")) position = "top"; prefs.edit().putString("orientation", orientation).putString("position", position).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, label = { Text("Horizontal") })
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
                                    val positionOptions = if (style == "ring") {
                                        listOf("top" to "Top centre", "camera" to "Near camera", "bottom" to "Bottom centre")
                                    } else if (orientation == "vertical") {
                                        listOf("left" to "Left edge", "center" to "Centre", "right" to "Right edge")
                                    } else {
                                        listOf("top" to "Top edge", "camera" to "Near camera", "bottom" to "Bottom edge")
                                    }
                                    positionOptions.forEach { (value, label) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = position == value, onClick = { position = value; prefs.edit().putString("position", value).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) })
                                            Text(label)
                                        }
                                    }
                                    Text("“Near camera” is an approximate top-center position; exact placement depends on the device's cutout.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (style == "ring") {
                                        HorizontalDivider()
                                        Text("Camera-hole calibration", style = MaterialTheme.typography.titleMedium)
                                        Text("Ring size: $ringSize dp", style = MaterialTheme.typography.bodyMedium)
                                        Slider(value = ringSize.toFloat(), onValueChange = { ringSize = it.toInt().coerceIn(40, 120); prefs.edit().putInt("ring_size", ringSize).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, valueRange = 40f..120f, steps = 39)
                                        Text("Horizontal offset: $ringOffsetX dp", style = MaterialTheme.typography.bodyMedium)
                                        Slider(value = ringOffsetX.toFloat(), onValueChange = { ringOffsetX = it.toInt().coerceIn(-150, 150); prefs.edit().putInt("ring_offset_x", ringOffsetX).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, valueRange = -150f..150f, steps = 59)
                                        Text("Vertical offset: $ringOffsetY dp", style = MaterialTheme.typography.bodyMedium)
                                        Slider(value = ringOffsetY.toFloat(), onValueChange = { ringOffsetY = it.toInt().coerceIn(0, 160); prefs.edit().putInt("ring_offset_y", ringOffsetY).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, valueRange = 0f..160f, steps = 39)
                                        Text("Ring stroke: $ringStroke dp", style = MaterialTheme.typography.bodyMedium)
                                        Slider(value = ringStroke.toFloat(), onValueChange = { ringStroke = it.toInt().coerceIn(2, 10); prefs.edit().putInt("ring_stroke", ringStroke).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) }, valueRange = 2f..10f, steps = 7)
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
                                                        "Downloads and transfers" -> { showForDownloads = value; prefs.edit().putBoolean("show_for_downloads", value).apply() }
                                                        "Music and media" -> { showForMedia = value; prefs.edit().putBoolean("show_for_media", value).apply() }
                                                        else -> { showForTimers = value; prefs.edit().putBoolean("show_for_timers", value).apply() }
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
                                                    var appRingSize by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_size", prefs.getInt("ring_size", 68)).coerceIn(40, 120)) }
                                                    var appRingOffsetX by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_offset_x", prefs.getInt("ring_offset_x", 0)).coerceIn(-150, 150)) }
                                                    var appRingOffsetY by remember(packageName) { mutableIntStateOf(prefs.getInt("app_${packageName}_ring_offset_y", prefs.getInt("ring_offset_y", 28)).coerceIn(0, 160)) }
                                                    Text(label, style = MaterialTheme.typography.titleMedium)
                                                    Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        var colorMenuExpanded by remember(packageName) { mutableStateOf(false) }
                                                        val currentColorName = appColorOptions.firstOrNull { it.second == appColorOverrides[packageName] }?.first ?: "Automatic"
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
                                                            TextButton(onClick = { styleMenuExpanded = true }) { Text(when (appStyle) { "line" -> "Line"; "ring" -> "Ring"; else -> "Default" }) }
                                                            DropdownMenu(expanded = styleMenuExpanded, onDismissRequest = { styleMenuExpanded = false }) {
                                                                listOf("default" to "Default", "line" to "Line", "ring" to "Ring").forEach { (value, label) ->
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
                                                                listOf("default" to "Default", "top" to "Top", "camera" to "Near camera", "bottom" to "Bottom", "left" to "Left", "center" to "Centre", "right" to "Right").forEach { (value, label) ->
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
                                                        if (appStyle == "ring") {
                                                            Text("Ring size: $appRingSize dp", style = MaterialTheme.typography.bodySmall)
                                                            Slider(value = appRingSize.toFloat(), onValueChange = { appRingSize = it.toInt().coerceIn(40, 120); prefs.edit().putInt("app_${packageName}_ring_size", appRingSize).apply(); sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName)) }, valueRange = 40f..120f, steps = 39)
                                                            Text("Camera X offset: $appRingOffsetX dp", style = MaterialTheme.typography.bodySmall)
                                                            Slider(value = appRingOffsetX.toFloat(), onValueChange = { appRingOffsetX = it.toInt().coerceIn(-150, 150); prefs.edit().putInt("app_${packageName}_ring_offset_x", appRingOffsetX).apply(); sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName)) }, valueRange = -150f..150f, steps = 59)
                                                            Text("Camera Y offset: $appRingOffsetY dp", style = MaterialTheme.typography.bodySmall)
                                                            Slider(value = appRingOffsetY.toFloat(), onValueChange = { appRingOffsetY = it.toInt().coerceIn(0, 160); prefs.edit().putInt("app_${packageName}_ring_offset_y", appRingOffsetY).apply(); sendBroadcast(Intent(ProgressNotificationListener.ACTION_SIGNAL).setPackage(this@MainActivity.packageName)) }, valueRange = 0f..160f, steps = 39)
                                                        }
                                                        TextButton(onClick = {
                                                            prefs.edit().remove("app_${packageName}_style").remove("app_${packageName}_orientation").remove("app_${packageName}_position").remove("app_${packageName}_thickness").remove("app_${packageName}_ring_size").remove("app_${packageName}_ring_offset_x").remove("app_${packageName}_ring_offset_y").apply()
                                                            appStyle = "default"; appOrientation = "default"; appPosition = "default"; appThickness = prefs.getInt("thickness", 4); appRingSize = prefs.getInt("ring_size", 68); appRingOffsetX = prefs.getInt("ring_offset_x", 0); appRingOffsetY = prefs.getInt("ring_offset_y", 28)
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
