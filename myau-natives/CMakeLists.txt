cmake_minimum_required(VERSION 3.20)
project(myau_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED JAVA_INCLUDE)
    set(JAVA_INCLUDE "$ENV{JAVA_HOME}/include")
endif()
if(NOT EXISTS "${JAVA_INCLUDE}/jni.h")
    message(FATAL_ERROR "jni.h not found under ${JAVA_INCLUDE}. Pass -DJAVA_INCLUDE=<jdk>/include")
endif()

add_library(myau_native SHARED dll/dllmain.cpp)
target_include_directories(myau_native PRIVATE
        "${JAVA_INCLUDE}"
        "${JAVA_INCLUDE}/win32")
set_target_properties(myau_native PROPERTIES PREFIX "" OUTPUT_NAME "myau_native")

if(NOT DEFINED CLIENT_JAR)
    file(GLOB JAR_CANDIDATES "${CMAKE_CURRENT_SOURCE_DIR}/../OpenMyau-Inject/build/libs/*.jar")
    list(FILTER JAR_CANDIDATES EXCLUDE REGEX "(sources|javadoc|dev)\\.jar$")
    if(NOT JAR_CANDIDATES)
        message(FATAL_ERROR
                "No client jar found under ../OpenMyau-Inject/build/libs. Run the Gradle build first, "
                "or pass -DCLIENT_JAR=<path>")
    endif()
    list(GET JAR_CANDIDATES 0 CLIENT_JAR)
endif()
message(STATUS "client jar: ${CLIENT_JAR}")

set(PAYLOAD_DIR "${CMAKE_BINARY_DIR}/payload")

add_custom_command(
        OUTPUT "${PAYLOAD_DIR}/myau_native.dll"
        COMMAND ${CMAKE_COMMAND} -E make_directory "${PAYLOAD_DIR}"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                $<TARGET_FILE:myau_native> "${PAYLOAD_DIR}/myau_native.dll"
        DEPENDS myau_native
        COMMENT "staging myau_native.dll for embedding")

add_custom_command(
        OUTPUT "${PAYLOAD_DIR}/client.jar"
        COMMAND ${CMAKE_COMMAND} -E make_directory "${PAYLOAD_DIR}"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${CLIENT_JAR}" "${PAYLOAD_DIR}/client.jar"
        DEPENDS "${CLIENT_JAR}"
        COMMENT "staging the client jar for embedding")

set(PAYLOAD_FILES "${PAYLOAD_DIR}/myau_native.dll" "${PAYLOAD_DIR}/client.jar")

set(LOGO_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/loader/logo.png")
if(EXISTS "${LOGO_SOURCE}")
    add_custom_command(
            OUTPUT "${PAYLOAD_DIR}/logo.png"
            COMMAND ${CMAKE_COMMAND} -E make_directory "${PAYLOAD_DIR}"
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    "${LOGO_SOURCE}" "${PAYLOAD_DIR}/logo.png"
            DEPENDS "${LOGO_SOURCE}"
            COMMENT "staging the logo for embedding")
    list(APPEND PAYLOAD_FILES "${PAYLOAD_DIR}/logo.png")
    set(LOGO_RESOURCE "IDR_LOGO       RCDATA \"${PAYLOAD_DIR}/logo.png\"")
else()
    message(STATUS "loader/logo.png is absent -- the window will draw the name as text")
    set(LOGO_RESOURCE "// no logo.png at build time")
endif()

set(ICON_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/loader/logo.ico")
if(EXISTS "${ICON_SOURCE}")
    set(ICON_RESOURCE "IDI_APP        ICON   \"${ICON_SOURCE}\"")
else()
    message(STATUS "loader/logo.ico is absent -- the exe keeps the default icon")
    set(ICON_RESOURCE "// no logo.ico at build time")
endif()

add_custom_target(myau_payload DEPENDS ${PAYLOAD_FILES})

set(GENERATED_RC "${CMAKE_CURRENT_BINARY_DIR}/myau_resources.rc")
configure_file(loader/resources.rc.in "${GENERATED_RC}" @ONLY)
set_property(SOURCE "${GENERATED_RC}" APPEND PROPERTY OBJECT_DEPENDS "${PAYLOAD_FILES};${ICON_SOURCE}")

add_executable(myau_loader WIN32
        loader/gui.cpp
        loader/inject.cpp
        loader/target.cpp
        "${GENERATED_RC}")
add_dependencies(myau_loader myau_payload)
target_include_directories(myau_loader PRIVATE loader)
target_link_libraries(myau_loader PRIVATE psapi gdiplus ole32 dwmapi)

if(MSVC)
    target_compile_options(myau_native PRIVATE /W3 /EHsc)
    target_compile_options(myau_loader PRIVATE /W3 /EHsc)

    set_property(TARGET myau_native PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
    set_property(TARGET myau_loader PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
    cmake_policy(SET CMP0091 NEW)
endif()
