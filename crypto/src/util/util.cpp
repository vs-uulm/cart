#include "util/util.h"

#include <string>
#include <cstring>
#include <openssl/sha.h>

std::array<uint8_t, 32> util::sha256(std::string &input) {
    std::array<uint8_t, 32> hash{0};
    SHA256_CTX sha256;
    SHA256_Init(&sha256);
    SHA256_Update(&sha256, input.c_str(), strlen(input.c_str()));
    SHA256_Final(hash.data(), &sha256);
    return hash;
}

std::array<uint8_t, 32> util::sha256(std::vector<uint8_t> &input) {
    std::string input_str(input.begin(), input.end());

    std::array<uint8_t, 32> hash{0};
    SHA256_CTX sha256;
    SHA256_Init(&sha256);
    SHA256_Update(&sha256, input_str.c_str(), strlen(input_str.c_str()));
    SHA256_Final(hash.data(), &sha256);
    return hash;
}

uint64_t util::factorial(uint32_t k) {
    uint64_t factorial = 1;

    for(uint32_t i = k; i > 1; i--)
        factorial *= i;

    return factorial;
}

uint64_t util::binomial(uint32_t n, uint32_t k) {
    return factorial(n) / (factorial(k) * factorial(n-k));
}