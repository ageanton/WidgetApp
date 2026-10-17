package com.local.monolith

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

const val PREFS_NAME = "soundpad_prefs_v2"
const val KEY_TARGET_PERCENT = "target_percent"
const val KEY_DISPLAY_PERCENT = "display_percent"
const val KEY_PREV_STREAM_VOL = "prev_stream_vol"
const val KEY_IS_CLAMPED = "is_clamped"

const val ACTION_TOGGLE_VOLUME = "com.local.monolith.ACTION_TOGGLE_VOLUME"

// ============================================================================
// CORE VOLUME TOGGLE, MOBILE DATA, BATTERY, INTERNET & DISPLAY ACTIONS
// ============================================================================

fun handleVolumeTileTap(context: Context): Int {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    val targetPercent = prefs.getInt(KEY_TARGET_PERCENT, 30)
    val targetIndex = ((targetPercent / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
    val isClamped = prefs.getBoolean(KEY_IS_CLAMPED, false)

    val newDisplayPercent = if (!isClamped || currentVol != targetIndex) {
        prefs.edit()
            .putInt(KEY_PREV_STREAM_VOL, currentVol)
            .putBoolean(KEY_IS_CLAMPED, true)
            .putInt(KEY_DISPLAY_PERCENT, targetPercent)
            .apply()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        targetPercent
    } else {
        val fallbackVol = (maxVol * 0.75f).roundToInt().coerceIn(1, maxVol)
        val restoreIndex = prefs.getInt(KEY_PREV_STREAM_VOL, fallbackVol).coerceIn(0, maxVol)
        val restoredPercent = ((restoreIndex.toFloat() / maxVol.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        prefs.edit()
            .putBoolean(KEY_IS_CLAMPED, false)
            .putInt(KEY_DISPLAY_PERCENT, restoredPercent)
            .apply()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreIndex, 0)
        restoredPercent
    }

    updateAllWidgets(context)
    return newDisplayPercent
}

fun setPreferredVolumeFromApp(context: Context, percent: Int) {
    val clamped = percent.coerceIn(0, 100)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val targetIndex = ((clamped / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)

    prefs.edit()
        .putInt(KEY_TARGET_PERCENT, clamped)
        .putInt(KEY_DISPLAY_PERCENT, clamped)
        .putBoolean(KEY_IS_CLAMPED, true)
        .apply()

    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
    updateAllWidgets(context)
}

/**
 * Restored exact Mobile Data behavior from the working second-to-last version:
 * Pops up Android's native Internet Connectivity bottom sheet panel with the Mobile Data switch.
 */
fun buildMobileDataPendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else {
        Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Direct Battery Protection sub-menu under Settings -> Battery -> Battery protection.
 */
fun resolveBatteryProtectionIntent(context: Context): Intent {
    val pm = context.packageManager

    val candidates = listOf(
        Intent().apply {
            component = ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.mode.BatteryProtectionActivity"
            )
        },
        Intent().apply {
            component = ComponentName(
                "com.samsung.android.sm_cn",
                "com.samsung.android.sm.battery.ui.mode.BatteryProtectionActivity"
            )
        },
        Intent("com.samsung.android.sm.ACTION_BATTERY_PROTECTION"),
        Intent(Intent.ACTION_POWER_USAGE_SUMMARY).apply {
            putExtra(":settings:fragment_args_key", "battery_protection")
        }
    )

    for (candidate in candidates) {
        candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pm.resolveActivity(candidate, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            return candidate
        }
    }

    return Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

fun buildBatteryProtectionPendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = resolveBatteryProtectionIntent(context)
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Opens the "Internet" page nested under "Network & internet" in the global Android Settings app.
 */
fun resolveInternetMenuIntent(context: Context): Intent {
    val pm = context.packageManager

    val candidates = listOf(
        Intent().apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$NetworkProviderSettingsActivity"
            )
        },
        Intent(Settings.ACTION_WIFI_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    )

    for (candidate in candidates) {
        candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pm.resolveActivity(candidate, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            return candidate
        }
    }

    return Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

fun buildInternetMenuPendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = resolveInternetMenuIntent(context)
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Opens the "Display" menu in the global Android Settings app.
 */
fun resolveDisplaySettingsIntent(context: Context): Intent {
    val pm = context.packageManager
    val displayIntent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (pm.resolveActivity(displayIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
        return displayIntent
    }
    return Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

fun buildDisplaySettingsPendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = resolveDisplaySettingsIntent(context)
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

fun updateAllWidgets(context: Context) {
    val mgr = AppWidgetManager.getInstance(context)

    val gridIds = mgr.getAppWidgetIds(ComponentName(context, ConsoleWidgetProvider::class.java))
    if (gridIds.isNotEmpty()) ConsoleWidgetProvider().onUpdate(context, mgr, gridIds)

    val volIds = mgr.getAppWidgetIds(ComponentName(context, VolumeWidgetProvider::class.java))
    if (volIds.isNotEmpty()) VolumeWidgetProvider().onUpdate(context, mgr, volIds)

    val dataIds = mgr.getAppWidgetIds(ComponentName(context, DataWidgetProvider::class.java))
    if (dataIds.isNotEmpty()) DataWidgetProvider().onUpdate(context, mgr, dataIds)

    val battIds = mgr.getAppWidgetIds(ComponentName(context, BatteryWidgetProvider::class.java))
    if (battIds.isNotEmpty()) BatteryWidgetProvider().onUpdate(context, mgr, battIds)

    val netIds = mgr.getAppWidgetIds(ComponentName(context, InternetWidgetProvider::class.java))
    if (netIds.isNotEmpty()) InternetWidgetProvider().onUpdate(context, mgr, netIds)

    val dispIds = mgr.getAppWidgetIds(ComponentName(context, DisplayWidgetProvider::class.java))
    if (dispIds.isNotEmpty()) DisplayWidgetProvider().onUpdate(context, mgr, dispIds)
}

// ============================================================================
// WIDGET PROVIDERS (2x3 5-TILE GRID + FIVE 1x1 STANDALONE WIDGETS)
// ============================================================================

class ConsoleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val volToggleIntent = Intent(context, ConsoleWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_VOLUME
        }
        val volPi = PendingIntent.getBroadcast(
            context,
            101,
            volToggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dataPi = buildMobileDataPendingIntent(context, 102)
        val batteryPi = buildBatteryProtectionPendingIntent(context, 103)
        val internetPi = buildInternetMenuPendingIntent(context, 104)
        val displayPi = buildDisplaySettingsPendingIntent(context, 105)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_grid_2x3)

            views.setContentDescription(R.id.tile_grid_volume, "Toggle preset volume")
            views.setContentDescription(R.id.tile_grid_data, "Mobile data")
            views.setContentDescription(R.id.tile_grid_battery, "Battery protection")
            views.setContentDescription(R.id.tile_grid_internet, "Internet")
            views.setContentDescription(R.id.tile_grid_display, "Display settings")

            views.setOnClickPendingIntent(R.id.tile_grid_volume, volPi)
            views.setOnClickPendingIntent(R.id.tile_grid_data, dataPi)
            views.setOnClickPendingIntent(R.id.tile_grid_battery, batteryPi)
            views.setOnClickPendingIntent(R.id.tile_grid_internet, internetPi)
            views.setOnClickPendingIntent(R.id.tile_grid_display, displayPi)

            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_VOLUME -> handleVolumeTileTap(context)
            Intent.ACTION_CONFIGURATION_CHANGED -> updateAllWidgets(context)
        }
    }
}

class VolumeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val volToggleIntent = Intent(context, VolumeWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_VOLUME
        }
        val volPi = PendingIntent.getBroadcast(
            context,
            201,
            volToggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            views.setImageViewResource(R.id.img_small_center_icon, R.drawable.ic_volume_up)
            views.setContentDescription(R.id.tile_small_root, "Toggle preset volume")
            views.setOnClickPendingIntent(R.id.tile_small_root, volPi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_VOLUME -> handleVolumeTileTap(context)
            Intent.ACTION_CONFIGURATION_CHANGED -> updateAllWidgets(context)
        }
    }
}

class DataWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val dataPi = buildMobileDataPendingIntent(context, 301)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            views.setImageViewResource(R.id.img_small_center_icon, R.drawable.ic_signal_cellular_alt)
            views.setContentDescription(R.id.tile_small_root, "Mobile data")
            views.setOnClickPendingIntent(R.id.tile_small_root, dataPi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            updateAllWidgets(context)
        }
    }
}

class BatteryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val batteryPi = buildBatteryProtectionPendingIntent(context, 401)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            views.setImageViewResource(R.id.img_small_center_icon, R.drawable.ic_shield)
            views.setContentDescription(R.id.tile_small_root, "Battery protection")
            views.setOnClickPendingIntent(R.id.tile_small_root, batteryPi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            updateAllWidgets(context)
        }
    }
}

class InternetWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val internetPi = buildInternetMenuPendingIntent(context, 501)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            views.setImageViewResource(R.id.img_small_center_icon, R.drawable.ic_language)
            views.setContentDescription(R.id.tile_small_root, "Internet")
            views.setOnClickPendingIntent(R.id.tile_small_root, internetPi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            updateAllWidgets(context)
        }
    }
}

class DisplayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val displayPi = buildDisplaySettingsPendingIntent(context, 601)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            views.setImageViewResource(R.id.img_small_center_icon, R.drawable.ic_display)
            views.setContentDescription(R.id.tile_small_root, "Display settings")
            views.setOnClickPendingIntent(R.id.tile_small_root, displayPi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            updateAllWidgets(context)
        }
    }
}

// ============================================================================
// MAIN ACTIVITY: COMPANION CALIBRATION SCREEN & LIVE 2x3 WIDGET PREVIEW
// ============================================================================

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        updateAllWidgets(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val isDark = isSystemInDarkTheme()
            val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

            var targetPercent by remember { mutableIntStateOf(prefs.getInt(KEY_TARGET_PERCENT, 30)) }

            val pageBg = if (isDark) Color(0xFF1E1D1B) else Color(0xFFE6E3DD)
            val containerBg = if (isDark) Color(0xFF2B2A28) else Color(0xFFF1EFEA)
            val tileBg = if (isDark) Color(0xFF3A3936) else Color(0xFFE5E3DE)
            val contentTint = if (isDark) Color(0xFFECEAE5) else Color(0xFF2E2D2B)
            val trackTint = if (isDark) Color(0xFF4A4844) else Color(0xFFD6D3CC)
            val progressTint = Color(0xFFD97757)

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = pageBg
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "SoundPad",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = contentTint,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Interactive 2x3 Widget Preview (Exact 5-button layout)
                        Box(
                            modifier = Modifier
                                .width(196.dp)
                                .height(276.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(containerBg)
                                .padding(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Row 1: Volume (left), Mobile Data (right)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(tileBg)
                                            .clickable { handleVolumeTileTap(context) }
                                            .padding(6.dp)
                                            .semantics { contentDescription = "Toggle preset volume" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_volume_up),
                                            contentDescription = null,
                                            tint = contentTint,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(tileBg)
                                            .clickable {
                                                runCatching { buildMobileDataPendingIntent(context, 901).send() }
                                            }
                                            .padding(6.dp)
                                            .semantics { contentDescription = "Mobile data" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_signal_cellular_alt),
                                            contentDescription = null,
                                            tint = contentTint,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }

                                // Row 2: Battery Protection (left), Internet (right)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(tileBg)
                                            .clickable {
                                                runCatching { context.startActivity(resolveBatteryProtectionIntent(context)) }
                                            }
                                            .padding(6.dp)
                                            .semantics { contentDescription = "Battery protection" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_shield),
                                            contentDescription = null,
                                            tint = contentTint,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(tileBg)
                                            .clickable {
                                                runCatching { context.startActivity(resolveInternetMenuIntent(context)) }
                                            }
                                            .padding(6.dp)
                                            .semantics { contentDescription = "Internet" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_language),
                                            contentDescription = null,
                                            tint = contentTint,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }

                                // Row 3: Display Settings (full-width bottom bar)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(tileBg)
                                        .clickable {
                                            runCatching { context.startActivity(resolveDisplaySettingsIntent(context)) }
                                        }
                                        .padding(6.dp)
                                        .semantics { contentDescription = "Display settings" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_display),
                                        contentDescription = null,
                                        tint = contentTint,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }

                        // Volume Preset Slider Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(28.dp))
                                .background(containerBg)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Preferred Volume",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = contentTint
                                )
                                Text(
                                    text = "$targetPercent%",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = progressTint
                                )
                            }

                            Slider(
                                value = targetPercent.toFloat(),
                                onValueChange = {
                                    val next = it.roundToInt().coerceIn(0, 100)
                                    targetPercent = next
                                    setPreferredVolumeFromApp(context, next)
                                },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
                                    thumbColor = progressTint,
                                    activeTrackColor = progressTint,
                                    inactiveTrackColor = trackTint
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
