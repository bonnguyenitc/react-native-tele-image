cmake_minimum_required(VERSION 3.22.1)
project(teleimage)

# Build the JNI shared library
add_library(teleimage SHARED
        teleimage_jni.cpp)

# Link with Android NDK libraries — same as Telegram's CMakeLists
target_link_libraries(teleimage
        android      # AndroidBitmap_lockPixels
        jnigraphics  # android/bitmap.h
        log)
