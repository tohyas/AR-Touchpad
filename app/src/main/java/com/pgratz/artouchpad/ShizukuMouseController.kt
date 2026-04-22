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

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.view.MotionEvent
import rikka.shizuku.Shizuku

class ShizukuMouseController {

    var isConnected = false
        private set

    private var service: IMouseService? = null
    private var onStateChanged: (() -> Unit)? = null
    private var lastMoveMs = 0L

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.pgratz.artouchpad", MouseService::class.java.name)
    )
        .processNameSuffix("mouse")
        .daemon(false)
        .version(11)  // bumped — key injection via InputManagerGlobal+displayId (Android keycodes)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IMouseService.Stub.asInterface(binder)
            isConnected = true
            onStateChanged?.invoke()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isConnected = false
            onStateChanged?.invoke()
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind()
        onStateChanged?.invoke()
    }

    fun init(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    fun destroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        unbind()
    }

    fun hasShizuku(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        hasShizuku() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermission() {
        if (hasShizuku() && !hasPermission()) Shizuku.requestPermission(1001)
    }

    fun bind() {
        if (hasPermission() && !isConnected) {
            Shizuku.bindUserService(userServiceArgs, connection)
        }
    }

    fun unbind() {
        if (isConnected) runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
        isConnected = false
    }

    fun setDisplay(displayId: Int, width: Int, height: Int) {
        runCatching { service?.setDisplay(displayId, width, height) }
    }

    fun moveMouse(dx: Float, dy: Float) {
        // Cap at ~60 Hz to avoid flooding the input dispatcher
        val now = System.currentTimeMillis()
        if (now - lastMoveMs < 16L) return
        lastMoveMs = now
        runCatching { service?.moveMouse(dx, dy) }
    }

    fun click(x: Float, y: Float) {
        runCatching { service?.click(x, y, MotionEvent.BUTTON_PRIMARY) }
    }

    fun rightClick(x: Float, y: Float) {
        runCatching { service?.click(x, y, MotionEvent.BUTTON_SECONDARY) }
    }

    fun doubleClick(x: Float, y: Float) {
        runCatching {
            service?.click(x, y, MotionEvent.BUTTON_PRIMARY)
            Thread.sleep(120)
            service?.click(x, y, MotionEvent.BUTTON_PRIMARY)
        }
    }

    fun scroll(dx: Float, dy: Float) {
        runCatching { service?.scroll(dx, dy) }
    }

    fun pressKey(linuxKeyCode: Int) {
        runCatching { service?.pressKey(linuxKeyCode) }
    }
}
