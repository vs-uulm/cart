//
// Created by Alexander Heß on 16.08.22.
//

#include <iostream>

#include "bls/util.h"
#include "bls/Polynomial.h"

std::pair<std::pair<std::vector<PrivateKey>, std::vector<PublicKey>>, PublicKey> util::generate_sample_keys(size_t num_signers, size_t threshold) {
    Polynomial polynomial(threshold-1);

    std::vector<PrivateKey> private_keys;
    std::vector<PublicKey> public_keys;

    private_keys.reserve(num_signers);
    public_keys.reserve(num_signers);

    // Compute random private key-shares
    bn_st* current;
    for(uint32_t i = 1; i <= num_signers; i++) {
        current = polynomial.evaluate(i);
        PrivateKey private_key(current, i);
        PublicKey public_key(private_key, i);

        private_keys.push_back(private_key);
        public_keys.push_back(public_key);

        bn_clean(current);
        delete current;
    }

    // compute common public key
    bn_st* free_coefficient = polynomial.evaluate(0);
    PrivateKey assembled_private_key(free_coefficient, 0);
    bn_clean(free_coefficient);
    delete free_coefficient;

    PublicKey common_public_key(assembled_private_key);
    return std::make_pair(std::make_pair(private_keys, public_keys), common_public_key);
}



