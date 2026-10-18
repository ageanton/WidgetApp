package com.local.monolith

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient as AndroidRadialGradient
import android.graphics.Shader as AndroidShader
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
const val KEY_THEME_ID = "widget_theme_id"
const val KEY_LED_COLOR_ID = "widget_led_color_id"
const val KEY_SMALL_SIZE_MODE = "small_size_mode" // 0 = Compact (52dp), 1 = Micro (44dp), 2 = Full

const val ACTION_TOGGLE_VOLUME = "com.local.monolith.ACTION_TOGGLE_VOLUME"

// ============================================================================
// WIDGET COLOR PALETTE & CONTRAST-SAFE LED CONFIGURATION
// ============================================================================

data class WidgetPalette(
    val id: Int,
    val label: String,
    val containerColor: Int,
    val tileColor: Int,
    val iconColor: Int,
    val dormantLedColor: Int,
    val ledCollarColor: Int,
    val containerRes: Int,
    val tileRes: Int,
    val compactContainerRes: Int,
    val compactTileRes: Int
)

data class LedAccentOption(
    val id: Int,
    val label: String,
    val colorInt: Int
)

val LED_ACCENT_OPTIONS = listOf(
    LedAccentOption(0, "Terracotta", 0xFFD97757.toInt()),
    LedAccentOption(1, "Amber", 0xFFFF9F1C.toInt()),
    LedAccentOption(2, "Signal Green", 0xFF2ECC71.toInt()),
    LedAccentOption(3, "Crimson", 0xFFE63946.toInt()),
    LedAccentOption(4, "Ice Cyan", 0xFF38BDF8.toInt())
)

fun isSystemNightMode(context: Context): Boolean {
    val nightBits = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return nightBits == Configuration.UI_MODE_NIGHT_YES
}

fun resolveActivePalette(context: Context, themeId: Int): WidgetPalette {
    val night = isSystemNightMode(context)
    return when (themeId) {
        1 -> WidgetPalette(
            id = 1,
            label = "Warm Stone",
            containerColor = 0xFFF1EFEA.toInt(),
            tileColor = 0xFFE5E3DE.toInt(),
            iconColor = 0xFF2E2D2B.toInt(),
            dormantLedColor = 0xFFC5C2BA.toInt(),
            ledCollarColor = 0xFF2E2D2B.toInt(),
            containerRes = R.drawable.bg_container_warm,
            tileRes = R.drawable.bg_tile_warm,
            compactContainerRes = R.drawable.bg_container_compact_warm,
            compactTileRes = R.drawable.bg_tile_compact_warm
        )
        2 -> WidgetPalette(
            id = 2,
            label = "Obsidian",
            containerColor = 0xFF2B2A28.toInt(),
            tileColor = 0xFF3A3936.toInt(),
            iconColor = 0xFFECEAE5.toInt(),
            dormantLedColor = 0xFF575550.toInt(),
            ledCollarColor = 0xFF161514.toInt(),
            containerRes = R.drawable.bg_container_obsidian,
            tileRes = R.drawable.bg_tile_obsidian,
            compactContainerRes = R.drawable.bg_container_compact_obsidian,
            compactTileRes = R.drawable.bg_tile_compact_obsidian
        )
        3 -> WidgetPalette(
            id = 3,
            label = "AMOLED Pitch",
            containerColor = 0xFF0D0E11.toInt(),
            tileColor = 0xFF181A20.toInt(),
            iconColor = 0xFFF2F4F8.toInt(),
            dormantLedColor = 0xFF323642.toInt(),
            ledCollarColor = 0xFF050608.toInt(),
            containerRes = R.drawable.bg_container_amoled,
            tileRes = R.drawable.bg_tile_amoled,
            compactContainerRes = R.drawable.bg_container_compact_amoled,
            compactTileRes = R.drawable.bg_tile_compact_amoled
        )
        4 -> WidgetPalette(
            id = 4,
            label = "Nordic Slate",
            containerColor = 0xFF222730.toInt(),
            tileColor = 0xFF2E3440.toInt(),
            iconColor = 0xFFECEFF4.toInt(),
            dormantLedColor = 0xFF4C566A.toInt(),
            ledCollarColor = 0xFF14171D.toInt(),
            containerRes = R.drawable.bg_container_slate,
            tileRes = R.drawable.bg_tile_slate,
            compactContainerRes = R.drawable.bg_container_compact_slate,
            compactTileRes = R.drawable.bg_tile_compact_slate
        )
        5 -> WidgetPalette(
            id = 5,
            label = "Sage Moss",
            containerColor = 0xFFE4E8E1.toInt(),
            tileColor = 0xFFD5DBD0.toInt(),
            iconColor = 0xFF232922.toInt(),
            dormantLedColor = 0xFFB4BDB0.toInt(),
            ledCollarColor = 0xFF232922.toInt(),
            containerRes = R.drawable.bg_container_sage,
            tileRes = R.drawable.bg_tile_sage,
            compactContainerRes = R.drawable.bg_container_compact_sage,
            compactTileRes = R.drawable.bg_tile_compact_sage
        )
        else -> {
            // 0 = Auto (Follows System Light/Dark Theme)
            if (night) {
                WidgetPalette(
                    id = 0,
                    label = "Auto",
                    containerColor = 0xFF2B2A28.toInt(),
                    tileColor = 0xFF3A3936.toInt(),
                    iconColor = 0xFFECEAE5.toInt(),
                    dormantLedColor = 0xFF575550.toInt(),
                    ledCollarColor = 0xFF161514.toInt(),
                    containerRes = R.drawable.bg_container_auto,
                    tileRes = R.drawable.bg_tile_auto,
                    compactContainerRes = R.drawable.bg_container_compact_auto,
                    compactTileRes = R.drawable.bg_tile_compact_auto
                )
            } else {
                WidgetPalette(
                    id = 0,
                    label = "Auto",
                    containerColor = 0xFFF1EFEA.toInt(),
                    tileColor = 0xFFE5E3DE.toInt(),
                    iconColor = 0xFF2E2D2B.toInt(),
                    dormantLedColor = 0xFFC5C2BA.toInt(),
                    ledCollarColor = 0xFF2E2D2B.toInt(),
                    containerRes = R.drawable.bg_container_auto,
                    tileRes = R.drawable.bg_tile_auto,
                    compactContainerRes = R.drawable.bg_container_compact_auto,
                    compactTileRes = R.drawable.bg_tile_compact_auto
                )
            }
        }
    }
}

object LedIndicatorRenderer {
    /**
     * Renders a crisp 3x anti-aliased LED indicator dot with a contrast-guard bezel
     * so it remains 100% visible against any light or dark widget theme.
     */
    fun renderLedBitmap(
        isEngaged: Boolean,
        palette: WidgetPalette,
        ledAccentColor: Int
    ): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        if (isEngaged) {
            val r = AndroidColor.red(ledAccentColor)
            val g = AndroidColor.green(ledAccentColor)
            val b = AndroidColor.blue(ledAccentColor)

            // 1. Soft radial outer bloom
            val glowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                shader = AndroidRadialGradient(
                    cx, cy, 34f,
                    intArrayOf(
                        AndroidColor.argb(110, r, g, b),
                        AndroidColor.argb(35, r, g, b),
                        AndroidColor.argb(0, r, g, b)
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    AndroidShader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, 34f, glowPaint)

            // 2. Contrast-guard machined bezel ring (prevents blending into any tile color)
            val collarPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = palette.ledCollarColor
                alpha = 215
            }
            canvas.drawCircle(cx, cy, 17f, collarPaint)

            // 3. Vibrant active LED core
            val corePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = ledAccentColor
            }
            canvas.drawCircle(cx, cy, 13.5f, corePaint)

            // 4. Crisp specular lens catchlight
            val specPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = AndroidColor.argb(180, 255, 255, 255)
            }
            canvas.drawCircle(cx - 4f, cy - 4f, 3.8f, specPaint)
        } else {
            // Dormant recessed socket dot
            val socketPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = palette.dormantLedColor
            }
            canvas.drawCircle(cx, cy, 10.5f, socketPaint)

            val rimPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = 2.5f
                color = palette.ledCollarColor
                alpha = 75
            }
            canvas.drawCircle(cx, cy, 10.5f, rimPaint)
        }

        return bmp
    }
}

// ============================================================================
// CORE VOLUME TOGGLE, MOBILE DATA, BATTERY, INTERNET & DISPLAY ACTIONS
// ============================================================================

fun handleVolumeTileTap(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    val targetPercent = prefs.getInt(KEY_TARGET_PERCENT, 30)
    val targetIndex = ((targetPercent / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
    val isClamped = prefs.getBoolean(KEY_IS_CLAMPED, false)

    val nowClamped = if (!isClamped || currentVol != targetIndex) {
        prefs.edit()
            .putInt(KEY_PREV_STREAM_VOL, currentVol)
            .putBoolean(KEY_IS_CLAMPED, true)
            .putInt(KEY_DISPLAY_PERCENT, targetPercent)
            .apply()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        true
    } else {
        val fallbackVol = (maxVol * 0.75f).roundToInt().coerceIn(1, maxVol)
        val restoreIndex = prefs.getInt(KEY_PREV_STREAM_VOL, fallbackVol).coerceIn(0, maxVol)
        val restoredPercent = ((restoreIndex.toFloat() / maxVol.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        prefs.edit()
            .putBoolean(KEY_IS_CLAMPED, false)
            .putInt(KEY_DISPLAY_PERCENT, restoredPercent)
            .apply()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreIndex, 0)
        false
    }

    updateAllWidgets(context)
    return nowClamped
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

fun buildSmallTileRemoteViews(
    context: Context,
    iconRes: Int,
    contentDesc: String,
    clickPi: PendingIntent,
    showVolumeLed: Boolean
): RemoteViews {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val themeId = prefs.getInt(KEY_THEME_ID, 0)
    val ledId = prefs.getInt(KEY_LED_COLOR_ID, 0)
    val sizeMode = prefs.getInt(KEY_SMALL_SIZE_MODE, 0) // Default 0 = Compact (52dp)
    val isClamped = prefs.getBoolean(KEY_IS_CLAMPED, false)

    val palette = resolveActivePalette(context, themeId)
    val ledAccent = LED_ACCENT_OPTIONS.firstOrNull { it.id == ledId }?.colorInt ?: 0xFFD97757.toInt()

    val layoutRes = when (sizeMode) {
        1 -> R.layout.widget_small_micro
        2 -> R.layout.widget_small_full
        else -> R.layout.widget_small_compact
    }

    val containerBgRes = if (sizeMode == 2) palette.containerRes else palette.compactContainerRes
    val tileBgRes = if (sizeMode == 2) palette.tileRes else palette.compactTileRes

    val views = RemoteViews(context.packageName, layoutRes)
    views.setInt(R.id.small_container_bg, "setBackgroundResource", containerBgRes)
    views.setInt(R.id.tile_small_root, "setBackgroundResource", tileBgRes)
    views.setImageViewResource(R.id.img_small_center_icon, iconRes)
    views.setInt(R.id.img_small_center_icon, "setColorFilter", palette.iconColor)
    views.setContentDescription(R.id.tile_small_root, contentDesc)
    views.setOnClickPendingIntent(R.id.tile_small_root, clickPi)

    if (showVolumeLed) {
        val ledBmp = LedIndicatorRenderer.renderLedBitmap(isClamped, palette, ledAccent)
        views.setViewVisibility(R.id.img_small_vol_led, View.VISIBLE)
        views.setImageViewBitmap(R.id.img_small_vol_led, ledBmp)
    } else {
        views.setViewVisibility(R.id.img_small_vol_led, View.GONE)
    }

    return views
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeId = prefs.getInt(KEY_THEME_ID, 0)
        val ledId = prefs.getInt(KEY_LED_COLOR_ID, 0)
        val isClamped = prefs.getBoolean(KEY_IS_CLAMPED, false)

        val palette = resolveActivePalette(context, themeId)
        val ledAccent = LED_ACCENT_OPTIONS.firstOrNull { it.id == ledId }?.colorInt ?: 0xFFD97757.toInt()
        val ledBmp = LedIndicatorRenderer.renderLedBitmap(isClamped, palette, ledAccent)

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

            // Apply chosen container and tile theme drawables
            views.setInt(R.id.grid_container_bg, "setBackgroundResource", palette.containerRes)
            views.setInt(R.id.tile_grid_volume, "setBackgroundResource", palette.tileRes)
            views.setInt(R.id.tile_grid_data, "setBackgroundResource", palette.tileRes)
            views.setInt(R.id.tile_grid_battery, "setBackgroundResource", palette.tileRes)
            views.setInt(R.id.tile_grid_internet, "setBackgroundResource", palette.tileRes)
            views.setInt(R.id.tile_grid_display, "setBackgroundResource", palette.tileRes)

            // Apply icon tint
            views.setInt(R.id.img_grid_volume, "setColorFilter", palette.iconColor)
            views.setInt(R.id.img_grid_data, "setColorFilter", palette.iconColor)
            views.setInt(R.id.img_grid_battery, "setColorFilter", palette.iconColor)
            views.setInt(R.id.img_grid_internet, "setColorFilter", palette.iconColor)
            views.setInt(R.id.img_grid_display, "setColorFilter", palette.iconColor)

            // Apply contrast-safe Volume status LED
            views.setImageViewBitmap(R.id.img_grid_vol_led, ledBmp)

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
            val views = buildSmallTileRemoteViews(
                context = context,
                iconRes = R.drawable.ic_volume_up,
                contentDesc = "Toggle preset volume",
                clickPi = volPi,
                showVolumeLed = true
            )
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
            val views = buildSmallTileRemoteViews(
                context = context,
                iconRes = R.drawable.ic_signal_cellular_alt,
                contentDesc = "Mobile data",
                clickPi = dataPi,
                showVolumeLed = false
            )
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
            val views = buildSmallTileRemoteViews(
                context = context,
                iconRes = R.drawable.ic_shield,
                contentDesc = "Battery protection",
                clickPi = batteryPi,
                showVolumeLed = false
            )
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
            val views = buildSmallTileRemoteViews(
                context = context,
                iconRes = R.drawable.ic_language,
                contentDesc = "Internet",
                clickPi = internetPi,
                showVolumeLed = false
            )
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
            val views = buildSmallTileRemoteViews(
                context = context,
                iconRes = R.drawable.ic_display,
                contentDesc = "Display settings",
                clickPi = displayPi,
                showVolumeLed = false
            )
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
// MAIN ACTIVITY: THEME PICKER, LED ACCENT PICKER, 1x1 COMPACT SIZE & PREVIEW
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
            var isClamped by remember { mutableStateOf(prefs.getBoolean(KEY_IS_CLAMPED, false)) }
            var selectedThemeId by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_ID, 0)) }
            var selectedLedId by remember { mutableIntStateOf(prefs.getInt(KEY_LED_COLOR_ID, 0)) }
            var selectedSmallSize by remember { mutableIntStateOf(prefs.getInt(KEY_SMALL_SIZE_MODE, 0)) }

            val activePalette = remember(selectedThemeId, isDark) {
                resolveActivePalette(context, selectedThemeId)
            }
            val activeLedColorInt = remember(selectedLedId) {
                LED_ACCENT_OPTIONS.firstOrNull { it.id == selectedLedId }?.colorInt ?: 0xFFD97757.toInt()
            }

            val pageBg = if (isDark) Color(0xFF161615) else Color(0xFFE5E2DC)
            val cardBg = if (isDark) Color(0xFF222120) else Color(0xFFF4F2ED)
            val primaryText = if (isDark) Color(0xFFECEAE5) else Color(0xFF2E2D2B)
            val secondaryText = if (isDark) Color(0xFF9E9B95) else Color(0xFF706E6A)

            val previewContainerColor = Color(activePalette.containerColor)
            val previewTileColor = Color(activePalette.tileColor)
            val previewIconColor = Color(activePalette.iconColor)
            val previewLedColor = Color(activeLedColorInt)

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = pageBg
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 44.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header Row with Live Volume Clamp Status Pill
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SoundPad",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = primaryText
                            )

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(cardBg)
                                    .clickable {
                                        isClamped = handleVolumeTileTap(context)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ContrastSafeLedDot(
                                    isEngaged = isClamped,
                                    activeColor = previewLedColor,
                                    dormantColor = Color(activePalette.dormantLedColor),
                                    collarColor = Color(activePalette.ledCollarColor),
                                    sizeDp = 10
                                )
                                Text(
                                    text = if (isClamped) "Preset Active ($targetPercent%)" else "Normal Volume",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryText
                                )
                            }
                        }

                        // Live Interactive Preview: 2x3 Grid + Compact 1x1 Volume Chip Side-by-Side
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 2x3 Grid Widget Live Preview
                            Box(
                                modifier = Modifier
                                    .width(184.dp)
                                    .height(260.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(previewContainerColor)
                                    .padding(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Row 1: Volume (with LED dot) + Mobile Data
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
                                                .background(previewTileColor)
                                                .clickable {
                                                    isClamped = handleVolumeTileTap(context)
                                                }
                                                .padding(6.dp)
                                                .semantics { contentDescription = "Toggle preset volume" }
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_volume_up),
                                                contentDescription = null,
                                                tint = previewIconColor,
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .align(Alignment.Center)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(top = 2.dp, end = 2.dp)
                                            ) {
                                                ContrastSafeLedDot(
                                                    isEngaged = isClamped,
                                                    activeColor = previewLedColor,
                                                    dormantColor = Color(activePalette.dormantLedColor),
                                                    collarColor = Color(activePalette.ledCollarColor),
                                                    sizeDp = 10
                                                )
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(previewTileColor)
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
                                                tint = previewIconColor,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }

                                    // Row 2: Battery Protection + Internet
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
                                                .background(previewTileColor)
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
                                                tint = previewIconColor,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(previewTileColor)
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
                                                tint = previewIconColor,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }

                                    // Row 3: Display Settings
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(previewTileColor)
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
                                            tint = previewIconColor,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                            }

                            // 1x1 Compact Widget Preview Column
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val chipSize = when (selectedSmallSize) {
                                    1 -> 44.dp
                                    2 -> 68.dp
                                    else -> 52.dp
                                }
                                val chipOuterRadius = if (selectedSmallSize == 2) 24.dp else 18.dp
                                val chipInnerRadius = if (selectedSmallSize == 2) 18.dp else 13.dp
                                val chipIconSize = when (selectedSmallSize) {
                                    1 -> 18.dp
                                    2 -> 24.dp
                                    else -> 21.dp
                                }

                                Box(
                                    modifier = Modifier
                                        .size(72.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(chipSize)
                                            .clip(RoundedCornerShape(chipOuterRadius))
                                            .background(previewContainerColor)
                                            .padding(if (selectedSmallSize == 1) 4.dp else 5.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(chipInnerRadius))
                                                .background(previewTileColor)
                                                .clickable {
                                                    isClamped = handleVolumeTileTap(context)
                                                }
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_volume_up),
                                                contentDescription = null,
                                                tint = previewIconColor,
                                                modifier = Modifier
                                                    .size(chipIconSize)
                                                    .align(Alignment.Center)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(top = 3.dp, end = 3.dp)
                                            ) {
                                                ContrastSafeLedDot(
                                                    isEngaged = isClamped,
                                                    activeColor = previewLedColor,
                                                    dormantColor = Color(activePalette.dormantLedColor),
                                                    collarColor = Color(activePalette.ledCollarColor),
                                                    sizeDp = if (selectedSmallSize == 1) 7 else 8
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = "1×1 Tile",
                                    fontSize = 12.sp,
                                    color = secondaryText
                                )
                            }
                        }

                        // Card 1: Preferred Volume Slider
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(cardBg)
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                    color = primaryText
                                )
                                Text(
                                    text = "$targetPercent%",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = previewLedColor
                                )
                            }

                            Slider(
                                value = targetPercent.toFloat(),
                                onValueChange = {
                                    val next = it.roundToInt().coerceIn(0, 100)
                                    targetPercent = next
                                    isClamped = true
                                    setPreferredVolumeFromApp(context, next)
                                },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
                                    thumbColor = previewLedColor,
                                    activeTrackColor = previewLedColor,
                                    inactiveTrackColor = secondaryText.copy(alpha = 0.25f)
                                )
                            )
                        }

                        // Card 2: Widget Color Theme Picker
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(cardBg)
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Widget Color Theme",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = primaryText
                            )

                            val themeChoices = listOf(
                                Triple(0, "Auto", Color(0xFFF1EFEA)),
                                Triple(1, "Warm Stone", Color(0xFFF1EFEA)),
                                Triple(2, "Obsidian", Color(0xFF2B2A28)),
                                Triple(3, "AMOLED", Color(0xFF0D0E11)),
                                Triple(4, "Slate", Color(0xFF222730)),
                                Triple(5, "Sage Moss", Color(0xFFE4E8E1))
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                themeChoices.chunked(3).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowItems.forEach { (id, name, swatchColor) ->
                                            val isSelected = selectedThemeId == id
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(
                                                        if (isSelected) previewLedColor.copy(alpha = 0.16f)
                                                        else pageBg.copy(alpha = 0.6f)
                                                    )
                                                    .border(
                                                        width = if (isSelected) 1.5.dp else 1.dp,
                                                        color = if (isSelected) previewLedColor else secondaryText.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(14.dp)
                                                    )
                                                    .clickable {
                                                        selectedThemeId = id
                                                        prefs.edit().putInt(KEY_THEME_ID, id).apply()
                                                        updateAllWidgets(context)
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clip(CircleShape)
                                                        .background(swatchColor)
                                                        .border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)
                                                )
                                                Text(
                                                    text = name,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = primaryText,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = secondaryText.copy(alpha = 0.15f))

                            // Indicator Dot Accent Color Picker
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Active Dot Color",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryText
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LED_ACCENT_OPTIONS.forEach { option ->
                                        val isSelected = selectedLedId == option.id
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(Color(option.colorInt))
                                                .border(
                                                    width = if (isSelected) 2.5.dp else 1.dp,
                                                    color = if (isSelected) primaryText else Color.Transparent,
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    selectedLedId = option.id
                                                    prefs.edit().putInt(KEY_LED_COLOR_ID, option.id).apply()
                                                    updateAllWidgets(context)
                                                }
                                        )
                                    }
                                }
                            }
                        }

                        // Card 3: 1x1 Widget Footprint Size Selector
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(cardBg)
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "1×1 Widget Footprint",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = primaryText
                            )

                            val sizeChoices = listOf(
                                0 to "Compact (52dp)",
                                1 to "Micro (44dp)",
                                2 to "Full Cell"
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                sizeChoices.forEach { (mode, label) ->
                                    val isSelected = selectedSmallSize == mode
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (isSelected) previewLedColor.copy(alpha = 0.16f)
                                                else pageBg.copy(alpha = 0.6f)
                                            )
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) previewLedColor else secondaryText.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                            .clickable {
                                                selectedSmallSize = mode
                                                prefs.edit().putInt(KEY_SMALL_SIZE_MODE, mode).apply()
                                                updateAllWidgets(context)
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = primaryText
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContrastSafeLedDot(
    isEngaged: Boolean,
    activeColor: Color,
    dormantColor: Color,
    collarColor: Color,
    sizeDp: Int
) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val r = size.minDimension / 2f
        if (isEngaged) {
            drawCircle(
                color = activeColor.copy(alpha = 0.28f),
                radius = r * 1.35f
            )
            drawCircle(
                color = collarColor.copy(alpha = 0.85f),
                radius = r * 0.78f
            )
            drawCircle(
                color = activeColor,
                radius = r * 0.62f
            )
        } else {
            drawCircle(
                color = dormantColor,
                radius = r * 0.50f
            )
        }
    }
}
