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
import android.util.Log
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TouchMode { IDLE, CURSOR, SCROLL, SELECT }
enum class VirtualKeyboardMode { QWERTY, KANA, SYMBOLS, EDITING }

sealed class VirtualKey {
    data class AndroidKeyCode(
        val keyCode: Int,
        val withCtrl: Boolean = false,
        val withShift: Boolean = false,
    ) : VirtualKey()
    data class AsciiChar(val char: Char, val withCtrl: Boolean = false, val withShift: Boolean = false) : VirtualKey()
    data class HardwareKey(
        val linuxKeyCode: Int,
        val androidKeyCode: Int? = null,
        val withShift: Boolean = false,
        val withCtrl: Boolean = false,
    ) : VirtualKey()
}

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
    val naturalScroll: Boolean = false,
    val showSettings: Boolean = false,
    val touchMode: TouchMode = TouchMode.IDLE,
    val showKeyboard: Boolean = false,
    val keyboardMode: VirtualKeyboardMode = VirtualKeyboardMode.QWERTY,
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

    private var pinchAccum = 0f
    private var smoothDx = 0f
    private var smoothDy = 0f

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
    // Applies velocity-adaptive exponential smoothing (heavy for slow/fine moves,
    // light for fast moves) before scaling by sensitivity. This suppresses finger
    // tremor on precise movements without adding noticeable lag on fast sweeps.
    fun moveCursor(rawDx: Float, rawDy: Float) {
        val speed = kotlin.math.sqrt(rawDx * rawDx + rawDy * rawDy)
        val alpha = (speed / 8f).coerceIn(0.30f, 0.85f)
        smoothDx = alpha * rawDx + (1f - alpha) * smoothDx
        smoothDy = alpha * rawDy + (1f - alpha) * smoothDy

        val sens = _state.value.sensitivity
        val dx = smoothDx * sens
        val dy = smoothDy * sens
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
    // Resets the smoothing filter on IDLE so the decaying tail doesn't bleed into the next touch.
    fun setTouchMode(mode: TouchMode) {
        if (mode == TouchMode.IDLE) { smoothDx = 0f; smoothDy = 0f }
        _state.update { it.copy(touchMode = mode) }
    }

    // Delegate clicks to MouseService at the current tracked cursor position.
    fun performClick() = mouse.click(_state.value.cursorX, _state.value.cursorY)
    fun performDoubleClick() = mouse.doubleClick(_state.value.cursorX, _state.value.cursorY)
    fun performRightClick() = mouse.rightClick(_state.value.cursorX, _state.value.cursorY)

    // Presses BTN_LEFT without releasing; moveCursor calls while held perform a drag.
    fun startSelectDrag() = mouse.mouseDown()

    // Releases BTN_LEFT to end a drag.
    fun endSelectDrag() {
        mouse.mouseUp()
    }

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

    // Sends one virtual keyboard key through the service-level hardware keyboard path.
    // Falls back to display-targeted injection only if uinput keyboard output is unavailable.
    fun pressVirtualKey(key: VirtualKey) {
        when (key) {
            is VirtualKey.AndroidKeyCode -> {
                val linuxKeyCode = androidKeyCodeToLinux(key.keyCode)
                if (linuxKeyCode != null) {
                    pressHardwareKey(linuxKeyCode, key.keyCode, withShift = key.withShift, withCtrl = key.withCtrl)
                } else {
                    Log.d(KEYBOARD_TAG, "fallback injection used keyCode=${key.keyCode}; output=fallback injection")
                    if (key.withCtrl) mouse.pressKeyWithCtrl(key.keyCode) else mouse.pressKey(key.keyCode)
                }
            }
            is VirtualKey.AsciiChar -> typeAsciiChar(key.char, key.withCtrl, key.withShift)
            is VirtualKey.HardwareKey -> pressHardwareKey(
                linuxKeyCode = key.linuxKeyCode,
                androidKeyCode = key.androidKeyCode,
                withShift = key.withShift,
                withCtrl = key.withCtrl,
            )
        }
    }

    fun typeAsciiChar(char: Char, withCtrl: Boolean = false, forceShift: Boolean = false) {
        val mapped = asciiKey(char)

        if (mapped != null) {
            pressHardwareKey(mapped.linuxKeyCode, mapped.androidKeyCode, mapped.withShift || forceShift, withCtrl)
        } else {
            Log.d(KEYBOARD_TAG, "virtual key pressed char=$char; output=fallback text injection")
            mouse.typeText(char.toString())
        }
    }

    fun sendRomajiKey(char: Char, withCtrl: Boolean = false) = typeAsciiChar(char, withCtrl)
    fun sendRomajiSequence(text: String) {
        text.forEach { typeAsciiChar(it) }
    }

    fun pasteClipboardToExternal() {
        pressVirtualKey(VirtualKey.AndroidKeyCode(KeyEvent.KEYCODE_V, withCtrl = true))
    }

    // Input: dDist — span change in pixels this frame (positive = spreading = zoom in).
    // Accumulates until 200 px threshold to avoid jitter; each 200 px = 1 AXIS_VSCROLL detent,
    // which Chrome/WebView maps to one zoom step (~10%) without affecting the system font scale.
    fun pinchZoom(dDist: Float) {
        pinchAccum += dDist
        val detents = (pinchAccum / 200f).toInt()
        if (detents != 0) {
            pinchAccum -= detents * 200f
            mouse.ctrlScroll(detents.toFloat())
        }
    }
    // Toggles showKeyboard in state, which shows or hides the virtual keyboard panel in the UI.
    fun toggleKeyboard() = _state.update {
        val willShow = !it.showKeyboard
        if (willShow) Log.d(KEYBOARD_TAG, "QWERTY keyboard opened")
        it.copy(showKeyboard = willShow, keyboardMode = if (willShow) VirtualKeyboardMode.QWERTY else it.keyboardMode)
    }

    fun setKeyboardMode(mode: VirtualKeyboardMode) = _state.update {
        if (mode == VirtualKeyboardMode.QWERTY && !it.showKeyboard) {
            Log.d(KEYBOARD_TAG, "QWERTY keyboard opened")
        }
        it.copy(showKeyboard = true, keyboardMode = mode)
    }

    // Input: text accumulated in the phone keyboard proxy fallback.
    // Dismisses the phone keyboard first (to avoid IME session conflicts), waits 200 ms for
    // the IME to tear down, then injects the text to the glasses display without appending Enter.
    fun sendKeyboardText(text: String) {
        if (text.isEmpty()) { toggleKeyboard(); return }
        toggleKeyboard()
        viewModelScope.launch {
            delay(200)
            mouse.typeText(text)
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

    companion object {
        private const val KEYBOARD_TAG = "VirtualKeyboard"

        private const val LINUX_KEY_1 = 2
        private const val LINUX_KEY_2 = 3
        private const val LINUX_KEY_3 = 4
        private const val LINUX_KEY_5 = 6
        private const val LINUX_KEY_7 = 8
        private const val LINUX_KEY_9 = 10
        private const val LINUX_KEY_0 = 11
        private const val LINUX_KEY_MINUS = 12
        private const val LINUX_KEY_EQUAL = 13
        private const val LINUX_KEY_BACKSPACE = 14
        private const val LINUX_KEY_TAB = 15
        private const val LINUX_KEY_ENTER = 28
        private const val LINUX_KEY_SEMICOLON = 39
        private const val LINUX_KEY_APOSTROPHE = 40
        private const val LINUX_KEY_BACKSLASH = 43
        private const val LINUX_KEY_COMMA = 51
        private const val LINUX_KEY_DOT = 52
        private const val LINUX_KEY_SLASH = 53
        private const val LINUX_KEY_SPACE = 57
        private const val LINUX_KEY_ESC = 1
        private const val LINUX_KEY_UP = 103
        private const val LINUX_KEY_LEFT = 105
        private const val LINUX_KEY_RIGHT = 106
        private const val LINUX_KEY_DOWN = 108
        private const val LINUX_KEY_DELETE = 111

        private fun linuxDigitKeyCode(char: Char): Int =
            if (char == '0') LINUX_KEY_0 else LINUX_KEY_1 + (char - '1')

        private fun linuxLetterKeyCode(char: Char): Int = when (char) {
            'a' -> 30; 'b' -> 48; 'c' -> 46; 'd' -> 32; 'e' -> 18; 'f' -> 33; 'g' -> 34
            'h' -> 35; 'i' -> 23; 'j' -> 36; 'k' -> 37; 'l' -> 38; 'm' -> 50; 'n' -> 49
            'o' -> 24; 'p' -> 25; 'q' -> 16; 'r' -> 19; 's' -> 31; 't' -> 20; 'u' -> 22
            'v' -> 47; 'w' -> 17; 'x' -> 45; 'y' -> 21; else -> 44
        }
    }

    private fun pressHardwareKey(
        linuxKeyCode: Int,
        androidKeyCode: Int?,
        withShift: Boolean = false,
        withCtrl: Boolean = false,
    ) {
        Log.d(KEYBOARD_TAG, "virtual key pressed linuxKey=$linuxKeyCode shift=$withShift ctrl=$withCtrl")
        val sent = mouse.pressHardwareKey(linuxKeyCode, withShift, withCtrl)
        if (sent) {
            Log.d(KEYBOARD_TAG, "key sent through uinput keyboard linuxKey=$linuxKeyCode shift=$withShift ctrl=$withCtrl")
        } else {
            Log.d(KEYBOARD_TAG, "fallback injection used linuxKey=$linuxKeyCode androidKey=$androidKeyCode")
            if (androidKeyCode != null) {
                if (withCtrl) mouse.pressKeyWithCtrl(androidKeyCode) else mouse.pressKey(androidKeyCode)
            }
        }
    }

    private fun androidKeyCodeToLinux(androidKeyCode: Int): Int? = when (androidKeyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> linuxLetterKeyCode('a' + (androidKeyCode - KeyEvent.KEYCODE_A))
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> linuxDigitKeyCode(('0' + (androidKeyCode - KeyEvent.KEYCODE_0)))
        KeyEvent.KEYCODE_SPACE -> LINUX_KEY_SPACE
        KeyEvent.KEYCODE_ENTER -> LINUX_KEY_ENTER
        KeyEvent.KEYCODE_DEL -> LINUX_KEY_BACKSPACE
        KeyEvent.KEYCODE_COMMA -> LINUX_KEY_COMMA
        KeyEvent.KEYCODE_PERIOD -> LINUX_KEY_DOT
        KeyEvent.KEYCODE_TAB -> LINUX_KEY_TAB
        KeyEvent.KEYCODE_ESCAPE -> LINUX_KEY_ESC
        KeyEvent.KEYCODE_FORWARD_DEL -> LINUX_KEY_DELETE
        KeyEvent.KEYCODE_DPAD_UP -> LINUX_KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> LINUX_KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LINUX_KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> LINUX_KEY_RIGHT
        else -> null
    }

    private data class AsciiKey(val linuxKeyCode: Int, val androidKeyCode: Int?, val withShift: Boolean = false)

    private fun asciiKey(char: Char): AsciiKey? {
        val lower = char.lowercaseChar()
        return when {
            lower in 'a'..'z' -> AsciiKey(
                linuxLetterKeyCode(lower),
                KeyEvent.KEYCODE_A + (lower - 'a'),
                char.isUpperCase(),
            )
            char in '0'..'9' -> AsciiKey(linuxDigitKeyCode(char), KeyEvent.KEYCODE_0 + (char - '0'))
            char == ' ' -> AsciiKey(LINUX_KEY_SPACE, KeyEvent.KEYCODE_SPACE)
            char == '\n' -> AsciiKey(LINUX_KEY_ENTER, KeyEvent.KEYCODE_ENTER)
            char == ',' -> AsciiKey(LINUX_KEY_COMMA, KeyEvent.KEYCODE_COMMA)
            char == '.' -> AsciiKey(LINUX_KEY_DOT, KeyEvent.KEYCODE_PERIOD)
            char == '-' -> AsciiKey(LINUX_KEY_MINUS, KeyEvent.KEYCODE_MINUS)
            char == '/' -> AsciiKey(LINUX_KEY_SLASH, KeyEvent.KEYCODE_SLASH)
            char == ';' -> AsciiKey(LINUX_KEY_SEMICOLON, KeyEvent.KEYCODE_SEMICOLON)
            char == '\'' -> AsciiKey(LINUX_KEY_APOSTROPHE, KeyEvent.KEYCODE_APOSTROPHE)
            char == '=' -> AsciiKey(LINUX_KEY_EQUAL, KeyEvent.KEYCODE_EQUALS)
            char == '\\' || char == '¥' -> AsciiKey(LINUX_KEY_BACKSLASH, KeyEvent.KEYCODE_BACKSLASH)
            char == ':' -> AsciiKey(LINUX_KEY_SEMICOLON, KeyEvent.KEYCODE_SEMICOLON, withShift = true)
            char == '(' -> AsciiKey(LINUX_KEY_9, KeyEvent.KEYCODE_9, withShift = true)
            char == ')' -> AsciiKey(LINUX_KEY_0, KeyEvent.KEYCODE_0, withShift = true)
            char == '&' -> AsciiKey(LINUX_KEY_7, KeyEvent.KEYCODE_7, withShift = true)
            char == '@' -> AsciiKey(LINUX_KEY_2, KeyEvent.KEYCODE_2, withShift = true)
            char == '"' -> AsciiKey(LINUX_KEY_APOSTROPHE, KeyEvent.KEYCODE_APOSTROPHE, withShift = true)
            char == '?' -> AsciiKey(LINUX_KEY_SLASH, KeyEvent.KEYCODE_SLASH, withShift = true)
            char == '!' -> AsciiKey(LINUX_KEY_1, KeyEvent.KEYCODE_1, withShift = true)
            char == '#' -> AsciiKey(LINUX_KEY_3, KeyEvent.KEYCODE_3, withShift = true)
            char == '%' -> AsciiKey(LINUX_KEY_5, KeyEvent.KEYCODE_5, withShift = true)
            char == '+' -> AsciiKey(LINUX_KEY_EQUAL, KeyEvent.KEYCODE_EQUALS, withShift = true)
            else -> null
        }
    }
}
