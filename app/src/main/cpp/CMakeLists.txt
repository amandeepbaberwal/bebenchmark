cmake_minimum_required(VERSION 3.22.1)
project(benchmark_engine)

add_library(benchmark_engine SHARED benchmark_engine.cpp)

find_library(log-lib log)
target_link_libraries(benchmark_engine ${log-lib})

target_compile_options(benchmark_engine PRIVATE
    -O3
    -ffast-math
    -ftree-vectorize
    -funroll-loops
)

# Enable NEON explicitly on 32-bit ARM; arm64 has it implicitly.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(benchmark_engine PRIVATE -mfpu=neon -mfloat-abi=softfp)
endif()
