cmake_minimum_required(VERSION 3.4.1)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -std=gnu++11  -D_CGE_USE_FFMPEG_ -DDEBUG -DANDROID_NDK -DCGE_TEXTURE_PREMULTIPLIED=1 -D__STDC_CONSTANT_MACROS -D_CGE_DISABLE_GLOBALCONTEXT_ -O3 -ffast-math -D_CGE_ONLY_FILTERS_")

# yuv-decoder
add_library(yuv-decoder SHARED
        src/main/jni/yuv-decoder.c)

target_link_libraries(yuv-decoder
        ${log} ${GLESv2} ${jnigraphics})

ADD_SUBDIRECTORY(src/main/jni/cge)
ADD_SUBDIRECTORY(src/main/jni/source)

