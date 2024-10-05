//
// Created by Alexander Heß on 16.08.22.
//

#ifndef THRESHSIG_WRAPPER_UTIL_H
#define THRESHSIG_WRAPPER_UTIL_H

#include <vector>
#include "PrivateKey.h"
#include "PublicKey.h"

namespace util {
    std::pair<std::pair<std::vector<PrivateKey>, std::vector<PublicKey>>, PublicKey> generate_sample_keys(size_t num_signers, size_t threshold);

    size_t sig_length();
}

#endif //THRESHSIG_WRAPPER_UTIL_H
