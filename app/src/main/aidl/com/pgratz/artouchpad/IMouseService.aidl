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
    void setDisplay(int displayId, int width, int height) = 1;
    void moveMouse(float dx, float dy) = 2;
    void click(float x, float y, int button) = 3;
    void scroll(float dx, float dy) = 4;
    void pressKey(int androidKeycode) = 5;
    void destroy() = 16777114;
}
