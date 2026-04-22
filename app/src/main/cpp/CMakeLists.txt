cmake_minimum_required(VERSION 3.22.1)
project("artouchpad")

add_library(artouchpad SHARED uinput_jni.cpp)
target_link_libraries(artouchpad android log)
