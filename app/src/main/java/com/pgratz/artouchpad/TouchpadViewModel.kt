// Copyright 2026 Paul Gratz
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.pgratz.artouchpad

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TouchMode { IDLE, CURSOR, SCROLL }

data class DisplayInfo(val id: Int, val name: String, val width: Int, val height: Int)

data class TouchpadState(
    val isServiceEnabled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuPermission: Boolean = false,
    val mouseReady: Boolean = false,
    val allDisplays: List<DisplayInfo> = emptyList(),
    val targetDisplay: DisplayInfo? = null,
    val cursorX: Float = 0f,
    val cursorY: Float = 0f,
    val sensitivity: Float = 1.5f,
    val scrollSpeed: Float = 1.0f,
    val naturalScroll: Boolean = true,
    val showSettings: Boolean = false,
    val touchMode: TouchMode = TouchMode.IDLE,
) {
    val externalDisplayConnected get() = targetDisplay != null
    val displayWidth get() = targetDisplay?.width ?: 1920
    val displayHeight get() = targetDisplay?.height ?: 1080
}

class TouchpadViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TouchpadState())
    val state: StateFlow<TouchpadState> = _state.asStateFlow()

    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val mouse = ShizukuMouseController()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        mouse.init(::refresh)
        displayManager.registerDisplayListener(displayListener, null)
        refresh()
        mouse.bind()
    }

    fun refresh() {
        val allDisplays = displayManager.displays.map { d ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getMetrics(m)
            DisplayInfo(d.displayId, d.name ?: "Display ${d.displayId}", m.widthPixels, m.heightPixels)
        }

        // Pick the external display: prefer any non-default display,
        // fall back to presentation category.
        val external = allDisplays.firstOrNull { it.id != Display.DEFAULT_DISPLAY }
            ?: run {
                displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                    .firstOrNull()
                    ?.let { d ->
                        val m = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        d.getMetrics(m)
                        DisplayInfo(d.displayId, d.name ?: "Presentation", m.widthPixels, m.heightPixels)
                    }
            }

        if (external != null && mouse.isConnected) {
            mouse.setDisplay(external.id, external.width, external.height)
        }

        _state.update {
            it.copy(
                isServiceEnabled = TouchpadAccessibilityService.instance != null,
                shizukuAvailable = mouse.hasShizuku(),
                shizukuPermission = mouse.hasPermission(),
                mouseReady = mouse.hasPermission() && mouse.isConnected,
                allDisplays = allDisplays,
                targetDisplay = external,
                cursorX = if (external != null) external.width / 2f else it.cursorX,
                cursorY = if (external != null) external.height / 2f else it.cursorY,
            )
        }
    }

    fun requestShizukuPermission() = mouse.requestPermission()

    fun moveCursor(rawDx: Float, rawDy: Float) {
        val sens = _state.value.sensitivity
        val dx = rawDx * sens
        val dy = rawDy * sens
        if (dx * dx + dy * dy < 1f) return
        mouse.moveMouse(dx, dy)

        val w = _state.value.displayWidth.toFloat()
        val h = _state.value.displayHeight.toFloat()
        _state.update {
            it.copy(
                cursorX = (it.cursorX + dx).coerceIn(0f, w - 1f),
                cursorY = (it.cursorY + dy).coerceIn(0f, h - 1f),
                touchMode = TouchMode.CURSOR,
            )
        }
    }

    fun setTouchMode(mode: TouchMode) = _state.update { it.copy(touchMode = mode) }

    fun performClick() = mouse.click(_state.value.cursorX, _state.value.cursorY)
    fun performDoubleClick() = mouse.doubleClick(_state.value.cursorX, _state.value.cursorY)
    fun performRightClick() = mouse.rightClick(_state.value.cursorX, _state.value.cursorY)

    fun performScroll(dx: Float, dy: Float) {
        val speed = _state.value.scrollSpeed
        val dir = if (_state.value.naturalScroll) 1f else -1f
        mouse.scroll(dx * speed * dir, dy * speed * dir)
        _state.update { it.copy(touchMode = TouchMode.SCROLL) }
    }

    fun performGlobalAction(action: Int) =
        TouchpadAccessibilityService.instance?.performGlobalAction(action)

    fun setSensitivity(v: Float) = _state.update { it.copy(sensitivity = v) }
    fun setScrollSpeed(v: Float) = _state.update { it.copy(scrollSpeed = v) }
    fun setNaturalScroll(v: Boolean) = _state.update { it.copy(naturalScroll = v) }
    fun toggleSettings() = _state.update { it.copy(showSettings = !it.showSettings) }

    override fun onCleared() {
        displayManager.unregisterDisplayListener(displayListener)
        mouse.destroy()
    }
}
