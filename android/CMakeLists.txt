# PokeL-ARecomp for Android (arm64-v8a): libmain.so = the runtime (Linux platform layer on bionic, the Vulkan
# backend, the ImGui menus), SDL3 as its own shared library. The game code (generated-android/, made from the
# player's own game files with tools/gen.py at the shifted base of runtime/rt.h) is a separate libgame.so built by
# android/build-gamelib.sh and linked against libmain.so.
cmake_minimum_required(VERSION 3.22)
project(plarecomp C CXX)

get_filename_component(ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
set(RT ${ROOT}/runtime)
set(TP ${ROOT}/third_party)

include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(adrenotools
    GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
    GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80
    GIT_SUBMODULES_RECURSE TRUE)
FetchContent_MakeAvailable(adrenotools)

set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
add_subdirectory(${TP}/SDL3 ${CMAKE_BINARY_DIR}/SDL3)

# shaderc: the NDK's own sources, built once by android/build-shaderc.sh (ndk-build: its static libraries)
set(SHADERC_SRC ${ANDROID_NDK}/sources/third_party/shaderc)
set(SHADERC_LIBDIR ${ROOT}/build/shaderc/obj/local/arm64-v8a)
add_library(shaderc_combined INTERFACE)
target_link_libraries(shaderc_combined INTERFACE
    ${SHADERC_LIBDIR}/libshaderc.a ${SHADERC_LIBDIR}/libshaderc_util.a ${SHADERC_LIBDIR}/libglslang.a
    ${SHADERC_LIBDIR}/libHLSL.a ${SHADERC_LIBDIR}/libOGLCompiler.a ${SHADERC_LIBDIR}/libOSDependent.a
    ${SHADERC_LIBDIR}/libSPIRV.a ${SHADERC_LIBDIR}/libSPIRV-Tools-opt.a ${SHADERC_LIBDIR}/libSPIRV-Tools.a)

file(GLOB RT_C ${RT}/*.c ${RT}/hle/*.c ${RT}/services/*.c ${RT}/nvn/*.c ${RT}/nvn/maxwell/*.c ${RT}/nvn/vulkan/*.c)
file(GLOB RT_CXX ${RT}/*.cpp ${RT}/nvn/vulkan/*.cpp)
file(GLOB RT_UI ${RT}/ui/*.mm)
list(APPEND RT_C ${RT}/platform/linux.c)
# not on Android yet: movies (FFmpeg)
list(REMOVE_ITEM RT_C ${RT}/hle/movie.c)
set_source_files_properties(${RT_UI} PROPERTIES LANGUAGE CXX COMPILE_OPTIONS "-xc++")

set(IMGUI ${TP}/imgui)
set(IMGUI_SRC ${IMGUI}/imgui.cpp ${IMGUI}/imgui_draw.cpp ${IMGUI}/imgui_tables.cpp ${IMGUI}/imgui_widgets.cpp
    ${IMGUI}/backends/imgui_impl_sdl3.cpp ${IMGUI}/backends/imgui_impl_vulkan.cpp)
set(FSR2_SRC ${TP}/fsr2/ffx_fsr2.cpp ${TP}/fsr2/ffx_assert.cpp ${TP}/fsr2/vk/ffx_fsr2_vk.cpp)

# the shader translator's id: part of the shader caches' tag, so a changed translator rebuilds them (as the Makefile)
file(READ ${RT}/nvn/maxwell/translate.c TR_A)
file(READ ${RT}/nvn/maxwell/shader.h TR_B)
string(SHA256 TR_HASH "${TR_A}${TR_B}")
string(SUBSTRING ${TR_HASH} 0 12 TRANSLATOR_ID)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${RT}/nvn/maxwell/translate.c ${RT}/nvn/maxwell/shader.h)

# hactool (ISC) + its mbedtls (Apache 2.0): Game Files reads the player's .nsp / .xci with it (gamefiles.c)
file(GLOB HACTOOL_SRC ${TP}/hactool/*.c)
file(GLOB MBEDTLS_SRC ${TP}/hactool/mbedtls/library/*.c)
add_library(hactool STATIC ${HACTOOL_SRC} ${MBEDTLS_SRC})
target_include_directories(hactool PUBLIC ${TP}/hactool ${TP}/hactool/mbedtls/include)
target_compile_definitions(hactool PRIVATE _BSD_SOURCE _DEFAULT_SOURCE _FILE_OFFSET_BITS=64)
target_compile_options(hactool PRIVATE -O2 -w -fPIC)

add_library(main SHARED ${RT_C} ${RT_CXX} ${RT_UI} ${IMGUI_SRC} ${FSR2_SRC} android_glue.c vulkan_driver.c gamefiles.c)
target_include_directories(main PRIVATE
    ${TP}/vulkan-headers/include ${TP}/vma/include ${RT} ${RT}/nvn ${IMGUI} ${IMGUI}/backends
    ${CMAKE_CURRENT_SOURCE_DIR}/inc ${TP}/smaa/Textures ${SHADERC_SRC}/libshaderc/include)
target_compile_definitions(main PRIVATE
    GAMELIB_ID="android" SHADER_TRANSLATOR_ID="${TRANSLATOR_ID}" FFX_GCC
    LARECOMP_ANDROID=1)
target_compile_options(main PRIVATE
    $<$<COMPILE_LANGUAGE:C>:-O2 -g -fno-strict-aliasing -Wall -Wno-unused-function -Wno-format>
    $<$<COMPILE_LANGUAGE:CXX>:-O2 -g -std=c++17 -fno-strict-aliasing -Wno-format>)
# -rdynamic equivalent: the game code library calls the runtime's functions (linked against libmain.so)
target_link_options(main PRIVATE -Wl,--export-dynamic -Wl,-z,nostart-stop-gc)
target_link_libraries(main PRIVATE SDL3::SDL3-shared shaderc_combined adrenotools hactool android log m dl)
