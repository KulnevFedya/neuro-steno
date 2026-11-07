cmake_minimum_required(VERSION 3.22)
project(steno_llama C CXX)
set(CMAKE_CXX_STANDARD 17)

# llama.cpp (submodule, зафиксированный тег) — статически в один libsteno_llama.so, только CPU
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# arm64: dotprod есть у всех телефонов среднего сегмента с 2018 г.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
add_subdirectory(llama.cpp)

add_library(steno_llama SHARED llama_jni.cpp)
target_link_libraries(steno_llama llama log)
