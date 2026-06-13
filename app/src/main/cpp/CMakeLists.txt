cmake_minimum_required(VERSION 3.22.1)
project(aurora_fp)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CP ${CMAKE_CURRENT_SOURCE_DIR}/chromaprint)
set(KISS ${CP}/3rdparty/kissfft)

# HAVE_CONFIG_H pulls in our hand-written config.h (KissFFT, internal resampler). The EXPORTS symbol
# makes the Chromaprint API visible since we compile it into the same shared object we call it from.
add_definitions(
    -DHAVE_CONFIG_H
    -DCHROMAPRINT_API_EXPORTS
    -D_USE_MATH_DEFINES
    -D__STDC_LIMIT_MACROS
    -D__STDC_CONSTANT_MACROS
)

include_directories(${CP} ${KISS})

add_library(aurora_fp SHARED
    aurora_fp.cpp
    ${CP}/chromaprint.cpp
    ${CP}/audio_processor.cpp
    ${CP}/chroma.cpp
    ${CP}/chroma_resampler.cpp
    ${CP}/chroma_filter.cpp
    ${CP}/spectrum.cpp
    ${CP}/fft.cpp
    ${CP}/fft_lib_kissfft.cpp
    ${CP}/fingerprinter.cpp
    ${CP}/image_builder.cpp
    ${CP}/silence_remover.cpp
    ${CP}/fingerprint_calculator.cpp
    ${CP}/fingerprint_compressor.cpp
    ${CP}/fingerprint_decompressor.cpp
    ${CP}/fingerprinter_configuration.cpp
    ${CP}/fingerprint_matcher.cpp
    ${CP}/simhash.cpp
    ${CP}/utils/base64.cpp
    ${CP}/avresample/resample2.c
    ${KISS}/kiss_fft.c
    ${KISS}/tools/kiss_fftr.c
)

find_library(log-lib log)
target_link_libraries(aurora_fp ${log-lib})
