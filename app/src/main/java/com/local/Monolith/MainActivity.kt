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
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF as AndroidRectF
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
// CORE VOLUME, MOBILE DATA & BATTERY PROTECTION ACTIONS
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

// ============================================================================
// 3X HIGH-RESOLUTION VOLUME RING RENDERER & TYPOGRAPHY HELPERS
// ============================================================================

fun getVolumeIconRes(percent: Int): Int {
    return when {
        percent <= 0 -> R.drawable.ic_volume_off
        percent < 50 -> R.drawable.ic_volume_down
        else -> R.drawable.ic_volume_up
    }
}

fun formatPercentageText(percent: Int): CharSequence {
    val text = "${percent.coerceIn(0, 100)}%"
    val spannable = SpannableString(text)
    // Number is 12sp on the TextView; "%" sign is 9sp (0.75x of 12sp)
    spannable.setSpan(
        RelativeSizeSpan(0.75f),
        text.length - 1,
        text.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    return spannable
}

object VolumeRingRenderer {
    /**
     * Renders the circular ring at 3x resolution (288x288 px) with anti-aliasing
     * and a transparent background so edges stay crisp while staying well below
     * Android's 1MB RemoteViews Binder transaction limit.
     */
    fun renderRingBitmap3x(context: Context, percent: Int): Bitmap {
        val sizePx = 288
        val strokeWidthPx = sizePx * (3.5f / 68f)

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)

        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightMask == Configuration.UI_MODE_NIGHT_YES

        val trackColor = if (isDark) 0xFF4A4844.toInt() else 0xFFD6D3CC.toInt()
        val progressColor = 0xFFD97757.toInt()

        val ringDiameter = sizePx * 0.75f
        val radius = ringDiameter / 2f
        val cx = sizePx / 2f
        val cy = sizePx / 2f

        val trackPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeCap = AndroidPaint.Cap.ROUND
            color = trackColor
        }
        canvas.drawCircle(cx, cy, radius, trackPaint)

        val clamped = percent.coerceIn(0, 100)
        if (clamped > 0) {
            val progressPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = strokeWidthPx
                strokeCap = AndroidPaint.Cap.ROUND
                color = progressColor
            }
            val oval = AndroidRectF(cx - radius, cy - radius, cx + radius, cy + radius)
            val sweepAngle = (clamped / 100f) * 360f
            canvas.drawArc(oval, -90f, sweepAngle, false, progressPaint)
        }

        return bmp
    }
}

fun updateAllWidgets(context: Context) {
    val mgr = AppWidgetManager.getInstance(context)

    val wideIds = mgr.getAppWidgetIds(ComponentName(context, ConsoleWidgetProvider::class.java))
    if (wideIds.isNotEmpty()) ConsoleWidgetProvider().onUpdate(context, mgr, wideIds)

    val volIds = mgr.getAppWidgetIds(ComponentName(context, VolumeWidgetProvider::class.java))
    if (volIds.isNotEmpty()) VolumeWidgetProvider().onUpdate(context, mgr, volIds)

    val dataIds = mgr.getAppWidgetIds(ComponentName(context, DataWidgetProvider::class.java))
    if (dataIds.isNotEmpty()) DataWidgetProvider().onUpdate(context, mgr, dataIds)

    val battIds = mgr.getAppWidgetIds(ComponentName(context, BatteryWidgetProvider::class.java))
    if (battIds.isNotEmpty()) BatteryWidgetProvider().onUpdate(context, mgr, battIds)
}

// ============================================================================
// WIDGET PROVIDERS (3x1 WIDE + 1x1 SMALL VARIANTS)
// ============================================================================

class ConsoleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val targetPercent = prefs.getInt(KEY_TARGET_PERCENT, 30)
        val displayPercent = prefs.getInt(KEY_DISPLAY_PERCENT, targetPercent)

        val ringBmp = VolumeRingRenderer.renderRingBitmap3x(context, displayPercent)
        val iconRes = getVolumeIconRes(displayPercent)
        val formattedPct = formatPercentageText(displayPercent)

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

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_wide)
            views.setImageViewBitmap(R.id.img_wide_ring, ringBmp)
            views.setImageViewResource(R.id.img_wide_vol_icon, iconRes)
            views.setTextViewText(R.id.txt_wide_vol_pct, formattedPct)
            views.setContentDescription(R.id.tile_wide_volume, "Volume $displayPercent percent")
            views.setContentDescription(R.id.tile_wide_data, "Mobile data")
            views.setContentDescription(R.id.tile_wide_battery, "Battery protection")

            views.setOnClickPendingIntent(R.id.tile_wide_volume, volPi)
            views.setOnClickPendingIntent(R.id.tile_wide_data, dataPi)
            views.setOnClickPendingIntent(R.id.tile_wide_battery, batteryPi)

            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val targetPercent = prefs.getInt(KEY_TARGET_PERCENT, 30)
        val displayPercent = prefs.getInt(KEY_DISPLAY_PERCENT, targetPercent)

        val ringBmp = VolumeRingRenderer.renderRingBitmap3x(context, displayPercent)
        val iconRes = getVolumeIconRes(displayPercent)
        val formattedPct = formatPercentageText(displayPercent)

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
            views.setViewVisibility(R.id.group_small_volume, View.VISIBLE)
            views.setViewVisibility(R.id.img_small_center_icon, View.GONE)

            views.setImageViewBitmap(R.id.img_small_ring, ringBmp)
            views.setImageViewResource(R.id.img_small_vol_icon, iconRes)
            views.setTextViewText(R.id.txt_small_vol_pct, formattedPct)
            views.setContentDescription(R.id.tile_small_root, "Volume $displayPercent percent")
            views.setOnClickPendingIntent(R.id.tile_small_root, volPi)

            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
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
            views.setViewVisibility(R.id.group_small_volume, View.GONE)
            views.setViewVisibility(R.id.img_small_center_icon, View.VISIBLE)

            views.setImageViewResource(R.id.img_small_center_icon, R.drawable.ic_signal_cellular_alt)
            views.setContentDescription(R.id.tile_small_root, "Mobile data")
            views.setOnClickPendingIntent(R.id.tile_small_root, dataPi)

            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
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
            views.setViewVisibility(R.id.group_small_volume, View.GONE)
            views.setViewVisibility(R.id.img_small_center_icon, View.VISIBLE)

            views.setImageViewResource(R.id.img_small_center_icon, R.drawable.ic_shield)
            views.setContentDescription(R.id.tile_small_root, "Battery protection")
            views.setOnClickPendingIntent(R.id.tile_small_root, batteryPi)

            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            updateAllWidgets(context)
        }
    }
}

// ============================================================================
// MAIN ACTIVITY: COMPANION CALIBRATION SCREEN MATCHING THE WIDGET PALETTE
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
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
             
