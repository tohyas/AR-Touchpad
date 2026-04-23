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

package com.pgratz.artouchpad;

interface IMouseService {
    // Tells the service which display to target for cursor movement and key injection,
    // and provides its pixel dimensions so the cursor can be clamped correctly.
    void setDisplay(int displayId, int width, int height) = 1;

    // Moves the virtual mouse by (dx, dy) pixels relative to its current position.
    // Sub-pixel remainders are accumulated across calls so slow movements don't stall.
    void moveMouse(float dx, float dy) = 2;

    // Presses and releases a mouse button at the current cursor position.
    // button: MotionEvent.BUTTON_PRIMARY (left) or BUTTON_SECONDARY (right).
    void click(float x, float y, int button) = 3;

    // Scrolls the content under the cursor by (dx, dy) finger-pixel deltas.
    // dy is converted to wheel detents; dx is reserved for horizontal scroll.
    void scroll(float dx, float dy) = 4;

    // Injects a key press+release for the given Android keycode (e.g. KEYCODE_BACK = 4)
    // targeted at the focused window on the configured display via InputManagerGlobal.
    void pressKey(int androidKeycode) = 5;

    // Converts a text string to KeyEvents via KeyCharacterMap and injects them
    // to the focused window on the configured display.
    void typeText(String text) = 6;

    // Writes the system font_scale setting (clamped 0.85–1.5) via `settings put system`.
    // Shell uid has permission to write system settings without root.
    void setFontScale(float scale) = 7;

    // Closes the uinput file descriptor and marks the device not ready.
    void destroy() = 16777114;
}
