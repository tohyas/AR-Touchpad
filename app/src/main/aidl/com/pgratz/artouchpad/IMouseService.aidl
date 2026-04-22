// IMouseService.aidl
package com.pgratz.artouchpad;

interface IMouseService {
    void setDisplay(int displayId, int width, int height) = 1;
    void moveMouse(float dx, float dy) = 2;
    void click(float x, float y, int button) = 3;
    void scroll(float dx, float dy) = 4;
    void destroy() = 16777114;
}
