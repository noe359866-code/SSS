# Cineflux · núcleo compartido (Android NDK / iOS Xcode)
cmake_minimum_required(VERSION 3.20)
project(cineflux_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

add_library(cineflux_core STATIC
    src/manifest.cpp
    src/profile.cpp
    src/stream.cpp
    src/ids.cpp
    src/skip.cpp
    src/catalog.cpp
    src/c_api.cpp
)
target_include_directories(cineflux_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_options(cineflux_core PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(cineflux_core PRIVATE CINEFLUX_VERSION="1.0.0")
