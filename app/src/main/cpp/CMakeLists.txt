cmake_minimum_required(VERSION 3.22.1)
project("artouchpad")

# Build libartouchpad.so as a shared library loaded by UinputNative at runtime.
# uinput_jni.cpp provides JNI bindings for /dev/uinput ioctl access — necessary
# because Android 16 removed the generic Os.ioctl variant from the Kotlin API.
add_library(artouchpad SHARED uinput_jni.cpp)

# android: NDK logging support (needed for __android_log_print).
# log:     links liblog so LOGI/LOGE macros resolve at link time.
target_link_libraries(artouchpad android log)
