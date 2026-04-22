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

package com.pgratz.artouchpad.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgratz.artouchpad.DisplayInfo
import com.pgratz.artouchpad.TouchMode
import com.pgratz.artouchpad.TouchpadViewModel
import kotlin.math.abs

private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF1A2332)
private val SURFACE_DISABLED = Color(0xFF111820)
private val ACCENT = Color(0xFF4FC3F7)
private val ACCENT_DIM = Color(0xFF1A4A6A)
private val TEXT = Color(0xFFE0E0E0)
private val TEXT_DIM = Color(0xFF90A4AE)
private val TEXT_MUTED = Color(0xFF546E7A)
private val NAV_ICON = Color(0xFFB0BEC5)

private const val MOVE_THRESHOLD = 5f
private const val TAP_MAX_MS = 220L
private const val LONG_PRESS_MS = 600L
private const val DOUBLE_TAP_WINDOW_MS = 300L

@Composable
fun TouchpadScreen(viewModel: TouchpadViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .systemBarsPadding(),
    ) {
        StatusBar(
            shizukuAvailable = state.shizukuAvailable,
            shizukuPermission = state.shizukuPermission,
            mouseReady = state.mouseReady,
            allDisplays = state.allDisplays,
            targetDisplay = state.targetDisplay,
            touchMode = state.touchMode,
            onSettingsClick = viewModel::toggleSettings,
            onGrantShizuku = viewModel::requestShizukuPermission,
            onConnectMouse = { viewModel.mouse.bind() },
        )

        if (state.showSettings) {
            SettingsPanel(
                sensitivity = state.sensitivity,
                scrollSpeed = state.scrollSpeed,
                naturalScroll = state.naturalScroll,
                onSensitivity = viewModel::setSensitivity,
                onScrollSpeed = viewModel::setScrollSpeed,
                onNaturalScroll = viewModel::setNaturalScroll,
                onDismiss = viewModel::toggleSettings,
            )
        } else {
            TouchpadSurface(
                modifier = Modifier.weight(1f),
                cursorX = state.cursorX,
                cursorY = state.cursorY,
                displayWidth = state.displayWidth,
                displayHeight = state.displayHeight,
                enabled = state.mouseReady,
                onMoveCursor = viewModel::moveCursor,
                onClick = { viewModel.performClick() },
                onDoubleClick = { viewModel.performDoubleClick() },
                onRightClick = { viewModel.performRightClick() },
                onScroll = viewModel::performScroll,
                onTouchModeChanged = viewModel::setTouchMode,
            )
            NavigationBar(
                onBack = { viewModel.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) },
                onHome = { viewModel.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) },
                onRecents = { viewModel.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) },
                onScreenshot = { viewModel.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) },
            )
        }
    }
}

@Composable
private fun StatusBar(
    shizukuAvailable: Boolean,
    shizukuPermission: Boolean,
    mouseReady: Boolean,
    allDisplays: List<DisplayInfo>,
    targetDisplay: DisplayInfo?,
    touchMode: TouchMode,
    onSettingsClick: () -> Unit,
    onGrantShizuku: () -> Unit,
    onConnectMouse: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AR Touchpad", color = TEXT, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(mouseReady, "Mouse")
                    StatusDot(targetDisplay != null, "Display")
                    if (touchMode != TouchMode.IDLE) {
                        Text(
                            if (touchMode == TouchMode.SCROLL) "↕ scroll" else "⊹ cursor",
                            color = ACCENT, fontSize = 11.sp,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    !shizukuAvailable ->
                        Text("Shizuku off", color = Color(0xFFFF7043), fontSize = 11.sp)
                    !shizukuPermission ->
                        TextButton(onClick = onGrantShizuku, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Grant Shizuku", color = Color(0xFFFF7043), fontSize = 12.sp)
                        }
                    !mouseReady ->
                        TextButton(onClick = onConnectMouse, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Connect", color = Color(0xFFFFB300), fontSize = 12.sp)
                        }
                }
                IconButton(onClick = onSettingsClick) {
                    Text("⚙", color = TEXT_DIM, fontSize = 22.sp)
                }
            }
        }

        // Display debug panel — always visible so we can diagnose routing
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (allDisplays.isEmpty()) {
                Text("No displays detected", color = Color(0xFFFF7043), fontSize = 11.sp)
            } else {
                allDisplays.forEach { d ->
                    val isTarget = d.id == targetDisplay?.id
                    val label = "Display ${d.id}: ${d.width}×${d.height}  ${d.name}"
                    Text(
                        if (isTarget) "▶ $label  ← targeting this" else "  $label",
                        color = if (isTarget) ACCENT else TEXT_MUTED,
                        fontSize = 11.sp,
                    )
                }
            }
            if (allDisplays.size == 1) {
                Text(
                    "Only 1 display seen — glasses may be in mirror mode. " +
                    "Pull down notification shade and switch to Desktop / Extended mode.",
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                )
            }
            if (targetDisplay == null && allDisplays.size > 1) {
                Text("Multiple displays but no external found — unexpected", color = Color(0xFFFF7043), fontSize = 11.sp)
            }
        }

        HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    }
}

@Composable
private fun StatusDot(active: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF4CAF50) else Color(0xFF424242))
        )
        Text(label, color = TEXT_MUTED, fontSize = 11.sp)
    }
}

@Composable
private fun TouchpadSurface(
    modifier: Modifier,
    cursorX: Float,
    cursorY: Float,
    displayWidth: Int,
    displayHeight: Int,
    enabled: Boolean,
    onMoveCursor: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (Float, Float) -> Unit,
    onTouchModeChanged: (TouchMode) -> Unit,
) {
    var touchPoints by remember { mutableStateOf(listOf<Offset>()) }

    Box(
        modifier = modifier.padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(if (enabled) SURFACE else SURFACE_DISABLED)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    var lastPositions = mapOf<PointerId, Offset>()
                    var downTime = 0L
                    var didMove = false
                    var lastTapTime = 0L

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val now = System.currentTimeMillis()
                            val pressed = event.changes.filter { it.pressed }
                            val justPressed = event.changes.filter { it.pressed && !it.previousPressed }
                            val justReleased = event.changes.filter { !it.pressed && it.previousPressed }

                            touchPoints = pressed.map { it.position }

                            if (justPressed.isNotEmpty() && pressed.size == 1) {
                                downTime = now
                                didMove = false
                                lastPositions = pressed.associate { it.id to it.position }
                            }

                            when (pressed.size) {
                                1 -> {
                                    val p = pressed.first()
                                    val last = lastPositions[p.id]
                                    if (last != null) {
                                        val dx = p.position.x - last.x
                                        val dy = p.position.y - last.y
                                        if (abs(dx) > MOVE_THRESHOLD || abs(dy) > MOVE_THRESHOLD) {
                                            didMove = true
                                            onMoveCursor(dx, dy)
                                            onTouchModeChanged(TouchMode.CURSOR)
                                        }
                                    }
                                    lastPositions = pressed.associate { it.id to it.position }
                                    p.consume()
                                }
                                2 -> {
                                    val newPositions = pressed.associate { it.id to it.position }
                                    if (lastPositions.size == 2) {
                                        val ids = pressed.map { it.id }
                                        val p0prev = lastPositions[ids[0]]
                                        val p1prev = lastPositions[ids[1]]
                                        val p0curr = newPositions[ids[0]]
                                        val p1curr = newPositions[ids[1]]
                                        if (p0prev != null && p1prev != null && p0curr != null && p1curr != null) {
                                            val dx = ((p0curr.x - p0prev.x) + (p1curr.x - p1prev.x)) / 2f
                                            val dy = ((p0curr.y - p0prev.y) + (p1curr.y - p1prev.y)) / 2f
                                            if (abs(dx) > 1f || abs(dy) > 1f) {
                                                onScroll(dx, dy)
                                                onTouchModeChanged(TouchMode.SCROLL)
                                                didMove = true
                                            }
                                        }
                                    }
                                    lastPositions = newPositions
                                    pressed.forEach { it.consume() }
                                }
                            }

                            if (justReleased.isNotEmpty() && pressed.isEmpty()) {
                                val duration = now - downTime
                                onTouchModeChanged(TouchMode.IDLE)

                                when {
                                    !didMove && duration >= LONG_PRESS_MS -> onRightClick()
                                    !didMove && duration < TAP_MAX_MS -> {
                                        if (now - lastTapTime < DOUBLE_TAP_WINDOW_MS) {
                                            onDoubleClick()
                                            lastTapTime = 0L
                                        } else {
                                            onClick()
                                            lastTapTime = now
                                        }
                                    }
                                }
                                lastPositions = emptyMap()
                                touchPoints = emptyList()
                            }
                        }
                    }
                },
        ) {
            // Dot grid
            val spacing = 32.dp.toPx()
            val cols = (size.width / spacing).toInt() + 1
            val rows = (size.height / spacing).toInt() + 1
            val xOffset = (size.width - (cols - 1) * spacing) / 2f
            val yOffset = (size.height - (rows - 1) * spacing) / 2f
            for (col in 0 until cols) {
                for (row in 0 until rows) {
                    drawCircle(
                        color = Color(0xFF263545),
                        radius = 1.8.dp.toPx(),
                        center = Offset(xOffset + col * spacing, yOffset + row * spacing),
                    )
                }
            }

            if (!enabled) return@Canvas

            // Cursor position indicator (scaled from external display coords)
            if (displayWidth > 0 && displayHeight > 0) {
                val cx = (cursorX / displayWidth) * size.width
                val cy = (cursorY / displayHeight) * size.height
                drawCircle(color = ACCENT.copy(alpha = 0.15f), radius = 16.dp.toPx(), center = Offset(cx, cy))
                drawCircle(color = ACCENT.copy(alpha = 0.6f), radius = 4.dp.toPx(), center = Offset(cx, cy))
                // Crosshair lines
                val arm = 12.dp.toPx()
                drawLine(ACCENT.copy(alpha = 0.4f), Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = 1.dp.toPx())
                drawLine(ACCENT.copy(alpha = 0.4f), Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = 1.dp.toPx())
            }

            // Live touch points
            touchPoints.forEach { pt ->
                drawCircle(color = ACCENT.copy(alpha = 0.2f), radius = 28.dp.toPx(), center = pt)
                drawCircle(color = ACCENT, radius = 6.dp.toPx(), center = pt)
                drawCircle(
                    color = ACCENT.copy(alpha = 0.5f),
                    radius = 18.dp.toPx(),
                    center = pt,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        // Disabled overlay text (outside Canvas, inside Box)
        if (!enabled) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Touchpad Disabled", color = TEXT_MUTED, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Grant Shizuku permission to activate", color = Color(0xFF37474F), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun NavigationBar(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onScreenshot: () -> Unit,
) {
    HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BG)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavButton("◀", "Back", onBack)
        NavButton("⬤", "Home", onHome)
        NavButton("▦", "Apps", onRecents)
        NavButton("⬛", "Shot", onScreenshot)
    }
}

@Composable
private fun NavButton(icon: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
            Text(icon, color = NAV_ICON, fontSize = 22.sp)
        }
        Text(label, color = TEXT_MUTED, fontSize = 10.sp)
    }
}

@Composable
private fun SettingsPanel(
    sensitivity: Float,
    scrollSpeed: Float,
    naturalScroll: Boolean,
    onSensitivity: (Float) -> Unit,
    onScrollSpeed: (Float) -> Unit,
    onNaturalScroll: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onDismiss) {
                Text("Done", color = ACCENT, fontSize = 14.sp)
            }
        }

        SettingSlider("Cursor Speed", sensitivity, 0.5f..6f, "%.1f×", onSensitivity)
        SettingSlider("Scroll Speed", scrollSpeed, 0.5f..3f, "%.1f×", onScrollSpeed)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Natural Scroll", color = TEXT_DIM, fontSize = 14.sp)
                Text("Content follows finger direction", color = TEXT_MUTED, fontSize = 11.sp)
            }
            Switch(
                checked = naturalScroll,
                onCheckedChange = onNaturalScroll,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ACCENT,
                    checkedTrackColor = ACCENT_DIM,
                    uncheckedThumbColor = TEXT_DIM,
                    uncheckedTrackColor = Color(0xFF263545),
                ),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Gesture Guide", color = TEXT_MUTED, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            GestureHint("1 finger drag", "Move cursor")
            GestureHint("1 finger tap", "Left click")
            GestureHint("1 finger double-tap", "Double click")
            GestureHint("1 finger long-press", "Right click")
            GestureHint("2 finger drag", "Scroll")
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TEXT_DIM, fontSize = 14.sp)
            Text(format.format(value), color = ACCENT, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = ACCENT,
                activeTrackColor = ACCENT_DIM,
                inactiveTrackColor = Color(0xFF263545),
            ),
        )
    }
}

@Composable
private fun GestureHint(gesture: String, action: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(gesture, color = TEXT_MUTED, fontSize = 12.sp)
        Text(action, color = TEXT_DIM, fontSize = 12.sp)
    }
}
