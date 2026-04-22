package com.pgratz.artouchpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class TouchpadAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TouchpadAccessibilityService? = null
            private set

        var cursorX = 540f
        var cursorY = 960f
        var externalDisplayWidth = 1920
        var externalDisplayHeight = 1080
        var externalDisplayId = Display.DEFAULT_DISPLAY

        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 650L
        private const val DOUBLE_GAP_MS = 100L
        private const val SCROLL_MS = 300L
        private const val SCROLL_SCALE = 200f
        private const val CLICK_VIBRATION_MS = 15L
        private const val LONG_VIBRATION_MS = 40L
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var vibrator: Vibrator

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = detectExternalDisplay()
        override fun onDisplayRemoved(displayId: Int) = detectExternalDisplay()
        override fun onDisplayChanged(displayId: Int) = detectExternalDisplay()
    }

    override fun onServiceConnected() {
        instance = this
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        displayManager.registerDisplayListener(displayListener, null)
        detectExternalDisplay()
    }

    private fun detectExternalDisplay() {
        val external = displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (external != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            external.getMetrics(metrics)
            externalDisplayWidth = metrics.widthPixels
            externalDisplayHeight = metrics.heightPixels
            externalDisplayId = external.displayId
        } else {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            externalDisplayWidth = bounds.width()
            externalDisplayHeight = bounds.height()
            externalDisplayId = Display.DEFAULT_DISPLAY
        }
        cursorX = externalDisplayWidth / 2f
        cursorY = externalDisplayHeight / 2f
    }

    fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, (externalDisplayWidth - 1).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (externalDisplayHeight - 1).toFloat())
    }

    fun performClick() {
        dispatchTap(cursorX, cursorY, TAP_MS)
        vibrate(CLICK_VIBRATION_MS)
    }

    fun performDoubleClick() {
        val x = cursorX
        val y = cursorY
        val s1 = stroke(x, y, x, y, 0, TAP_MS)
        val s2 = stroke(x, y, x, y, TAP_MS + DOUBLE_GAP_MS, TAP_MS)
        dispatchGesture(buildGesture(s1, s2), null, null)
        vibrate(CLICK_VIBRATION_MS)
    }

    fun performRightClick() {
        // Long-press at cursor position — most desktop launchers map this to right-click
        val path = Path().apply { moveTo(cursorX, cursorY) }
        val s = GestureDescription.StrokeDescription(path, 0, LONG_PRESS_MS)
        dispatchGesture(buildGesture(s), null, null)
        vibrate(LONG_VIBRATION_MS)
    }

    fun performScroll(dx: Float, dy: Float) {
        val endX = (cursorX - dx * SCROLL_SCALE).coerceIn(0f, externalDisplayWidth.toFloat())
        val endY = (cursorY - dy * SCROLL_SCALE).coerceIn(0f, externalDisplayHeight.toFloat())
        dispatchGesture(buildGesture(stroke(cursorX, cursorY, endX, endY, 0, SCROLL_MS)), null, null)
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(buildGesture(GestureDescription.StrokeDescription(path, 0, durationMs)), null, null)
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, startMs: Long, durationMs: Long) =
        GestureDescription.StrokeDescription(
            Path().apply {
                moveTo(x1, y1)
                if (x1 != x2 || y1 != y2) lineTo(x2, y2)
            },
            startMs,
            durationMs,
        )

    private fun buildGesture(vararg strokes: GestureDescription.StrokeDescription): GestureDescription {
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }

        // Best-effort: try to target the external display via hidden API (Android 12+)
        if (externalDisplayId != Display.DEFAULT_DISPLAY) {
            try {
                val m = GestureDescription.Builder::class.java
                    .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(builder, externalDisplayId)
            } catch (_: Exception) {}
        }

        return builder.build()
    }

    private fun vibrate(ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

}

