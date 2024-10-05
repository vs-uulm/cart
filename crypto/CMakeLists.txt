cmake_minimum_required(VERSION 3.22)
project(crypto)

set(CMAKE_CXX_STANDARD 23)
if(APPLE)
    set(CMAKE_OSX_ARCHITECTURES "x86_64")
endif()

add_executable(Microbenchmark
        src/apps/microbenchmark.cpp
        src/bls/Polynomial.cpp
        src/bls/PublicKey.cpp
        src/bls/PrivateKey.cpp
        src/bls/Signature.cpp
        src/bls/util.cpp
        src/bls/LagrangeCoefficients.cpp)

target_include_directories(Microbenchmark PRIVATE /usr/local/include)
target_include_directories(Microbenchmark PRIVATE include)

if(APPLE)
    target_include_directories(Microbenchmark PRIVATE /usr/local/homebrew/include)
    target_link_directories(Microbenchmark PRIVATE /usr/local/homebrew/lib)
endif()

target_link_directories(Microbenchmark PRIVATE /usr/local/lib)
target_link_libraries(Microbenchmark crypto pthread relic gmp)

add_executable(AggregationBenchmark
        src/apps/aggregation_test.cpp
        src/bls/Polynomial.cpp
        src/bls/PublicKey.cpp
        src/bls/PrivateKey.cpp
        src/bls/Signature.cpp
        src/bls/util.cpp
        src/bls/LagrangeCoefficients.cpp
        )

target_include_directories(AggregationBenchmark PRIVATE /usr/local/include)
target_include_directories(AggregationBenchmark PRIVATE include)

if(APPLE)
    target_include_directories(AggregationBenchmark PRIVATE /usr/local/homebrew/include)
    target_link_directories(AggregationBenchmark PRIVATE /usr/local/homebrew/lib)
endif()

target_link_directories(AggregationBenchmark PRIVATE /usr/local/lib)
target_link_libraries(AggregationBenchmark crypto pthread relic gmp)

add_library(cart_jni_binding SHARED
        src/bls/Polynomial.cpp
        src/bls/PublicKey.cpp
        src/bls/PrivateKey.cpp
        src/bls/Signature.cpp
        src/bls/util.cpp
        src/binding/JNIBinding.cpp
        src/bls/LagrangeCoefficients.cpp)

set_property(TARGET cart_jni_binding PROPERTY POSITION_INDEPENDENT_CODE ON)

target_include_directories(cart_jni_binding PRIVATE /usr/local/include)
target_include_directories(cart_jni_binding PRIVATE include)

if(APPLE)
    target_include_directories(cart_jni_binding PRIVATE /Users/alex/Library/Java/JavaVirtualMachines/openjdk-19.0.1/Contents/Home/include)
    target_include_directories(cart_jni_binding PRIVATE /Users/alex/Library/Java/JavaVirtualMachines/openjdk-19.0.1/Contents/Home/include/darwin)
    target_include_directories(cart_jni_binding PRIVATE /usr/local/homebrew/include)
    target_link_directories(cart_jni_binding PRIVATE /usr/local/homebrew/lib)
endif()

if(UNIX AND NOT APPLE)
    target_include_directories(cart_jni_binding PRIVATE /usr/lib/jvm/java-21-openjdk-amd64/include)
    target_include_directories(cart_jni_binding PRIVATE /usr/lib/jvm/java-21-openjdk-amd64/include/linux)
endif()

target_link_directories(cart_jni_binding PRIVATE /usr/local/lib)
target_link_libraries(cart_jni_binding crypto pthread relic gmp)


add_library(cart_ffm_binding SHARED
        src/binding/FFMBinding.cpp
        src/bls/Polynomial.cpp
        src/bls/PublicKey.cpp
        src/bls/PrivateKey.cpp
        src/bls/Signature.cpp
        src/bls/util.cpp
        src/bls/LagrangeCoefficients.cpp)

set_property(TARGET cart_ffm_binding PROPERTY POSITION_INDEPENDENT_CODE ON)

target_include_directories(cart_ffm_binding PRIVATE /usr/local/include)
target_include_directories(cart_ffm_binding PRIVATE include)

if(APPLE)
    target_include_directories(cart_ffm_binding PRIVATE /usr/local/homebrew/include)
    target_link_directories(cart_ffm_binding PRIVATE /usr/local/homebrew/lib)
endif()

target_link_directories(cart_ffm_binding PRIVATE /usr/local/lib)
target_link_libraries(cart_ffm_binding crypto pthread relic gmp)
