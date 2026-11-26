cmake_minimum_required(VERSION 3.22.1)
project(cineflux_player LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Núcleo compartido (mismo código que usa iOS).
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core cineflux-core)

add_library(cineflux SHARED jni_bridge.cpp)
find_library(log-lib log)
target_link_libraries(cineflux PRIVATE cineflux_core ${log-lib})
target_compile_options(cineflux PRIVATE -Wall -Wextra)
