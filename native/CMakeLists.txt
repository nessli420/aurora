cmake_minimum_required(VERSION 3.25)
project(aurora_desktop LANGUAGES C CXX)

if(NOT MSVC OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "aurora desktop natives need msvc x64")
endif()

set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
foreach(config IN LISTS CMAKE_CONFIGURATION_TYPES)
    string(TOUPPER ${config} config)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${config} ${CMAKE_BINARY_DIR}/bin)
endforeach()
add_compile_options(/utf-8 /fp:precise)

find_package(JNI REQUIRED)

add_library(aurora_native SHARED
    src/jni_support.cpp
    src/audio/wasapi_devices.cpp
    src/audio/wasapi_stream.cpp
    src/audio/wasapi_jni.cpp
    src/media/smtc_jni.cpp
    src/system/system_jni.cpp
)
set_target_properties(aurora_native PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF)
target_include_directories(aurora_native PRIVATE src)
target_compile_definitions(aurora_native PRIVATE UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN _WIN32_WINNT=0x0A00)
target_compile_options(aurora_native PRIVATE /W4 /permissive- /EHsc /Zc:__cplusplus)
target_link_libraries(aurora_native PRIVATE JNI::JNI ole32 avrt ksuser dwmapi crypt32 shell32 runtimeobject)
