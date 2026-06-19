cmake_minimum_required(VERSION 3.10)

project("carriex")

add_library(
    carriex_jni
    SHARED
    native-lib.cpp
)
add_library(sodium SHARED IMPORTED)

set_target_properties(sodium PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsodium.so
)

find_library(log-lib log)

target_link_libraries(
    carriex_jni
    sodium
    ${log-lib}
)

include_directories(
        ${CMAKE_SOURCE_DIR}/libsodium/include
)

