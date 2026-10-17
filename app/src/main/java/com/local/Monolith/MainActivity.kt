package com.local.monolith

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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

const val ACTION_SET_PRESET_VOLUME = "com.local.monolith.ACTION_SET_PRESET_VOLUME"

// ============================================================================
// CORE ONE-WAY VOLUME PRESET & SETTINGS DEEP-LINK ACTIONS
// ============================================================================

fun applyPresetVolume(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val presetPercent = prefs.getInt(KEY_TARGET_PERCENT, 30).coerceIn(0, 100)
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val targetIndex = ((presetPercent / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
}

fun resolveMobileNetworkIntent(context: Context): Intent {
    val pm = context.packageManager

    val candidates = listOf(
        Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
        Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        Intent().apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$MobileNetworkListActivity"
            )
        },
        Intent().apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.network.telephony.MobileNetworkActivity"
            )
        },
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

fun buildMobileDataPendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = resolveMobileNetworkIntent(context)
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

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

fun resolveInternetPageIntent(context: Context): Intent {
    val pm = context.packageManager

    val candidates = listOf(
        // Direct "Internet" page inside "Network & internet" in Android Settings
        Intent().apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$NetworkProviderSettingsActivity"
            )
        },
        Intent(Settings.ACTION_WIFI_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "internet_settings")
        },
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

fun buildInternetPagePendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = resolveInternetPageIntent(context)
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

// ============================================================================
// WIDGET PROVIDERS (1x1 SMALL VOLUME + 2x2 LARGE 4-TILE GRID)
// ============================================================================

class VolumeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val volIntent = Intent(context, VolumeWidgetProvider::class.java).apply {
            action = ACTION_SET_PRESET_VOLUME
        }
        val volPi = PendingIntent.getBroadcast(
            context,
            101,
            volIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            views.setContentDescription(R.id.tile_small_volume, "Set volume to preset")
            views.setOnClickPendingIntent(R.id.tile_small_volume, volPi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_SET_PRESET_VOLUME) {
            applyPresetVolume(context)
        }
    }
}

class GridWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val volIntent = Intent(context, GridWidgetProvider::class.java).apply {
            action = ACTION_SET_PRESET_VOLUME
        }
        val volPi = PendingIntent.getBroadcast(
            context,
            201,
            volIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dataPi = buildMobileDataPendingIntent(context, 202)
        val batteryPi = buildBatteryProtectionPendingIntent(context, 203)
        val internetPi = buildInternetPagePendingIntent(context, 204)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_large)
            views.setContentDescription(R.id.tile_large_volume, "Set volume to preset")
            views.setContentDescription(R.id.tile_large_data, "Mobile data")
            views.setContentDescription(R.id.tile_large_battery, "Battery protection")
            views.setContentDescription(R.id.tile_large_internet, "Internet")

            views.setOnClickPendingIntent(R.id.tile_large_volume, volPi)
            views.setOnClickPendingIntent(R.id.tile_large_data, dataPi)
            views.setOnClickPendingIntent(R.id.tile_large_battery, batteryPi)
            views.setOnClickPendingIntent(R.id.tile_large_internet, internetPi)

            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_SET_PRESET_VOLUME) {
            applyPresetVolume(context)
        }
    }
}

// ============================================================================
// MAIN ACTIVITY: PRESET CONFIGURATION & 2x2 WIDGET PREVIEW
// ============================================================================

class MainActivity : ComponentActivity() {
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
            val accentTint = Color(0xFFD97757)

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = pageBg
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Text(
                            text = "SoundPad",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = contentTint
                        )

                        // Interactive 2x2 Widget Preview
                        Box(
                            modifier = Modifier
                                .size(180.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(containerBg)
                                .padding(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Top Row: Volume (left), Mobile Signal (right)
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
                                            .clickable { applyPresetVolume(context) }
                                            .padding(6.dp)
                                            .semantics { contentDescription = "Set volume to preset" },
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
                                                runCatching { context.startActivity(resolveMobileNetworkIntent(context)) }
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

                                // Bottom Row: Battery Protection (left), Internet (right)
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
                                                runCatching { context.startActivity(resolveInternetPageIntent(context)) }
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
                                    text = "Preset Volume",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = contentTint
                                )
                                Text(
                                    text = "$targetPercent%",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = accentTint
                                )
                            }

                            Slider(
                                value = targetPercent.toFloat(),
                                onValueChange = {
                                    val next = it.roundToInt().coerceIn(0, 100)
                                    targetPercent = next
                                    prefs.edit().putInt(KEY_TARGET_PERCENT, next).apply()
                                    applyPresetVolume(context)
                                },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
                                    thumbColor = accentTint,
                                    activeTrackColor = accentTint,
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
