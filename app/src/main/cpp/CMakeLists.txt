cmake_minimum_required(VERSION 3.22.1)
project("opusjni")

# Включить C++17
set(CMAKE_CXX_STANDARD 17)

# Определить макросы для Opus
add_definitions(-DVAR_ARRAYS)
add_definitions(-DOPUS_BUILD)
add_definitions(-DCUSTOM_MODES)
add_definitions(-DFIXED_POINT)
add_definitions(-DHAVE_CONFIG_H)

# Указать пути к заголовочным файлам
include_directories(
        opus/include
        opus/celt
        opus/silk
        opus/silk/fixed
        opus/src
        ${CMAKE_CURRENT_BINARY_DIR}  # для config.h
)

# Создать config.h файл
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/config.h
        "#ifndef CONFIG_H
#define CONFIG_H
#define PACKAGE_VERSION \"1.5.2\"
#define HAVE_LRINTF 1
#define HAVE_LRINT 1
#define FIXED_POINT 1
#define VAR_ARRAYS 1
#define restrict __restrict__
#define HAVE_FLOOR 1
#define HAVE_CEIL 1
#ifdef __ARM_NEON__
#define OPUS_ARM_NEON_INTR
#define OPUS_ARM_NEON
#endif
#endif
")

# Используем GLOB для автоматического поиска файлов
file(GLOB OPUS_SILK_SOURCES "opus/silk/*.c")
file(GLOB OPUS_SILK_FIXED_SOURCES "opus/silk/fixed/*.c")
file(GLOB OPUS_CELT_SOURCES "opus/celt/*.c")
file(GLOB OPUS_SRC_SOURCES "opus/src/*.c")

# Исключаем demo файлы если они есть
list(FILTER OPUS_CELT_SOURCES EXCLUDE REGEX ".*demo\\.c$")
list(FILTER OPUS_SRC_SOURCES EXCLUDE REGEX ".*demo\\.c$")
list(FILTER OPUS_SRC_SOURCES EXCLUDE REGEX ".*compare\\.c$")

# Соберём все источники (функция уже есть в sort.c)
set(OPUS_SOURCES
        ${OPUS_SILK_SOURCES}
        ${OPUS_SILK_FIXED_SOURCES}
        ${OPUS_CELT_SOURCES}
        ${OPUS_SRC_SOURCES}
)

# Подключить JNI
find_library(log-lib log)

# Создать библиотеку
add_library(
        opus-lib
        SHARED
        opus_jni.cpp
        ${OPUS_SOURCES}
)

target_link_libraries(
        opus-lib
        ${log-lib}
)