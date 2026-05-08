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

import android.provider.Settings
import android.util.Log
import android.view.KeyEvent as AKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgratz.artouchpad.DisplayInfo
import com.pgratz.artouchpad.TouchMode
import com.pgratz.artouchpad.TouchpadViewModel
import com.pgratz.artouchpad.VirtualKey
import com.pgratz.artouchpad.VirtualKeyboardMode
import kotlin.math.abs
import kotlin.math.sqrt


private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF1A2332)
private val SURFACE_DISABLED = Color(0xFF111820)
private val ACCENT = Color(0xFF4FC3F7)
private val ACCENT_DIM = Color(0xFF1A4A6A)
private val TEXT = Color(0xFFE0E0E0)
private val TEXT_DIM = Color(0xFF90A4AE)
private val TEXT_MUTED = Color(0xFF546E7A)
private val NAV_ICON = Color(0xFFB0BEC5)
private val KanaKeyHeight = 62.dp
private val KeyboardKeyHeight = 50.dp
private val EditingKeyHeight = 54.dp

private const val MOVE_THRESHOLD = 5f
private const val TAP_MAX_MS = 220L
private const val DOUBLE_TAP_WINDOW_MS = 300L
private const val TAP_DRAG_HOLD_MS = 140L
private const val GESTURE_TAG = "TouchpadGesture"

// Root composable. Collects ViewModel state and renders either SettingsPanel (when
// showSettings is true) or the main layout: StatusBar → TouchpadSurface → optional
// virtual keyboard panel → NavigationBar.
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
            serviceEnabled = state.isServiceEnabled,
            targetDisplay = state.targetDisplay,
            touchMode = state.touchMode,
            onSettingsClick = viewModel::toggleSettings,
            onGrantShizuku = viewModel::requestShizukuPermission,
            onConnectMouse = { viewModel.mouse.bind() },
            onEnableService = {
                context.startActivity(
                    android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )

        if (state.showSettings) {
            SettingsPanel(
                sensitivity = state.sensitivity,
                scrollSpeed = state.scrollSpeed,
                naturalScroll = state.naturalScroll,
                allDisplays = state.allDisplays,
                targetDisplay = state.targetDisplay,
                onSensitivity = viewModel::setSensitivity,
                onScrollSpeed = viewModel::setScrollSpeed,
                onNaturalScroll = viewModel::setNaturalScroll,
                onDismiss = viewModel::toggleSettings,
            )
        } else {
            TouchpadSurface(
                modifier = Modifier.weight(1f),
                enabled = state.mouseReady,
                onMoveCursor = viewModel::moveCursor,
                onClick = { viewModel.performClick() },
                onDoubleClick = { viewModel.performDoubleClick() },
                onRightClick = { viewModel.performRightClick() },
                onScroll = viewModel::performScroll,
                onPinch = viewModel::pinchZoom,
                onTouchModeChanged = viewModel::setTouchMode,
                onSelectStart = viewModel::startSelectDrag,
                onSelectEnd = viewModel::endSelectDrag,
            )
            if (state.showKeyboard) {
                VirtualKeyboardPanel(
                    mode = state.keyboardMode,
                    onModeChange = viewModel::setKeyboardMode,
                    onKey = viewModel::pressVirtualKey,
                    onChar = viewModel::sendRomajiKey,
                    onText = viewModel::sendRomajiSequence,
                    onShiftLatchChanged = viewModel::setKeyboardShiftDown,
                    onDismiss = viewModel::toggleKeyboard,
                )
            }
            NavigationBar(
                keyboardActive = state.showKeyboard,
                onBack         = { viewModel.pressKey(AKeyEvent.KEYCODE_BACK) },
                onHome         = { viewModel.pressKey(AKeyEvent.KEYCODE_HOME) },
                onRecents      = { viewModel.pressKey(AKeyEvent.KEYCODE_APP_SWITCH) },
                onToggleKeyboard = viewModel::toggleKeyboard,
            )
        }
    }
}

// Top status bar showing the app title, three status dots (Mouse/Display/Nav),
// the current touch mode indicator, and contextual action buttons for each
// unmet setup step (grant Shizuku → connect mouse → enable accessibility service).
@Composable
private fun StatusBar(
    shizukuAvailable: Boolean,
    shizukuPermission: Boolean,
    mouseReady: Boolean,
    serviceEnabled: Boolean,
    targetDisplay: DisplayInfo?,
    touchMode: TouchMode,
    onSettingsClick: () -> Unit,
    onGrantShizuku: () -> Unit,
    onConnectMouse: () -> Unit,
    onEnableService: () -> Unit,
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
                    StatusDot(serviceEnabled, "Nav")
                    val modeLabel = when (touchMode) {
                        TouchMode.SCROLL -> "↕ scroll"
                        TouchMode.SELECT -> "⊹ select"
                        TouchMode.CURSOR -> "⊹ cursor"
                        TouchMode.IDLE   -> null
                    }
                    modeLabel?.let { Text(it, color = ACCENT, fontSize = 11.sp) }
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
                    !serviceEnabled ->
                        TextButton(onClick = onEnableService, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Enable Nav", color = Color(0xFFFFB300), fontSize = 12.sp)
                        }
                }
                IconButton(onClick = onSettingsClick) {
                    Text("⚙", color = TEXT_DIM, fontSize = 22.sp)
                }
            }
        }

        HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    }
}

// Small colored circle (green = active, gray = inactive) followed by a text label.
// Used in StatusBar to show Mouse/Display/Nav readiness at a glance.
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

// The main touch surface. Intercepts raw pointer events with a single pointerInput handler:
//   1 finger: tap (click), double-tap, double-tap+drag (left-button drag), or drag (cursor move).
//   2 fingers: tap (right-click), pinch (spread > translate -> zoom), or drag (scroll).
// Renders a dot grid and live touch point indicators on a Canvas.
@Composable
private fun TouchpadSurface(
    modifier: Modifier,
    enabled: Boolean,
    onMoveCursor: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    onTouchModeChanged: (TouchMode) -> Unit,
    onSelectStart: () -> Unit,
    onSelectEnd: () -> Unit,
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
                    var startPosition = Offset.Zero
                    var downTime = 0L
                    var didMove = false
                    var lastCleanTapUpTime = 0L
                    var tapDragCandidate = false
                    var isDragMode = false
                    var maxPointers = 0
                    var twoFingerDidMove = false
                    var twoFingerAccumX = 0f
                    var twoFingerAccumY = 0f
                    var twoFingerAccumSpan = 0f
                    var activeTouch = false

                    fun releaseDrag(reason: String) {
                        if (isDragMode) {
                            Log.d(GESTURE_TAG, "mouse up / select end: $reason")
                            onSelectEnd()
                            isDragMode = false
                        }
                    }

                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val now = System.currentTimeMillis()
                                val pressed = event.changes.filter { it.pressed }
                                val justPressed = event.changes.filter { it.pressed && !it.previousPressed }
                                val justReleased = event.changes.filter { !it.pressed && it.previousPressed }

                                touchPoints = pressed.map { it.position }
                                if (pressed.isNotEmpty()) {
                                    maxPointers = maxOf(maxPointers, pressed.size)
                                }

                                if (justPressed.isNotEmpty() && pressed.size == 1) {
                                    downTime = now
                                    didMove = false
                                    activeTouch = true
                                    maxPointers = 1
                                    twoFingerDidMove = false
                                    twoFingerAccumX = 0f
                                    twoFingerAccumY = 0f
                                    twoFingerAccumSpan = 0f
                                    isDragMode = false
                                    startPosition = pressed.first().position
                                    tapDragCandidate = now - lastCleanTapUpTime <= DOUBLE_TAP_WINDOW_MS
                                    Log.d(
                                        GESTURE_TAG,
                                        "one-finger down: local tracking only, tapDragCandidate=$tapDragCandidate",
                                    )
                                    lastPositions = pressed.associate { it.id to it.position }
                                } else if (justPressed.isNotEmpty() && pressed.size == 2) {
                                    releaseDrag("pointer-count changed to two fingers")
                                    if (lastPositions.isEmpty()) downTime = now
                                    activeTouch = true
                                    tapDragCandidate = false
                                    lastCleanTapUpTime = 0L
                                    twoFingerDidMove = false
                                    twoFingerAccumX = 0f
                                    twoFingerAccumY = 0f
                                    twoFingerAccumSpan = 0f
                                    lastPositions = pressed.associate { it.id to it.position }
                                }

                                when (pressed.size) {
                                    1 -> {
                                        val p = pressed.first()
                                        val last = lastPositions[p.id]
                                        if (last != null) {
                                            val dx = p.position.x - last.x
                                            val dy = p.position.y - last.y
                                            val totalDx = p.position.x - startPosition.x
                                            val totalDy = p.position.y - startPosition.y
                                            val totalMove = sqrt(totalDx * totalDx + totalDy * totalDy)
                                            val moved = totalMove > MOVE_THRESHOLD

                                            // Ignore movement from a remaining finger after a two-finger gesture.
                                            if (maxPointers == 1 && !didMove && moved) {
                                                didMove = true
                                                val heldForTapDrag = now - downTime >= TAP_DRAG_HOLD_MS
                                                if (tapDragCandidate && heldForTapDrag) {
                                                    isDragMode = true
                                                    lastCleanTapUpTime = 0L
                                                    Log.d(
                                                        GESTURE_TAG,
                                                        "mouse down / select start: double-tap-hold drag recognized before first drag move",
                                                    )
                                                    onSelectStart()
                                                } else {
                                                    tapDragCandidate = false
                                                    lastCleanTapUpTime = 0L
                                                    Log.d(
                                                        GESTURE_TAG,
                                                        "cursor move: one-finger drag recognized totalMove=$totalMove heldMs=${now - downTime}",
                                                    )
                                                }
                                            }

                                            if (maxPointers == 1 && (isDragMode || didMove)) {
                                                Log.d(
                                                    GESTURE_TAG,
                                                    "cursor move: branch=${if (isDragMode) "tap-drag" else "one-finger drag"} dx=$dx dy=$dy",
                                                )
                                                onMoveCursor(dx, dy)
                                                if (isDragMode) {
                                                    onTouchModeChanged(TouchMode.SELECT)
                                                } else {
                                                    onTouchModeChanged(TouchMode.CURSOR)
                                                }
                                            }
                                        }
                                        lastPositions = pressed.associate { it.id to it.position }
                                        p.consume()
                                    }
                                    2 -> {
                                        releaseDrag("two-finger gesture took over")

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
                                                val pdx = p1prev.x - p0prev.x; val pdy = p1prev.y - p0prev.y
                                                val cdx = p1curr.x - p0curr.x; val cdy = p1curr.y - p0curr.y
                                                val prevSpan = sqrt(pdx * pdx + pdy * pdy)
                                                val currSpan = sqrt(cdx * cdx + cdy * cdy)
                                                val dSpan = currSpan - prevSpan

                                                twoFingerAccumX += dx
                                                twoFingerAccumY += dy
                                                twoFingerAccumSpan += dSpan
                                                val translated = sqrt(twoFingerAccumX * twoFingerAccumX + twoFingerAccumY * twoFingerAccumY)
                                                val movementStarted = translated > MOVE_THRESHOLD || abs(twoFingerAccumSpan) > MOVE_THRESHOLD
                                                if (movementStarted) {
                                                    twoFingerDidMove = true
                                                }

                                                if (twoFingerDidMove) {
                                                    // When fingers spread/contract more than they translate, it's a pinch.
                                                    // Otherwise treat as scroll, including horizontal scroll.
                                                    if (abs(dSpan) > abs(dx) + abs(dy)) {
                                                        if (dSpan != 0f) {
                                                            onPinch(dSpan)
                                                        }
                                                    } else if (dx != 0f || dy != 0f) {
                                                        Log.d(GESTURE_TAG, "scroll: two-finger drag dx=$dx dy=$dy")
                                                        onScroll(dx, dy)
                                                        onTouchModeChanged(TouchMode.SCROLL)
                                                    }
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
                                        isDragMode -> releaseDrag("finger released after tap-drag")
                                        maxPointers == 2 && !twoFingerDidMove && duration < TAP_MAX_MS -> {
                                            Log.d(GESTURE_TAG, "right click: clean two-finger tap durationMs=$duration")
                                            onRightClick()
                                            lastCleanTapUpTime = 0L
                                        }
                                        maxPointers == 1 && !didMove && duration < TAP_MAX_MS -> {
                                            if (tapDragCandidate) {
                                                Log.d(GESTURE_TAG, "double click: clean second tap sends second left click")
                                                onClick()
                                                lastCleanTapUpTime = 0L
                                            } else {
                                                Log.d(GESTURE_TAG, "click: clean one-finger tap durationMs=$duration")
                                                onClick()
                                                lastCleanTapUpTime = now
                                            }
                                        }
                                        else -> {
                                            if (maxPointers == 1 && !didMove) {
                                                Log.d(GESTURE_TAG, "one-finger hold released without click durationMs=$duration")
                                            }
                                            lastCleanTapUpTime = 0L
                                        }
                                    }
                                    activeTouch = false
                                    tapDragCandidate = false
                                    isDragMode = false
                                    maxPointers = 0
                                    twoFingerDidMove = false
                                    twoFingerAccumX = 0f
                                    twoFingerAccumY = 0f
                                    twoFingerAccumSpan = 0f
                                    lastPositions = emptyMap()
                                    touchPoints = emptyList()
                                }
                            }
                        }
                    } finally {
                        if (activeTouch) {
                            releaseDrag("pointer input cancelled")
                            onTouchModeChanged(TouchMode.IDLE)
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

// Keyboard panel host. All modes are app-rendered key surfaces and do not use a phone-side IME.
@Composable
private fun VirtualKeyboardPanel(
    mode: VirtualKeyboardMode,
    onModeChange: (VirtualKeyboardMode) -> Unit,
    onKey: (VirtualKey) -> Unit,
    onChar: (Char, Boolean) -> Unit,
    onText: (String) -> Unit,
    onShiftLatchChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (mode) {
        VirtualKeyboardMode.QWERTY -> QwertyKeyboard(
            mode = mode,
            onModeChange = onModeChange,
            onKey = onKey,
            onChar = onChar,
        )
        VirtualKeyboardMode.KANA -> KanaKeyboard(
            mode = mode,
            onModeChange = onModeChange,
            onChar = onChar,
            onText = onText,
            onKey = onKey,
        )
        VirtualKeyboardMode.SYMBOLS -> SymbolKeyboard(
            mode = mode,
            onModeChange = onModeChange,
            onChar = onChar,
            onKey = onKey,
        )
        VirtualKeyboardMode.EDITING -> EditingKeyboard(
            mode = mode,
            onModeChange = onModeChange,
            onKey = onKey,
            onShiftLatchChanged = onShiftLatchChanged,
        )
    }
}

@Composable
private fun QwertyKeyboard(
    mode: VirtualKeyboardMode,
    onModeChange: (VirtualKeyboardMode) -> Unit,
    onKey: (VirtualKey) -> Unit,
    onChar: (Char, Boolean) -> Unit,
) {
    var shift by remember { mutableStateOf(false) }
    val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        KeyboardRow(chars = "1234567890", onChar = { ch -> onChar(ch, false) })

        letterRows.take(2).forEach { row ->
            KeyboardRow(
                chars = row,
                shifted = shift,
                onChar = { ch ->
                    val output = if (shift) ch.uppercaseChar() else ch.lowercaseChar()
                    onChar(output, false)
                    shift = false
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            KeyboardActionKey(
                label = "⇧",
                modifier = Modifier.weight(1.25f),
                selected = shift,
                onClick = { shift = !shift },
            )
            letterRows[2].forEach { ch ->
                KeyboardLetterKey(
                    label = if (shift) ch.uppercaseChar().toString() else ch.toString(),
                    modifier = Modifier.weight(1f),
                ) {
                    val output = if (shift) ch.uppercaseChar() else ch.lowercaseChar()
                    onChar(output, false)
                    shift = false
                }
            }
            KeyboardActionKey(
                label = "⌫",
                modifier = Modifier.weight(1.5f),
                onClick = { onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DEL)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ModeCycleKey(mode, Modifier.weight(1.05f), onModeChange = onModeChange)
            KeyboardActionKey("◂I▸", Modifier.weight(1.05f)) { onModeChange(VirtualKeyboardMode.EDITING) }
            KeyboardActionKey(",", modifier = Modifier.weight(0.75f)) { onChar(',', false) }
            KeyboardActionKey(
                "⎵",
                modifier = Modifier.weight(3f),
            ) { onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_SPACE)) }
            KeyboardActionKey(".", modifier = Modifier.weight(0.75f)) { onChar('.', false) }
            KeyboardActionKey("←", Modifier.weight(0.75f)) { onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DPAD_LEFT)) }
            KeyboardActionKey("→", Modifier.weight(0.75f)) { onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DPAD_RIGHT)) }
            KeyboardActionKey(
                "↲",
                modifier = Modifier.weight(1f),
            ) { onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_ENTER)) }
        }
    }
}

@Composable
private fun SymbolKeyboard(
    mode: VirtualKeyboardMode,
    onModeChange: (VirtualKeyboardMode) -> Unit,
    onChar: (Char, Boolean) -> Unit,
    onKey: (VirtualKey) -> Unit,
) {
    HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        KeyboardRow(chars = "1234567890") { onChar(it, false) }
        SymbolRow(listOf("-", "/", ":", ";", "(", ")", "¥", "&", "@", "\""), onChar)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(".", ",", "?", "!", "'", "#", "%", "+", "=").forEach { label ->
                KeyboardLetterKey(label, Modifier.weight(1f)) { onChar(label.first(), false) }
            }
            KeyboardActionKey("⌫", Modifier.weight(1.5f)) {
                onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DEL))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ModeCycleKey(mode, Modifier.weight(1.05f), onModeChange = onModeChange)
            KeyboardActionKey("◂I▸", Modifier.weight(1.05f)) { onModeChange(VirtualKeyboardMode.EDITING) }
            KeyboardActionKey("ABC", Modifier.weight(1f)) { onModeChange(VirtualKeyboardMode.QWERTY) }
            KeyboardActionKey("⎵", Modifier.weight(2.6f)) {
                onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_SPACE))
            }
            KeyboardActionKey(".", Modifier.weight(0.75f)) { onChar('.', false) }
            KeyboardActionKey("←", Modifier.weight(0.75f)) { onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DPAD_LEFT)) }
            KeyboardActionKey("→", Modifier.weight(0.75f)) { onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DPAD_RIGHT)) }
            KeyboardActionKey("↲", Modifier.weight(1f)) {
                onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_ENTER))
            }
        }
    }
}

@Composable
private fun EditingKeyboard(
    mode: VirtualKeyboardMode,
    onModeChange: (VirtualKeyboardMode) -> Unit,
    onKey: (VirtualKey) -> Unit,
    onShiftLatchChanged: (Boolean) -> Unit,
) {
    var shiftLatched by rememberSaveable { mutableStateOf(false) }
    fun key(keyCode: Int, forceCtrl: Boolean = false) {
        onKey(
            VirtualKey.AndroidKeyCode(
                keyCode,
                withCtrl = forceCtrl,
            ),
        )
    }
    @Composable
    fun EditKey(label: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
        KeyboardActionKey(label, modifier, selected = selected, height = EditingKeyHeight, onClick = onClick)
    }

    HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            EditKey("⊡", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_A, forceCtrl = true) }
            EditKey("↑", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_DPAD_UP) }
            EditKey("⌫", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_DEL) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            EditKey("←", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_DPAD_LEFT) }
            EditKey("⇧", Modifier.weight(1f), selected = shiftLatched) {
                val next = !shiftLatched
                shiftLatched = next
                onShiftLatchChanged(next)
            }
            EditKey("→", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_DPAD_RIGHT) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            EditKey("⧉", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_C, forceCtrl = true) }
            EditKey("↓", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_DPAD_DOWN) }
            EditKey("⎘", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_V, forceCtrl = true) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ModeCycleKey(mode, Modifier.weight(1f), height = EditingKeyHeight, onModeChange)
            EditKey("⎵", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_SPACE) }
            EditKey("↲", Modifier.weight(1f)) { key(AKeyEvent.KEYCODE_ENTER) }
        }
    }
}

@Composable
private fun KanaKeyboard(
    mode: VirtualKeyboardMode,
    onModeChange: (VirtualKeyboardMode) -> Unit,
    onChar: (Char, Boolean) -> Unit,
    onText: (String) -> Unit,
    onKey: (VirtualKey) -> Unit,
) {
    var lastKana by remember { mutableStateOf<KanaOutput?>(null) }

    fun sendKana(key: KanaKey, direction: FlickDirection) {
        val output = key.output(direction) ?: return
        onText(output.romaji)
        lastKana = output
    }

    fun applyModifier(modifier: KanaModifier) {
        val previous = lastKana ?: return
        val replacement = previous.modified(modifier) ?: return
        // Japanese IMEs usually keep the previous kana in composition; one Backspace
        // removes that composed kana before the replacement romaji is typed.
        onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DEL))
        onText(replacement.romaji)
        lastKana = replacement
    }

    HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyboardActionKey("↶", Modifier.weight(1f), height = KanaKeyHeight) {
                onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_Z, withCtrl = true))
            }
            listOf(KanaKey.A, KanaKey.KA, KanaKey.SA).forEach { key ->
                KanaFlickKey(key, Modifier.weight(1f)) { sendKana(key, it) }
            }
            KeyboardActionKey("⌫", Modifier.weight(1f), height = KanaKeyHeight) {
                onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DEL))
                lastKana = null
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyboardActionKey("←", Modifier.weight(1f), height = KanaKeyHeight) {
                onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DPAD_LEFT))
            }
            listOf(KanaKey.TA, KanaKey.NA, KanaKey.HA).forEach { key ->
                KanaFlickKey(key, Modifier.weight(1f)) { sendKana(key, it) }
            }
            KeyboardActionKey("→", Modifier.weight(1f), height = KanaKeyHeight) {
                onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_DPAD_RIGHT))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyboardActionKey("◂I▸", Modifier.weight(1f), height = KanaKeyHeight) {
                onModeChange(VirtualKeyboardMode.EDITING)
            }
            listOf(KanaKey.MA, KanaKey.YA, KanaKey.RA).forEach { key ->
                KanaFlickKey(key, Modifier.weight(1f)) { sendKana(key, it) }
            }
            KeyboardActionKey("⎵", Modifier.weight(1f), height = KanaKeyHeight) {
                onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_SPACE))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ModeCycleKey(mode, Modifier.weight(1f), height = KanaKeyHeight, onModeChange)
            KeyboardActionKey("小゛゜", Modifier.weight(1f), height = KanaKeyHeight) {
                applyModifier(lastKana?.nextModifier() ?: KanaModifier.DAKUTEN)
            }
            KanaFlickKey(KanaKey.WA, Modifier.weight(1f)) { sendKana(KanaKey.WA, it) }
            JapanesePunctuationKey(Modifier.weight(1f), onChar, onText)
            KeyboardActionKey("↲", Modifier.weight(1f), height = KanaKeyHeight) {
                onKey(VirtualKey.AndroidKeyCode(AKeyEvent.KEYCODE_ENTER))
            }
        }
    }
}

@Composable
private fun KeyboardRow(
    chars: String,
    shifted: Boolean = false,
    keyHeight: Dp = KeyboardKeyHeight,
    onChar: (Char) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        chars.forEach { ch ->
            val out = if (shifted) ch.uppercaseChar() else ch
            KeyboardLetterKey(
                label = out.toString(),
                modifier = Modifier.weight(1f),
                height = keyHeight,
                onClick = { onChar(out) },
            )
        }
    }
}

@Composable
private fun KeyboardLetterKey(
    label: String,
    modifier: Modifier,
    height: Dp = KeyboardKeyHeight,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(7.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF263545),
            contentColor = TEXT,
        ),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun KeyboardActionKey(
    label: String,
    modifier: Modifier,
    selected: Boolean = false,
    height: Dp = KeyboardKeyHeight,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(7.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) ACCENT_DIM else Color(0xFF1F2C3A),
            contentColor = if (selected) ACCENT else TEXT_DIM,
        ),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ModeKey(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    KeyboardActionKey(label = label, modifier = modifier, selected = selected, onClick = onClick)
}

@Composable
private fun ModeCycleKey(
    mode: VirtualKeyboardMode,
    modifier: Modifier,
    height: Dp = KeyboardKeyHeight,
    onModeChange: (VirtualKeyboardMode) -> Unit,
) {
    val (label, next) = when (mode) {
        VirtualKeyboardMode.KANA -> "あa1" to VirtualKeyboardMode.QWERTY
        VirtualKeyboardMode.QWERTY -> "あa1" to VirtualKeyboardMode.SYMBOLS
        VirtualKeyboardMode.SYMBOLS -> "あa1" to VirtualKeyboardMode.KANA
        VirtualKeyboardMode.EDITING -> "あa1" to VirtualKeyboardMode.KANA
    }
    KeyboardActionKey(label, modifier, height = height) { onModeChange(next) }
}

@Composable
private fun SymbolRow(symbols: List<String>, onChar: (Char, Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        symbols.forEach { label ->
            KeyboardLetterKey(label, Modifier.weight(1f)) { onChar(label.first(), false) }
        }
    }
}

private enum class FlickDirection { CENTER, LEFT, UP, RIGHT, DOWN }
private enum class KanaModifier { DAKUTEN, HANDAKUTEN, SMALL }

private data class KanaOutput(val base: String, val romaji: String) {
    fun nextModifier(): KanaModifier? = when {
        base in listOf("ka", "ki", "ku", "ke", "ko") -> KanaModifier.DAKUTEN
        base in listOf("sa", "shi", "su", "se", "so") -> KanaModifier.DAKUTEN
        base in listOf("ta", "chi", "tsu", "te", "to") -> if (base == "tsu") KanaModifier.SMALL else KanaModifier.DAKUTEN
        base in listOf("ha", "hi", "fu", "he", "ho") -> KanaModifier.DAKUTEN
        base in listOf("ba", "bi", "bu", "be", "bo") -> KanaModifier.HANDAKUTEN
        base in listOf("ya", "yu", "yo") -> KanaModifier.SMALL
        else -> null
    }

    fun modified(modifier: KanaModifier): KanaOutput? {
        val replacement = when (modifier) {
            KanaModifier.DAKUTEN -> when (base) {
                "ka" -> "ga"; "ki" -> "gi"; "ku" -> "gu"; "ke" -> "ge"; "ko" -> "go"
                "sa" -> "za"; "shi" -> "ji"; "su" -> "zu"; "se" -> "ze"; "so" -> "zo"
                "ta" -> "da"; "chi" -> "ji"; "tsu" -> "zu"; "te" -> "de"; "to" -> "do"
                "ha" -> "ba"; "hi" -> "bi"; "fu" -> "bu"; "he" -> "be"; "ho" -> "bo"
                else -> null
            }
            KanaModifier.HANDAKUTEN -> when (base) {
                "ha" -> "pa"; "hi" -> "pi"; "fu" -> "pu"; "he" -> "pe"; "ho" -> "po"
                "ba" -> "pa"; "bi" -> "pi"; "bu" -> "pu"; "be" -> "pe"; "bo" -> "po"
                else -> null
            }
            KanaModifier.SMALL -> when (base) {
                "ya" -> "xya"; "yu" -> "xyu"; "yo" -> "xyo"; "tsu" -> "xtu"
                else -> null
            }
        } ?: return null
        return KanaOutput(base = replacement, romaji = replacement)
    }
}

private enum class KanaKey(
    val label: String,
    private val center: String?,
    private val left: String?,
    private val up: String?,
    private val right: String?,
    private val down: String?,
) {
    A("あ", "a", "i", "u", "e", "o"),
    KA("か", "ka", "ki", "ku", "ke", "ko"),
    SA("さ", "sa", "shi", "su", "se", "so"),
    TA("た", "ta", "chi", "tsu", "te", "to"),
    NA("な", "na", "ni", "nu", "ne", "no"),
    HA("は", "ha", "hi", "fu", "he", "ho"),
    MA("ま", "ma", "mi", "mu", "me", "mo"),
    YA("や", "ya", null, "yu", null, "yo"),
    RA("ら", "ra", "ri", "ru", "re", "ro"),
    WA("わ", "wa", "wo", "nn", "-", null);

    fun output(direction: FlickDirection): KanaOutput? {
        val base = when (direction) {
            FlickDirection.CENTER -> center
            FlickDirection.LEFT -> left
            FlickDirection.UP -> up
            FlickDirection.RIGHT -> right
            FlickDirection.DOWN -> down
        } ?: return null
        return KanaOutput(base = base, romaji = base)
    }
}

@Composable
private fun KanaFlickKey(
    kanaKey: KanaKey,
    modifier: Modifier,
    onFlick: (FlickDirection) -> Unit,
) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    val direction = remember(drag) { drag.toFlickDirection() }
    Box(
        modifier = modifier
            .height(KanaKeyHeight)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF263545))
            .pointerInput(kanaKey) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        drag = Offset.Zero
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            if (change.pressed) {
                                drag += change.positionChange()
                                change.consume()
                            } else {
                                change.consume()
                                onFlick(drag.toFlickDirection())
                                drag = Offset.Zero
                                break
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (direction == FlickDirection.CENTER) kanaKey.label else "${kanaKey.label} ${direction.preview}",
            color = TEXT,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun JapanesePunctuationKey(
    modifier: Modifier,
    onChar: (Char, Boolean) -> Unit,
    onText: (String) -> Unit,
) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    val direction = remember(drag) { drag.toFlickDirection() }
    fun sendPunctuation(dir: FlickDirection) = when (dir) {
        FlickDirection.CENTER -> onChar(',', false)
        FlickDirection.LEFT -> onChar('.', false)
        FlickDirection.UP -> onChar('?', false)
        FlickDirection.RIGHT -> onChar('!', false)
        FlickDirection.DOWN -> onText("...")
    }
    Box(
        modifier = modifier
            .height(KanaKeyHeight)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF263545))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        drag = Offset.Zero
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            if (change.pressed) {
                                drag += change.positionChange()
                                change.consume()
                            } else {
                                change.consume()
                                sendPunctuation(drag.toFlickDirection())
                                drag = Offset.Zero
                                break
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (direction == FlickDirection.CENTER) "。、？！" else "。、？！ ${direction.preview}",
            color = TEXT,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private val FlickDirection.preview: String get() = when (this) {
    FlickDirection.CENTER -> ""
    FlickDirection.LEFT -> "←"
    FlickDirection.UP -> "↑"
    FlickDirection.RIGHT -> "→"
    FlickDirection.DOWN -> "↓"
}

private fun Offset.toFlickDirection(): FlickDirection {
    val threshold = 24f
    if (kotlin.math.sqrt(x * x + y * y) < threshold) return FlickDirection.CENTER
    return if (kotlin.math.abs(x) > kotlin.math.abs(y)) {
        if (x < 0f) FlickDirection.LEFT else FlickDirection.RIGHT
    } else {
        if (y < 0f) FlickDirection.UP else FlickDirection.DOWN
    }
}

// Bottom navigation row with four buttons: Back, Home, Apps (Recents), and keyboard toggle.
// The keyboard button is tinted accent when the proxy is active.
@Composable
private fun NavigationBar(
    keyboardActive: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onToggleKeyboard: () -> Unit,
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
        NavButton("⌨", "Keys", onToggleKeyboard,
            tint = if (keyboardActive) ACCENT else NAV_ICON)
    }
}

// A centered icon + small label stacked in a column; tint defaults to NAV_ICON gray
// but can be overridden (e.g. ACCENT) to indicate an active state.
@Composable
private fun NavButton(icon: String, label: String, onClick: () -> Unit, tint: Color = NAV_ICON) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
            Text(icon, color = tint, fontSize = 22.sp)
        }
        Text(label, color = TEXT_MUTED, fontSize = 10.sp)
    }
}

// Full-screen settings overlay (shown instead of the touchpad when gear is tapped).
// Contains cursor/scroll speed sliders, natural scroll toggle, connected display list,
// and a gesture reference guide. Dismissed via the "Done" button.
@Composable
private fun SettingsPanel(
    sensitivity: Float,
    scrollSpeed: Float,
    naturalScroll: Boolean,
    allDisplays: List<DisplayInfo>,
    targetDisplay: DisplayInfo?,
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

        SettingSlider("Cursor Speed", sensitivity, 0.4f..2.0f, "%.1f×", onSensitivity)
        SettingSlider("Scroll Speed", scrollSpeed, 0.3f..1.3f, "%.1f×", onScrollSpeed)

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

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Displays", color = TEXT_MUTED, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (allDisplays.isEmpty()) {
                Text("No displays detected", color = Color(0xFFFF7043), fontSize = 12.sp)
            } else {
                allDisplays.forEach { d ->
                    val isTarget = d.id == targetDisplay?.id
                    Text(
                        if (isTarget) "▶ ${d.name}  ${d.width}×${d.height}" else "  ${d.name}  ${d.width}×${d.height}",
                        color = if (isTarget) ACCENT else TEXT_MUTED,
                        fontSize = 12.sp,
                    )
                }
            }
            if (allDisplays.size == 1) {
                Text(
                    "Only 1 display — glasses may be in mirror mode. Switch to Desktop/Extended.",
                    color = Color(0xFFFFB300), fontSize = 11.sp,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Gesture Guide", color = TEXT_MUTED, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            GestureHint("1 finger drag", "Move cursor")
            GestureHint("1 finger tap", "Left click")
            GestureHint("1 finger double-tap", "Double click")
            GestureHint("1 finger double-tap + drag", "Drag")
            GestureHint("2 finger tap", "Right click")
            GestureHint("2 finger drag", "Scroll vertically/horizontally")
            GestureHint("2 finger pinch", "Zoom page")
        }
    }
}

// A labeled Slider with the formatted current value displayed to its right.
// label: display name; value/range: current value and allowed bounds; format: printf string
// for the value (e.g. "%.1f×"); onChange: callback with the new float value.
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

// A single row in the gesture guide: gesture description on the left, resulting action on the right.
@Composable
private fun GestureHint(gesture: String, action: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(gesture, color = TEXT_MUTED, fontSize = 12.sp)
        Text(action, color = TEXT_DIM, fontSize = 12.sp)
    }
}
