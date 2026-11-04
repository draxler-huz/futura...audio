cmake_minimum_required(VERSION 3.22.1)

project(futura_engine LANGUAGES CXX)

# Oboe is built from the vendored source so its .cpp files are compiled
# and linked, not just its headers. Built static into the engine library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(src/main/cpp/third_party/oboe oboe EXCLUDE_FROM_ALL)

add_library(futura_engine SHARED
    src/main/cpp/audio/AudioOutput.cpp
    src/main/cpp/audio/pcm_parser.cpp
    src/main/cpp/core/Engine.cpp
    src/main/cpp/jni/JniBridge.cpp
    src/main/cpp/jni/NativeEngineJni.cpp
    src/main/cpp/jni/DspJni.cpp
    src/main/cpp/jni/UsbAudioJni.cpp
    src/main/cpp/jni/MlJni.cpp
    src/main/cpp/jni/CrashHandlerJni.cpp
)

target_compile_features(futura_engine PRIVATE cxx_std_17)
target_include_directories(futura_engine PRIVATE
    src/main/cpp
    src/main/cpp/third_party/oboe/include
)
target_compile_options(futura_engine PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

find_library(log-lib log)

target_link_libraries(futura_engine
    oboe
    ${log-lib}
)
