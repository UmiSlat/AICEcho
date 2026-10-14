cmake_minimum_required(VERSION 3.22.1)
project(aicecho_native LANGUAGES C CXX)

find_package(dobby REQUIRED CONFIG)
find_library(android_log log REQUIRED)

add_library(aicecho_pmm SHARED src/main/cpp/pmm_hook.cpp)
target_link_libraries(aicecho_pmm PRIVATE dobby::dobby ${android_log} dl)

add_executable(aicecho_injector src/main/cpp/hal_injector.c)
target_link_libraries(aicecho_injector PRIVATE dl)

function(stage_binary target destination)
    get_filename_component(destination_directory "${destination}" DIRECTORY)
    add_custom_command(TARGET ${target} POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory "${destination_directory}"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:${target}>" "${destination}"
        COMMAND ${CMAKE_STRIP} --strip-unneeded "${destination}"
        VERBATIM
    )
endfunction()

set(module_stage "${CMAKE_CURRENT_SOURCE_DIR}/build/ksu-module/${ANDROID_ABI}")
stage_binary(aicecho_pmm "${module_stage}/system/vendor/lib64/libaicecho_pmm.so")
stage_binary(aicecho_injector "${module_stage}/bin/aicecho_injector")
