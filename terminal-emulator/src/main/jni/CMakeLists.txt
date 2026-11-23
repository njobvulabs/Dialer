cmake_minimum_required(VERSION 3.18.1)

project("termux")

add_library(termux SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/termux.c
)

target_compile_features(termux PRIVATE c_std_11)
