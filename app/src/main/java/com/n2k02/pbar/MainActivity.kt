package com.n2k02.pbar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private enum class Tab(val label: String) { HOME("Home"), STYLE("Appearance"), UPDATES("Updates") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PBarApp() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PBarApp() {
        val context = LocalContext.current
        val prefs = remember { getSharedPreferences("pbar", MODE_PRIVATE) }
        var tab by remember { mutableStateOf(Tab.HOME) }
        var overlayEnabled by remember { mutableStateOf(prefs.getBoolean("overlay_enabled", false)) }
        var style by remember { mutableStateOf(prefs.getString("style", "line") ?: "line") }
        var position by remember { mutableStateOf(prefs.getString("position", "top") ?: "top") }
        var latestVersion by remember { mutableStateOf<String?>(null) }
        var apkUrl by remember { mutableStateOf<String?>(null) }
        var releaseUrl by remember { mutableStateOf("https://github.com/N2K02/PBar/releases/latest") }
        var updateMessage by remember { mutableStateOf("Check GitHub for the latest PBar release.") }
        var checking by remember { mutableStateOf(false) }

        MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
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
                            ElevatedCard(Modifier.fillMaxWidth()) {
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
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("What PBar can detect", style = MaterialTheme.typography.titleMedium)
                                    Text("• Download or transfer progress when the source notification exposes progress.")
                                    Text("• Media notifications, shown as a music indicator.")
                                    Text("Some apps do not publish exact progress. PBar cannot show data that Android or the source app does not provide.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text("Position", style = MaterialTheme.typography.titleMedium)
                                    listOf("top" to "Top edge", "camera" to "Near camera", "bottom" to "Bottom edge").forEach { (value, label) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = position == value, onClick = { position = value; prefs.edit().putString("position", value).apply(); if (overlayEnabled) startService(Intent(this@MainActivity, FloatingIndicatorService::class.java)) })
                                            Text(label)
                                        }
                                    }
                                    Text("“Near camera” is an approximate top-center position; exact placement depends on the device's cutout.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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
                                        Button(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl ?: releaseUrl))) }, modifier = Modifier.fillMaxWidth()) { Text("Download version $latestVersion") }
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
