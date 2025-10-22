cmake_minimum_required(VERSION 3.10)
project(benchmark_tool LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(benchmark_tool
    src/main.cpp
)

if (WIN32)
    target_compile_definitions(benchmark_tool PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
    target_link_libraries(benchmark_tool PRIVATE psapi)
endif()
