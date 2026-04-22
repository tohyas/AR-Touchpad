package com.pgratz.artouchpad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.pgratz.artouchpad.ui.TouchpadScreen
import com.pgratz.artouchpad.ui.theme.ARTouchpadTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TouchpadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ARTouchpadTheme {
                TouchpadScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
