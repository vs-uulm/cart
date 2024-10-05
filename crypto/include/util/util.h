#ifndef THRESHSIG_WRAPPER_UTIL_H
#define THRESHSIG_WRAPPER_UTIL_H

#include <string>
#include <vector>
#include <array>

namespace util {

    std::array<uint8_t, 32> sha256(std::string& input);

    std::array<uint8_t, 32> sha256(std::vector<uint8_t>& input);

    uint64_t factorial(uint32_t k);

    uint64_t binomial(uint32_t n, uint32_t k);
}

#endif //THRESHSIG_WRAPPER_UTIL_H
