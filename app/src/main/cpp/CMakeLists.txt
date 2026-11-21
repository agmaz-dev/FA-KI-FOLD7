cmake_minimum_required(VERSION 3.22.1)
project(foldki_llm C CXX)

# WICHTIG: Die KI immer optimiert bauen, auch in der Test-APK (Debug wäre 10- bis 50-mal langsamer)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp (MIT-Lizenz) wird beim Bauen geladen – feste Version für reproduzierbare APKs
include(FetchContent)
set(LLAMA_TAG "b12287" CACHE STRING "llama.cpp Version")
# llama.cpp als einzelne Bibliotheken; die Rechenteile (CPU, GPU/OpenCL) werden zur Laufzeit geladen.
# Fehlt OpenCL auf einem Gerät, wird der GPU-Teil einfach übersprungen und die KI läuft wie bisher auf dem Prozessor.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Snapdragon 8 Elite (Galaxy Z Fold 7) kann dotprod und i8mm – deutlich schneller bei 4-Bit-Modellen
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
    # Optimierte Rechenkerne von ARM (schneller auf Snapdragon/Oryon)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

# 16-KB-Seiten für alle Bibliotheken (llama, ggml, Rechenteile), nicht nur für foldki_llm
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

# GPU (Adreno im Snapdragon 8 Elite) über OpenCL. Die Khronos-Header und der ICD-Loader werden nur zum Bauen
# gebraucht; zur Laufzeit nutzt die App die OpenCL-Bibliothek des Handys (/vendor/lib64/libOpenCL.so,
# freigegeben per <uses-native-library> im Manifest). Unsere libOpenCL.so wird nicht in die APK gepackt.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(OPENCL_TAG "v2024.10.24" CACHE STRING "OpenCL-Header/ICD-Loader Version")
    FetchContent_Declare(opencl_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
        GIT_TAG ${OPENCL_TAG}
        GIT_SHALLOW TRUE)
    FetchContent_GetProperties(opencl_headers)
    if(NOT opencl_headers_POPULATED)
        FetchContent_Populate(opencl_headers)
    endif()
    set(OPENCL_ICD_LOADER_HEADERS_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERINFO OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(opencl_icd_loader
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
        GIT_TAG ${OPENCL_TAG}
        GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(opencl_icd_loader)
    # find_package(OpenCL) in ggml findet damit Header und das Ziel "OpenCL" aus dem ICD-Loader
    set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
endif()

if(DEFINED ENV{LLAMA_SRC_DIR})
    set(FETCHCONTENT_SOURCE_DIR_LLAMA $ENV{LLAMA_SRC_DIR})
endif()
FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LLAMA_TAG}
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

add_library(foldki_llm SHARED foldki_llm.cpp)
target_include_directories(foldki_llm PRIVATE ${llama_SOURCE_DIR}/common ${llama_SOURCE_DIR}/include ${llama_SOURCE_DIR}/vendor ${llama_SOURCE_DIR}/ggml/include)
if(TARGET llama-common)
    target_link_libraries(foldki_llm PRIVATE llama llama-common ggml ggml-base)
else()
    target_link_libraries(foldki_llm PRIVATE llama common ggml ggml-base)
endif()
if(ANDROID)
    target_link_libraries(foldki_llm PRIVATE android log)
endif()
# Android 15+ / Google Play: 16-KB-Seiten (Galaxy Z Fold 7) – ELF-Segmente auf 16 KB ausrichten
target_link_options(foldki_llm PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
