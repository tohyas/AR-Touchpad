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

import android.util.Log

object UinputNative {
    private const val TAG = "UinputNative"

    init {
        var ok = false
        try {
            System.loadLibrary("artouchpad")
            ok = true
        } catch (e: UnsatisfiedLinkError) {
            // Shizuku service process may not have the standard library path set.
            // Derive the .so path from where our class was loaded.
            try {
                val src = UinputNative::class.java.protectionDomain?.codeSource?.location?.file
                if (src != null) {
                    // src is something like /data/app/.../base.apk — strip filename
                    val base = src.removeSuffix("/base.apk").removeSuffix("base.apk")
                    val lib = "$base/lib/arm64-v8a/libartouchpad.so"
                    System.load(lib)
                    ok = true
                    Log.i(TAG, "loaded via explicit path: $lib")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "explicit load also failed: $e2")
            }
            if (!ok) Log.e(TAG, "loadLibrary failed: $e")
        }
        if (ok) Log.i(TAG, "native library loaded")
    }

    @JvmStatic external fun nOpen(): Int
    @JvmStatic external fun nIoctl(request: Int, value: Int): Int
    @JvmStatic external fun nWriteDevInfo(name: String): Int
    @JvmStatic external fun nWriteEvent(type: Int, code: Int, value: Int): Int
    @JvmStatic external fun nClose()
}
