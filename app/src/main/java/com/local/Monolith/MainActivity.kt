package com.local.monolith

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient as AndroidLinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RadialGradient as AndroidRadialGradient
import android.graphics.RectF as AndroidRectF
import android.graphics.Shader as AndroidShader
import android.graphics.Typeface as AndroidTypeface
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

const val PREFS_NAME = "soundpad_hardware_prefs"
const val KEY_TARGET_PERCENT = "target_percent"
const val KEY_IS_ENGAGED = "is_engaged"
const val KEY_PREV_STREAM_VOL = "prev_stream_vol"

const val ACTION_TOGGLE_VOLUME = "com.local.monolith.ACTION_TOGGLE_VOLUME"
const val ACTION_REFRESH_STATUS = "com.local.monolith.ACTION_REFRESH_STATUS"

// ============================================================================
// CORE AUDIO & CONNECTIVITY STATE ENGINE
// ============================================================================

fun toggleVolumeAttenuator(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    val targetPercent = prefs.getInt(KEY_TARGET_PERCENT, 30)
    val targetIndex = ((targetPercent / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
    val currentlyEngaged = prefs.getBoolean(KEY_IS_ENGAGED, false)

    val nextEngaged = if (!currentlyEngaged) {
        // Save current volume before clamping so the second tap restores it
        prefs.edit()
            .putInt(KEY_PREV_STREAM_VOL, currentVol)
            .putBoolean(KEY_IS_ENGAGED, true)
            .apply()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        true
    } else {
        // Disengage and restore previous volume
        val defaultRestore = (maxVol * 0.75f).roundToInt().coerceIn(1, maxVol)
        val restoreVol = prefs.getInt(KEY_PREV_STREAM_VOL, defaultRestore).coerceIn(0, maxVol)
        prefs.edit().putBoolean(KEY_IS_ENGAGED, false).apply()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0)
        false
    }

    updateAllHardwareWidgets(context)
    return nextEngaged
}

fun applyTargetVolumeIfEngaged(context: Context, percent: Int) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_IS_ENGAGED, false)) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetIndex = ((percent / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
    }
    updateAllHardwareWidgets(context)
}

fun isWifiConnected(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (_: Exception) {
        false
    }
}

fun isCellularConnected(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    } catch (_: Exception) {
        false
    }
}

fun buildInternetPanelPendingIntent(context: Context, requestCode: Int): PendingIntent {
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

fun buildWifiPanelPendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_WIFI).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS).apply {
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

fun updateAllHardwareWidgets(context: Context) {
    val mgr = AppWidgetManager.getInstance(context)

    val volIds = mgr.getAppWidgetIds(ComponentName(context, VolumeWidgetProvider::class.java))
    if (volIds.isNotEmpty()) VolumeWidgetProvider().onUpdate(context, mgr, volIds)

    val consoleIds = mgr.getAppWidgetIds(ComponentName(context, ConsoleWidgetProvider::class.java))
    if (consoleIds.isNotEmpty()) ConsoleWidgetProvider().onUpdate(context, mgr, consoleIds)

    val dataIds = mgr.getAppWidgetIds(ComponentName(context, DataWidgetProvider::class.java))
    if (dataIds.isNotEmpty()) DataWidgetProvider().onUpdate(context, mgr, dataIds)

    val wifiIds = mgr.getAppWidgetIds(ComponentName(context, WifiWidgetProvider::class.java))
    if (wifiIds.isNotEmpty()) WifiWidgetProvider().onUpdate(context, mgr, wifiIds)
}

// ============================================================================
// HIGH-RESOLUTION HARDWARE BITMAP RENDERER (ANODIZED CHASSIS + GAUGE + LEDS)
// ============================================================================

object HardwareBitmapRenderer {

    private const val COLOR_CHASSIS_TOP = 0xFF1A1B1F.toInt()
    private const val COLOR_CHASSIS_BOT = 0xFF111215.toInt()
    private const val COLOR_WELL_TOP = 0xFF090A0C.toInt()
    private const val COLOR_WELL_BOT = 0xFF0E0F12.toInt()
    private const val COLOR_AMBER = 0xFFFF9F1C.toInt()
    private const val COLOR_BONE = 0xFFF4F4F6.toInt()
    private const val COLOR_DIM_TEXT = 0xFF686970.toInt()
    private const val COLOR_DORMANT_TICK = 0xFF232429.toInt()
    private const val COLOR_INACTIVE_ACTIVE_TICK = 0xFF494B54.toInt()
    private const val COLOR_LED_OFF = 0xFF26272B.toInt()

    fun renderVolumeTile1x1(percent: Int, isEngaged: Boolean): Bitmap {
        val size = 440
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)

        drawAnodizedChassis(canvas, size.toFloat(), size.toFloat(), 96f)
        drawRecessedWell(canvas, AndroidRectF(24f, 24f, size - 24f, size - 24f), 76f)

        // Top-right physical status LED
        drawStatusLed(canvas, cx = size - 68f, cy = 68f, isLit = isEngaged)

        // 270-degree segmented rotary gauge + bold tabular percentage (no text labels)
        drawSegmentedRotaryGauge(
            canvas = canvas,
            cx = size / 2f,
            cy = size / 2f + 6f,
            radius = 128f,
            percent = percent,
            isEngaged = isEngaged,
            textSizePx = 76f
        )

        return bmp
    }

    fun renderSingleIconTile1x1(isCellular: Boolean, isLit: Boolean): Bitmap {
        val size = 440
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)

        drawAnodizedChassis(canvas, size.toFloat(), size.toFloat(), 96f)
        drawRecessedWell(canvas, AndroidRectF(24f, 24f, size - 24f, size - 24f), 76f)
        drawStatusLed(canvas, cx = size - 68f, cy = 68f, isLit = isLit)

        if (isCellular) {
            drawCellularBarsGlyph(canvas, cx = size / 2f, cy = size / 2f + 6f, scale = 1.35f, isLit = isLit)
        } else {
            drawWifiArcsGlyph(canvas, cx = size / 2f, cy = size / 2f + 10f, scale = 1.35f, isLit = isLit)
        }

        return bmp
    }

    fun renderConsoleStrip3x1(
        percent: Int,
        isVolEngaged: Boolean,
        isCellActive: Boolean,
        isWifiActive: Boolean
    ): Bitmap {
        val width = 960
        val height = 320
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)

        drawAnodizedChassis(canvas, width.toFloat(), height.toFloat(), 76f)

        val padOuter = 20f
        val gap = 14f
        val totalWellsWidth = width - (padOuter * 2f) - (gap * 2f)
        val wellWidth = totalWellsWidth / 3f
        val wellTop = 20f
        val wellBot = height - 20f

        for (i in 0..2) {
            val left = padOuter + i * (wellWidth + gap)
            val right = left + wellWidth
            val rect = AndroidRectF(left, wellTop, right, wellBot)
            drawRecessedWell(canvas, rect, 58f)

            val cx = rect.centerX()
            val cy = rect.centerY()
            val ledX = rect.right - 38f
            val ledY = rect.top + 38f

            when (i) {
                0 -> {
                    drawStatusLed(canvas, ledX, ledY, isVolEngaged)
                    drawSegmentedRotaryGauge(
                        canvas = canvas,
                        cx = cx,
                        cy = cy + 4f,
                        radius = 86f,
                        percent = percent,
                        isEngaged = isVolEngaged,
                        textSizePx = 50f
                    )
                }
                1 -> {
                    drawStatusLed(canvas, ledX, ledY, isCellActive)
                    drawCellularBarsGlyph(canvas, cx = cx, cy = cy + 4f, scale = 1.0f, isLit = isCellActive)
                }
                2 -> {
                    drawStatusLed(canvas, ledX, ledY, isWifiActive)
                    drawWifiArcsGlyph(canvas, cx = cx, cy = cy + 8f, scale = 1.0f, isLit = isWifiActive)
                }
            }
        }

        return bmp
    }

    private fun drawAnodizedChassis(canvas: AndroidCanvas, w: Float, h: Float, cornerRadius: Float) {
        val rect = AndroidRectF(2f, 2f, w - 2f, h - 2f)

        // Anodized dark slate body
        val bodyPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.FILL
            shader = AndroidLinearGradient(
                0f, 0f, 0f, h,
                COLOR_CHASSIS_TOP, COLOR_CHASSIS_BOT,
                AndroidShader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bodyPaint)

        // Top-edge specular catchlight border (22% white at top fading to 4% at bottom)
        val borderPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = 3f
            shader = AndroidLinearGradient(
                0f, 0f, 0f, h,
                AndroidColor.argb(58, 255, 255, 255),
                AndroidColor.argb(10, 255, 255, 255),
                AndroidShader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
    }

    private fun drawRecessedWell(canvas: AndroidCanvas, rect: AndroidRectF, cornerRadius: Float) {
        val wellPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.FILL
            shader = AndroidLinearGradient(
                0f, rect.top, 0f, rect.bottom,
                COLOR_WELL_TOP, COLOR_WELL_BOT,
                AndroidShader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, wellPaint)

        // Subtle inner rim highlight at bottom of recessed well
        val rimPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = 2f
            shader = AndroidLinearGradient(
                0f, rect.top, 0f, rect.bottom,
                AndroidColor.argb(180, 0, 0, 0),
                AndroidColor.argb(26, 255, 255, 255),
                AndroidShader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, rimPaint)
    }

    private fun drawStatusLed(canvas: AndroidCanvas, cx: Float, cy: Float, isLit: Boolean) {
        if (isLit) {
            // Soft radial amber glow
            val glowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                shader = AndroidRadialGradient(
                    cx, cy, 22f,
                    intArrayOf(
                        AndroidColor.argb(120, 255, 159, 28),
                        AndroidColor.argb(35, 255, 159, 28),
                        AndroidColor.argb(0, 255, 159, 28)
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    AndroidShader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, 22f, glowPaint)

            // Lit core dot
            val corePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = COLOR_AMBER
            }
            canvas.drawCircle(cx, cy, 6.5f, corePaint)

            // Specular pinpoint inside LED
            val highlightPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = AndroidColor.argb(200, 255, 238, 200)
            }
            canvas.drawCircle(cx - 1.5f, cy - 1.5f, 2f, highlightPaint)
        } else {
            // Dormant gunmetal socket
            val offPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = COLOR_LED_OFF
            }
            canvas.drawCircle(cx, cy, 6f, offPaint)
        }
    }

    private fun drawSegmentedRotaryGauge(
        canvas: AndroidCanvas,
        cx: Float,
        cy: Float,
        radius: Float,
        percent: Int,
        isEngaged: Boolean,
        textSizePx: Float
    ) {
        val totalTicks = 28
        val startAngleDeg = 135f
        val sweepAngleDeg = 270f
        val activeTicks = ((percent.coerceIn(0, 100) / 100f) * totalTicks).roundToInt()

        val tickLength = radius * 0.16f
        val innerRadius = radius - tickLength

        val tickPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = (radius * 0.048f).coerceAtLeast(4f)
            strokeCap = AndroidPaint.Cap.ROUND
        }

        for (i in 0 until totalTicks) {
            val fraction = i.toFloat() / (totalTicks - 1).toFloat()
            val angleDeg = startAngleDeg + fraction * sweepAngleDeg
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val x1 = cx + (innerRadius * cos(angleRad)).toFloat()
            val y1 = cy + (innerRadius * sin(angleRad)).toFloat()
            val x2 = cx + (radius * cos(angleRad)).toFloat()
            val y2 = cy + (radius * sin(angleRad)).toFloat()

            tickPaint.color = when {
                i < activeTicks && isEngaged -> COLOR_AMBER
                i < activeTicks && !isEngaged -> COLOR_INACTIVE_ACTIVE_TICK
                else -> COLOR_DORMANT_TICK
            }
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // Bold tabular percentage numeral only (zero label clutter)
        val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = if (isEngaged) COLOR_BONE else COLOR_DIM_TEXT
            textSize = textSizePx
            textAlign = AndroidPaint.Align.CENTER
            typeface = AndroidTypeface.create(AndroidTypeface.MONOSPACE, AndroidTypeface.BOLD)
            letterSpacing = -0.04f
        }

        val fontMetrics = textPaint.fontMetrics
        val textBaseline = cy - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText("$percent%", cx, textBaseline, textPaint)
    }

    private fun drawCellularBarsGlyph(canvas: AndroidCanvas, cx: Float, cy: Float, scale: Float, isLit: Boolean) {
        val barWidth = 11f * scale
        val gap = 8f * scale
        val totalW = (4 * barWidth) + (3 * gap)
        val startX = cx - totalW / 2f
        val maxH = 66f * scale
        val baseLineY = cy + maxH / 2f

        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.FILL
            color = if (isLit) COLOR_BONE else COLOR_DIM_TEXT
        }

        val heights = floatArrayOf(0.32f, 0.54f, 0.77f, 1.0f)
        for (i in 0..3) {
            val h = maxH * heights[i]
            val left = startX + i * (barWidth + gap)
            val rect = AndroidRectF(left, baseLineY - h, left + barWidth, baseLineY)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    private fun drawWifiArcsGlyph(canvas: AndroidCanvas, cx: Float, cy: Float, scale: Float, isLit: Boolean) {
        val color = if (isLit) COLOR_BONE else COLOR_DIM_TEXT
        val dotY = cy + 24f * scale

        val strokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = 9.5f * scale
            strokeCap = AndroidPaint.Cap.ROUND
            this.color = color
        }

        val radii = floatArrayOf(24f * scale, 44f * scale, 64f * scale)
        for (r in radii) {
            val oval = AndroidRectF(cx - r, dotY - r, cx + r, dotY + r)
            canvas.drawArc(oval, 225f, 90f, false, strokePaint)
        }

        val dotPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.FILL
            this.color = color
        }
        canvas.drawCircle(cx, dotY, 6.5f * scale, dotPaint)
    }
}

// ============================================================================
// WIDGET PROVIDERS (1x1 ATTENUATOR, 3x1 CONSOLE STRIP, 1x1 CELL, 1x1 WI-FI)
// ============================================================================

class VolumeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val percent = prefs.getInt(KEY_TARGET_PERCENT, 30)
        val isEngaged = prefs.getBoolean(KEY_IS_ENGAGED, false)
        val bmp = HardwareBitmapRenderer.renderVolumeTile1x1(percent, isEngaged)

        val toggleIntent = Intent(context, VolumeWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_VOLUME
        }
        val pi = PendingIntent.getBroadcast(
            context,
            1001,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_single_canvas)
            views.setImageViewBitmap(R.id.widget_single_img, bmp)
            views.setOnClickPendingIntent(R.id.widget_single_root, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_VOLUME) {
            toggleVolumeAttenuator(context)
        }
    }
}

class ConsoleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val percent = prefs.getInt(KEY_TARGET_PERCENT, 30)
        val isVolEngaged = prefs.getBoolean(KEY_IS_ENGAGED, false)
        val isCell = isCellularConnected(context)
        val isWifi = isWifiConnected(context)

        val bmp = HardwareBitmapRenderer.renderConsoleStrip3x1(
            percent = percent,
            isVolEngaged = isVolEngaged,
            isCellActive = isCell,
            isWifiActive = isWifi
        )

        val toggleVolIntent = Intent(context, ConsoleWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_VOLUME
        }
        val volPi = PendingIntent.getBroadcast(
            context,
            2001,
            toggleVolIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cellPi = buildInternetPanelPendingIntent(context, 2002)
        val wifiPi = buildWifiPanelPendingIntent(context, 2003)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_console_canvas)
            views.setImageViewBitmap(R.id.widget_console_img, bmp)
            views.setOnClickPendingIntent(R.id.hit_zone_volume, volPi)
            views.setOnClickPendingIntent(R.id.hit_zone_cell, cellPi)
            views.setOnClickPendingIntent(R.id.hit_zone_wifi, wifiPi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_VOLUME -> toggleVolumeAttenuator(context)
            ACTION_REFRESH_STATUS -> updateAllHardwareWidgets(context)
        }
    }
}

class DataWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val isCell = isCellularConnected(context)
        val bmp = HardwareBitmapRenderer.renderSingleIconTile1x1(isCellular = true, isLit = isCell)
        val cellPi = buildInternetPanelPendingIntent(context, 3001)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_single_canvas)
            views.setImageViewBitmap(R.id.widget_single_img, bmp)
            views.setOnClickPendingIntent(R.id.widget_single_root, cellPi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class WifiWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val isWifi = isWifiConnected(context)
        val bmp = HardwareBitmapRenderer.renderSingleIconTile1x1(isCellular = false, isLit = isWifi)
        val wifiPi = buildWifiPanelPendingIntent(context, 4001)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_single_canvas)
            views.setImageViewBitmap(R.id.widget_single_img, bmp)
            views.setOnClickPendingIntent(R.id.widget_single_root, wifiPi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

// ============================================================================
// MAIN ACTIVITY: HARDWARE CALIBRATION CONSOLE WITH LIVE 1:1 BITMAP PREVIEWS
// ============================================================================

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        updateAllHardwareWidgets(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

            var targetPercent by remember { mutableIntStateOf(prefs.getInt(KEY_TARGET_PERCENT, 30)) }
            var isEngaged by remember { mutableStateOf(prefs.getBoolean(KEY_IS_ENGAGED, false)) }
            var cellConnected by remember { mutableStateOf(isCellularConnected(context)) }
            var wifiConnected by remember { mutableStateOf(isWifiConnected(context)) }

            // Re-render exact widget bitmaps in real time as the user drags the attenuator
            val previewConsoleBitmap = remember(targetPercent, isEngaged, cellConnected, wifiConnected) {
                HardwareBitmapRenderer.renderConsoleStrip3x1(
                    percent = targetPercent,
                    isVolEngaged = isEngaged,
                    isCellActive = cellConnected,
                    isWifiActive = wifiConnected
                ).asImageBitmap()
            }

            val previewSingleVolBitmap = remember(targetPercent, isEngaged) {
                HardwareBitmapRenderer.renderVolumeTile1x1(targetPercent, isEngaged).asImageBitmap()
            }

            val previewSingleCellBitmap = remember(cellConnected) {
                HardwareBitmapRenderer.renderSingleIconTile1x1(isCellular = true, isLit = cellConnected).asImageBitmap()
            }

            val previewSingleWifiBitmap = remember(wifiConnected) {
                HardwareBitmapRenderer.renderSingleIconTile1x1(isCellular = false, isLit = wifiConnected).asImageBitmap()
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF09090B),
                    surface = Color(0xFF131417),
                    primary = Color(0xFFFF9F1C),
                    onBackground = Color(0xFFF4F4F6),
                    onSurface = Color(0xFFF4F4F6)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 44.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SOUNDPAD // 01",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                letterSpacing = 1.5.sp,
                                color = Color(0xFFF4F4F6)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isEngaged) Color(0x26FF9F1C) else Color(0xFF18191D))
                                    .border(
                                        width = 1.dp,
                                        color = if (isEngaged) Color(0xFFFF9F1C) else Color(0xFF282930),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        isEngaged = toggleVolumeAttenuator(context)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isEngaged) "ENGAGED • $targetPercent%" else "STANDBY",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = if (isEngaged) Color(0xFFFF9F1C) else Color(0xFF787982)
                                )
                            }
                        }

                        // Live Interactive 1:1 Widget Hardware Preview Surface
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF0E0F12))
                                .border(1.dp, Color(0xFF22232A), RoundedCornerShape(24.dp))
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "LIVE HARDWARE WIDGET PREVIEW (TAP TO TEST)",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp,
                                color = Color(0xFF70727A)
                            )

                            // 3x1 Console Strip Preview
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(3f)
                            ) {
                                Image(
                                    bitmap = previewConsoleBitmap,
                                    contentDescription = "3x1 Console Strip Preview",
                                    modifier = Modifier.fillMaxSize()
                                )
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable {
                                                isEngaged = toggleVolumeAttenuator(context)
                                            }
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable {
                                                runCatching { buildInternetPanelPendingIntent(context, 901).send() }
                                                cellConnected = isCellularConnected(context)
                                            }
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable {
                                                runCatching { buildWifiPanelPendingIntent(context, 902).send() }
                                                wifiConnected = isWifiConnected(context)
                                            }
                                    )
                                }
                            }

                            // Individual 1x1 Tiles Preview Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Image(
                                    bitmap = previewSingleVolBitmap,
                                    contentDescription = "1x1 Volume Tile",
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clickable {
                                            isEngaged = toggleVolumeAttenuator(context)
                                        }
                                )
                                Image(
                                    bitmap = previewSingleCellBitmap,
                                    contentDescription = "1x1 Cellular Tile",
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clickable {
                                            runCatching { buildInternetPanelPendingIntent(context, 903).send() }
                                            cellConnected = isCellularConnected(context)
                                        }
                                )
                                Image(
                                    bitmap = previewSingleWifiBitmap,
                                    contentDescription = "1x1 Wi-Fi Tile",
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clickable {
                                            runCatching { buildWifiPanelPendingIntent(context, 904).send() }
                                            wifiConnected = isWifiConnected(context)
                                        }
                                )
                            }
                        }

                        // Tactile Hardware Attenuator Slider Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF131418))
                                .border(1.dp, Color(0xFF25262E), RoundedCornerShape(24.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text(
                                    text = "ATTENUATOR LEVEL",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    letterSpacing = 1.2.sp,
                                    color = Color(0xFF8A8C96)
                                )
                                Text(
                                    text = "$targetPercent%",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 32.sp,
                                    color = Color(0xFFF4F4F6)
                                )
                            }

                            // Custom Recessed Hardware Track Slider
                            TactileHardwareSlider(
                                percent = targetPercent,
                                onPercentChange = { updated ->
                                    targetPercent = updated
                                    prefs.edit().putInt(KEY_TARGET_PERCENT, updated).apply()
                                    applyTargetVolumeIfEngaged(context, updated)
                                }
                            )

                            // Fine-Tune Step Buttons (-5%, -1%, +1%, +5%)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(-5, -1, +1, +5).forEach { delta ->
                                    val label = if (delta > 0) "+$delta%" else "$delta%"
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF0B0C0E))
                                            .border(1.dp, Color(0xFF23242B), RoundedCornerShape(12.dp))
                                            .clickable {
                                                val next = (targetPercent + delta).coerceIn(0, 100)
                                                targetPercent = next
                                                prefs.edit().putInt(KEY_TARGET_PERCENT, next).apply()
                                                applyTargetVolumeIfEngaged(context, next)
                                            }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color(0xFFD4D5DC)
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
fun TactileHardwareSlider(
    percent: Int,
    onPercentChange: (Int) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF090A0C))
            .border(1.dp, Color(0xFF22232A), RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val next = ((offset.x / size.width) * 100f).roundToInt().coerceIn(0, 100)
                    onPercentChange(next)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val next = ((change.position.x / size.width) * 100f).roundToInt().coerceIn(0, 100)
                    onPercentChange(next)
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val totalBars = 40
        val activeBars = ((percent / 100f) * totalBars).roundToInt()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until totalBars) {
                val isLit = i < activeBars
                val isMajor = i % 5 == 0
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(if (isMajor) 28.dp else 18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (isLit) Color(0xFFFF9F1C)
                            else Color(0xFF232429)
                        )
                )
            }
        }
    }
}
