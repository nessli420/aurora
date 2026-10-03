cmake_minimum_required(VERSION 3.20)
project(aurora_alsa LANGUAGES CXX)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
    message(FATAL_ERROR "the alsa output library only builds on linux")
endif()

find_package(JNI REQUIRED)
find_package(ALSA REQUIRED)
find_package(Threads REQUIRED)

set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)

add_library(aurora_alsa SHARED
    src/alsa_devices.cpp
    src/alsa_stream.cpp
    src/alsa_jni.cpp
)
set_target_properties(aurora_alsa PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF CXX_VISIBILITY_PRESET hidden)
target_include_directories(aurora_alsa PRIVATE src ${JNI_INCLUDE_DIRS})
target_compile_options(aurora_alsa PRIVATE -Wall -Wextra -O2)
target_link_libraries(aurora_alsa PRIVATE ALSA::ALSA Threads::Threads)
target_link_options(aurora_alsa PRIVATE -Wl,--no-undefined -Wl,--as-needed)
