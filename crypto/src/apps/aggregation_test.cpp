//
// Created by Alexander Heß on 14.03.24.
//
#include <iostream>
#include <sstream>

#include "bls/Signature.h"
#include "bls/LagrangeCoefficients.h"
#include "bls/PrivateKey.h"
#include "bls/PublicKey.h"
#include "bls/util.h"

extern "C" {
#include <relic/relic.h>
}

int main(int argc, char **argv) {
    size_t num_signers = argc >= 2 ? atoi(argv[1]) : 4;
    size_t threshold = argc >= 3 ? atoi(argv[2]) : 3;
    size_t num_operations = argc >= 4 ? atoi(argv[3]) : 100;

    if (core_init() != RLC_OK) {
        core_clean();
        std::cerr << "Error: could not initialize the relic library" << std::endl;
    }

    if (pc_param_set_any() != RLC_OK) {
        core_clean();
        std::cerr << "Error: could not initialize the relic library" << std::endl;
    }

    std::pair<std::pair<std::vector<PrivateKey>, std::vector<PublicKey>>, PublicKey>
            keys = util::generate_sample_keys(num_signers, threshold);

    std::vector<PrivateKey> privateKeys = keys.first.first;
    std::vector<PublicKey> publicKeys = keys.first.second;

    PublicKey groupPublicKey = keys.second;

    std::vector<std::vector<uint8_t>> test_data;
    test_data.reserve(num_operations);

    std::cout << "Performing Setup" << std::endl;
    for (uint32_t i = 0; i < num_operations; i++) {
        std::stringstream ss;
        ss << "random message" << ((i + 1));
        std::string message_data(ss.str());
        std::vector<uint8_t> data_vec(message_data.begin(), message_data.end());
        test_data.push_back(data_vec);
    }

    std::vector<std::vector<Signature>> signature_shares;
    signature_shares.reserve(num_operations);
    for (uint32_t i = 0; i < num_operations; i++) {
        std::vector<Signature> sig_shares;
        sig_shares.reserve(threshold);
        for(uint32_t j = 1; j <= threshold; j++)
            sig_shares.emplace_back(privateKeys.at(j).sign(test_data.at(i)));

        signature_shares.emplace_back(sig_shares);
    }

    std::cout << "Finished Setup" << std::endl;

    // Aggregation
    auto start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        Signature aggregatedSignature(signature_shares.at(i));
    }
    auto end = std::chrono::high_resolution_clock::now();

    auto ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);

    std::cout << "Standard Aggregation of " << num_operations << " signatures took " << ms_int.count() / num_operations << "µs on average per operation"
              << std::endl;

    // Pre-computed aggregation Aggregation
    LagrangeCoefficients lagrangeCoefficients(num_signers, threshold);
    std::cout << "Generated coefficients" << std::endl;

    start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        std::vector<uint32_t> indices;
        indices.reserve(signature_shares.at(i).size());

        for(auto& share: signature_shares.at(i))
            indices.push_back(share.get_index());

        Signature aggregatedSignature(signature_shares.at(i), lagrangeCoefficients.get_coefficients(indices));
    }
    end = std::chrono::high_resolution_clock::now();

    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);

    std::cout << "Pre-Computed Aggregation of " << num_operations << " signatures took " << 1.0 * ms_int.count() / num_operations << "µs on average per operation"
              << std::endl;
}
