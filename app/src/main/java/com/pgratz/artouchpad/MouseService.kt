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

import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Shizuku UserService — runs as shell uid.
 *
 * Creates a real virtual mouse via /dev/uinput using JNI (the only reliable
 * way to call ioctl with a value argument on Android 16, where Os.ioctl was
 * stripped down to ioctlInetAddress and ioctlInt(fd, req) — no generic variant).
 *
 * Shell uid is in the `input` group and has rw access to /dev/uinput, so no
 * additional permissions are needed beyond what Shizuku already grants.
 */
class MouseService : IMouseService.Stub() {

    private var displayId = android.view.Display.DEFAULT_DISPLAY
    private var displayWidth = 1920
    private var displayHeight = 1080
    private var cursorX = 960f
    private var cursorY = 540f

    private var uinputReady = false

    // Sub-pixel accumulators: carry fractional remainders between calls so that
    // slow movements (e.g. 0.4 px/frame) accumulate cleanly instead of truncating
    // to zero every frame and producing sudden jumps.
    private var accumX = 0f
    private var accumY = 0f
    private var accumScroll = 0f

    // Reflection handles for display-targeted key injection.
    // injectInputEvent(event, mode) on InputManagerGlobal respects the displayId
    // embedded in the event, routing the key to the focused window on that display.
    private val imgClass by lazy {
        runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
    }
    private val imgInstance by lazy {
        imgClass?.getMethod("getInstance")?.invoke(null)
    }
    private val imgInjectEvent by lazy {
        imgClass?.getMethod("injectInputEvent",
            android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayIdMethod by lazy {
        runCatching {
            android.view.InputEvent::class.java
                .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
        }.getOrNull()
    }

    init {
        initUinput()
    }

    private fun initUinput() {
        try {
            val fd = UinputNative.nOpen()
            if (fd < 0) { Log.e(TAG, "nOpen failed"); return }

            fun ioctl(req: Int, value: Int) {
                val r = UinputNative.nIoctl(req, value)
                if (r < 0) Log.w(TAG, "ioctl(0x${req.toString(16)}, $value) returned $r")
            }

            ioctl(UI_SET_EVBIT,  EV_SYN)
            ioctl(UI_SET_EVBIT,  EV_KEY)
            ioctl(UI_SET_EVBIT,  EV_REL)
            ioctl(UI_SET_RELBIT, REL_X)
            ioctl(UI_SET_RELBIT, REL_Y)
            ioctl(UI_SET_RELBIT, REL_WHEEL)
            ioctl(UI_SET_RELBIT, REL_HWHEEL)
            ioctl(UI_SET_KEYBIT, BTN_LEFT)
            ioctl(UI_SET_KEYBIT, BTN_RIGHT)
            ioctl(UI_SET_KEYBIT, BTN_MIDDLE)
            ioctl(UI_SET_KEYBIT, KEY_BACK)
            ioctl(UI_SET_KEYBIT, KEY_HOME)
            ioctl(UI_SET_KEYBIT, KEY_APPSWITCH)

            val n = UinputNative.nWriteDevInfo("AR Touchpad Mouse")
            if (n < 0) { Log.e(TAG, "nWriteDevInfo failed"); return }

            ioctl(UI_DEV_CREATE, 0)

            Thread.sleep(400) // give InputReader time to register the device
            uinputReady = true
            Log.i(TAG, "uinput device ready")
        } catch (e: Exception) {
            Log.e(TAG, "initUinput failed: $e")
        }
    }

    private fun ev(type: Int, code: Int, value: Int) = UinputNative.nWriteEvent(type, code, value)
    private fun sync() = ev(EV_SYN, SYN_REPORT, 0)

    override fun setDisplay(id: Int, width: Int, height: Int) {
        displayId = id
        displayWidth = width
        displayHeight = height
        cursorX = width / 2f
        cursorY = height / 2f
        accumX = 0f
        accumY = 0f
        accumScroll = 0f

        // Nudge the pointer to wake the cursor.
        if (uinputReady) {
            ev(EV_REL, REL_X, 1); ev(EV_REL, REL_Y, 1); sync()
            Thread.sleep(50)
            ev(EV_REL, REL_X, -1); ev(EV_REL, REL_Y, -1); sync()
        }
        Log.i(TAG, "setDisplay id=$id ${width}x${height} uinputReady=$uinputReady")
    }

    override fun moveMouse(dx: Float, dy: Float) {
        if (!uinputReady) return
        accumX += dx
        accumY += dy
        val idx = accumX.toInt()
        val idy = accumY.toInt()
        if (idx == 0 && idy == 0) return
        accumX -= idx
        accumY -= idy
        cursorX = (cursorX + idx).coerceIn(0f, displayWidth - 1f)
        cursorY = (cursorY + idy).coerceIn(0f, displayHeight - 1f)
        ev(EV_REL, REL_X, idx)
        ev(EV_REL, REL_Y, idy)
        sync()
    }

    override fun click(x: Float, y: Float, button: Int) {
        if (!uinputReady) return
        val btn = if (button == MotionEvent.BUTTON_SECONDARY) BTN_RIGHT else BTN_LEFT
        ev(EV_KEY, btn, 1); sync()
        Thread.sleep(50)
        ev(EV_KEY, btn, 0); sync()
    }

    // Takes an Android KeyEvent keycode (e.g. KeyEvent.KEYCODE_BACK = 4).
    // Injects via InputManagerGlobal with the event's displayId set so the key
    // lands on the focused window of the glasses display, not the phone.
    override fun pressKey(androidKeycode: Int) {
        val instance = imgInstance ?: run { Log.e(TAG, "InputManagerGlobal unavailable"); return }
        val inject   = imgInjectEvent ?: run { Log.e(TAG, "injectInputEvent unavailable"); return }
        val setDisp  = setDisplayIdMethod ?: run { Log.e(TAG, "setDisplayId unavailable"); return }
        try {
            val t = SystemClock.uptimeMillis()
            val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, androidKeycode, 0)
            setDisp.invoke(down, displayId)
            inject.invoke(instance, down, 0 /*INJECT_INPUT_EVENT_MODE_ASYNC*/)
            Thread.sleep(20)
            val up = KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, androidKeycode, 0)
            setDisp.invoke(up, displayId)
            inject.invoke(instance, up, 0)
            Log.d(TAG, "pressKey keycode=$androidKeycode displayId=$displayId")
        } catch (e: Exception) {
            Log.e(TAG, "pressKey $androidKeycode failed: $e")
        }
    }

    override fun typeText(text: String) {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(text.toCharArray())
        if (events != null) {
            injectKeyEvents(events)
        } else {
            for (ch in text) {
                val evs = kcm.getEvents(charArrayOf(ch)) ?: continue
                injectKeyEvents(evs)
            }
        }
        Log.d(TAG, "typeText \"$text\" displayId=$displayId")
    }

    private fun injectKeyEvents(events: Array<out KeyEvent>) {
        val instance = imgInstance ?: return
        val inject   = imgInjectEvent ?: return
        val setDisp  = setDisplayIdMethod ?: return
        for (ev in events) {
            val targeted = KeyEvent(ev.downTime, ev.eventTime, ev.action,
                                    ev.keyCode, ev.repeatCount, ev.metaState)
            setDisp.invoke(targeted, displayId)
            inject.invoke(instance, targeted, 0)
        }
    }

    override fun scroll(dx: Float, dy: Float) {
        if (!uinputReady) return
        // 20px of finger movement = 1 wheel detent (adjustable via scrollSpeed in ViewModel).
        accumScroll += dy / 20f
        val steps = accumScroll.toInt()
        if (steps != 0) {
            accumScroll -= steps
            ev(EV_REL, REL_WHEEL, -steps)
            sync()
        }
    }

    override fun destroy() {
        UinputNative.nClose()
        uinputReady = false
    }

    companion object {
        private const val TAG = "MouseService"

        const val UI_SET_EVBIT  = 0x40045564
        const val UI_SET_KEYBIT = 0x40045565
        const val UI_SET_RELBIT = 0x40045566
        const val UI_DEV_CREATE  = 0x5501
        const val UI_DEV_DESTROY = 0x5502

        const val EV_SYN = 0; const val EV_KEY = 1; const val EV_REL = 2
        const val REL_X = 0; const val REL_Y = 1; const val REL_HWHEEL = 6; const val REL_WHEEL = 8
        const val BTN_LEFT = 0x110; const val BTN_RIGHT = 0x111; const val BTN_MIDDLE = 0x112
        const val KEY_BACK = 158; const val KEY_HOME = 102; const val KEY_APPSWITCH = 580
        const val SYN_REPORT = 0
    }
}
