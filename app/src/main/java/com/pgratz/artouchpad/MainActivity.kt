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

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pgratz.artouchpad.ui.TouchpadScreen
import com.pgratz.artouchpad.ui.theme.ARTouchpadTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Single-activity entry point. Hosts the Compose UI and owns the ViewModel lifecycle.
class MainActivity : ComponentActivity() {

    private val viewModel: TouchpadViewModel by viewModels()
    private var qwertyWindowNonFocusable = false

    // Sets up edge-to-edge display and mounts the full-screen TouchpadScreen composable.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeQwertyFocusMode()
        setContent {
            ARTouchpadTheme {
                TouchpadScreen(viewModel = viewModel)
            }
        }
    }

    // Re-checks display and Shizuku state when returning from Settings or the permission
    // dialog, so the UI reflects any changes the user made while away.
    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun observeQwertyFocusMode() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state
                    .map { it.showKeyboard && it.keyboardMode == VirtualKeyboardMode.QWERTY }
                    .distinctUntilChanged()
                    .collectLatest(::setQwertyWindowNonFocusable)
            }
        }
    }

    private fun setQwertyWindowNonFocusable(enabled: Boolean) {
        if (qwertyWindowNonFocusable == enabled) return
        qwertyWindowNonFocusable = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        Log.d(TAG, "QWERTY non-focusable window flag applied=$enabled")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
