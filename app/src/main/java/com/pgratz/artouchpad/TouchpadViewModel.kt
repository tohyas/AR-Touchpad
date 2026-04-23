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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val sensitivity: Float = 1.2f,
    val scrollSpeed: Float = 0.8f,
    val naturalScroll: Boolean = true,
    val showSettings: Boolean = false,
    val touchMode: TouchMode = TouchMode.IDLE,
    val showKeyboard: Boolean = false,
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

    private var fontScale = app.resources.configuration.fontScale
    private var fontScaleAccum = 0f

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
        TouchpadAccessibilityService.onExternalTextFocus = {
            if (!_state.value.showKeyboard) {
                _state.update { it.copy(showKeyboard = true) }
                // Dismiss the glasses-side IME that Android auto-showed.
                // BACK is consumed by the IME (dismisses it) and never reaches the app,
                // so Chrome's text field stays focused and ready for our injected text.
                viewModelScope.launch {
                    delay(400)
                    mouse.pressKey(android.view.KeyEvent.KEYCODE_BACK)
                }
            }
        }
    }

    // Enumerates all displays via DisplayManager; picks the first non-default display as the
    // target (glasses). Updates state with display list, cursor center, and all status flags.
    // Also calls setDisplay on MouseService so key/cursor events reach the right display.
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

    // Opens the Shizuku permission dialog so the user can grant shell-uid access.
    fun requestShizukuPermission() = mouse.requestPermission()

    // Input: raw pixel deltas from the touch event.
    // Scales by sensitivity, forwards to MouseService, and updates the tracked cursor
    // position in state (used to draw the overlay dot and target clicks correctly).
    fun moveCursor(rawDx: Float, rawDy: Float) {
        val sens = _state.value.sensitivity
        val dx = rawDx * sens
        val dy = rawDy * sens
        if (dx == 0f && dy == 0f) return
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

    // Updates touchMode in state, which drives the cursor/scroll indicator shown in the status bar.
    fun setTouchMode(mode: TouchMode) = _state.update { it.copy(touchMode = mode) }

    // Delegate clicks to MouseService at the current tracked cursor position.
    fun performClick() = mouse.click(_state.value.cursorX, _state.value.cursorY)
    fun performDoubleClick() = mouse.doubleClick(_state.value.cursorX, _state.value.cursorY)
    fun performRightClick() = mouse.rightClick(_state.value.cursorX, _state.value.cursorY)

    // Applies scrollSpeed multiplier and natural-scroll direction inversion, then
    // forwards the adjusted delta to MouseService for wheel-detent conversion.
    fun performScroll(dx: Float, dy: Float) {
        val speed = _state.value.scrollSpeed
        val dir = if (_state.value.naturalScroll) 1f else -1f
        mouse.scroll(dx * speed * dir, dy * speed * dir)
        _state.update { it.copy(touchMode = TouchMode.SCROLL) }
    }

    // Forwards an Android keycode to MouseService for injection on the glasses display.
    fun pressKey(linuxKeyCode: Int) = mouse.pressKey(linuxKeyCode)
    // Converts text to key events and injects them to the focused window on the glasses display.
    fun typeText(text: String) = mouse.typeText(text)

    // Input: dDist — change in pixel distance between two touch points this frame (positive = spread).
    // Accumulates in fontScaleAccum; once 0.05 of scale change has built up, applies it to
    // fontScale and calls setFontScale on the service (clamped to 0.85–1.5).
    fun pinchZoom(dDist: Float) {
        // ~500px of total spread = 1.0 scale change; apply in 0.05 steps to avoid jitter
        fontScaleAccum += dDist / 500f
        if (kotlin.math.abs(fontScaleAccum) >= 0.05f) {
            fontScale = (fontScale + fontScaleAccum).coerceIn(0.85f, 1.5f)
            fontScaleAccum = 0f
            mouse.setFontScale(fontScale)
        }
    }
    // Toggles showKeyboard in state, which shows or hides the KeyboardProxy strip in the UI.
    fun toggleKeyboard() = _state.update { it.copy(showKeyboard = !it.showKeyboard) }

    // Input: text accumulated in the phone keyboard proxy.
    // Dismisses the phone keyboard first (to avoid IME session conflicts), waits 200 ms for
    // the IME to tear down, then injects the text followed by Enter to the glasses display.
    fun sendKeyboardText(text: String) {
        if (text.isEmpty()) { toggleKeyboard(); return }
        toggleKeyboard()
        viewModelScope.launch {
            delay(200)
            mouse.typeText(text)
            mouse.pressKey(android.view.KeyEvent.KEYCODE_ENTER)
        }
    }

    // Delegates an AccessibilityService global action (e.g. GLOBAL_ACTION_BACK) to the service instance.
    fun performGlobalAction(action: Int) =
        TouchpadAccessibilityService.instance?.performGlobalAction(action)

    // Settings state updaters — each writes one field into TouchpadState.
    fun setSensitivity(v: Float) = _state.update { it.copy(sensitivity = v) }
    fun setScrollSpeed(v: Float) = _state.update { it.copy(scrollSpeed = v) }
    fun setNaturalScroll(v: Boolean) = _state.update { it.copy(naturalScroll = v) }
    fun toggleSettings() = _state.update { it.copy(showSettings = !it.showSettings) }

    // Cleans up the accessibility callback, display listener, and mouse service when the
    // ViewModel is destroyed (e.g. app process ends or activity is permanently finished).
    override fun onCleared() {
        TouchpadAccessibilityService.onExternalTextFocus = null
        displayManager.unregisterDisplayListener(displayListener)
        mouse.destroy()
    }
}
