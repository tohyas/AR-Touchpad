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
//
// Modifications Copyright 2026 Tohya Sugano.

package com.tohyas.deskpad

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tohyas.deskpad.ui.TouchpadScreen
import com.tohyas.deskpad.ui.theme.DeskPadTheme

// Single-activity entry point. Hosts the Compose UI and owns the ViewModel lifecycle.
class MainActivity : ComponentActivity() {

    private val viewModel: TouchpadViewModel by viewModels()
    private var controllerWindowNonFocusable = false

    // Sets up edge-to-edge display and mounts the full-screen TouchpadScreen composable.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyControllerWindowFocusPolicy()
        setContent {
            DeskPadTheme {
                TouchpadScreen(viewModel = viewModel)
            }
        }
    }

    // Re-checks display and Shizuku state when returning from Settings or the permission
    // dialog, so the UI reflects any changes the user made while away.
    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        applyControllerWindowFocusPolicy()
    }

    private fun applyControllerWindowFocusPolicy() {
        setControllerWindowNonFocusable(true)
    }

    private fun setControllerWindowNonFocusable(enabled: Boolean) {
        if (controllerWindowNonFocusable == enabled) return
        controllerWindowNonFocusable = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        Log.d(TAG, "Controller non-focusable window flag applied=$enabled")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
