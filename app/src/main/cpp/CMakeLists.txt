cmake_minimum_required(VERSION 3.22.1)
project(njretro)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
set(BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
set(LIBRETRO_STATIC ON CACHE BOOL "" FORCE)
set(SKIP_LIBRARY ON CACHE BOOL "" FORCE)
set(DISABLE_DEPS ON CACHE BOOL "" FORCE)
set(DISABLE_FRONTENDS ON CACHE BOOL "" FORCE)
set(M_CORE_GB ON CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(BUILD_LTO OFF CACHE BOOL "" FORCE)
set(BUILD_PGO OFF CACHE BOOL "" FORCE)

add_subdirectory(mgba ${CMAKE_BINARY_DIR}/mgba EXCLUDE_FROM_ALL)

add_library(njretro SHARED RetroNative.cpp)
target_include_directories(njretro PRIVATE mgba/src/platform/libretro)
target_link_libraries(njretro mgba_libretro android log)

set(PPSSPP_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ppsspp")
add_subdirectory(ppsspp-libretro ${CMAKE_BINARY_DIR}/ppsspp-libretro EXCLUDE_FROM_ALL)
