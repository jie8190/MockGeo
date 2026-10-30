cmake_minimum_required(VERSION 3.22.1)
project(mockgeo_art LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mockgeo_art SHARED
    mockgeo_art.cpp
)

find_library(log-lib log)
find_library(dl-lib dl)

target_compile_options(mockgeo_art PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
)

target_link_options(mockgeo_art PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(mockgeo_art
    ${log-lib}
    ${dl-lib}
)
