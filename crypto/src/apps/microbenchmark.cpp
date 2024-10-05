//
// Created by Alexander Heß on 11.01.24.
//

#include <numeric>
#include <iostream>
#include <sstream>

#include "bls/PrivateKey.h"
#include "bls/PublicKey.h"
#include "bls/util.h"
#include "bls/LagrangeCoefficients.h"

extern "C" {
#include <relic/relic.h>
}


int main(int argc, char **argv) {
    size_t num_signers = argc >= 2 ? atoi(argv[1]) : 4;
    size_t threshold = argc >= 3 ? atoi(argv[2]) : 2;
    size_t num_operations = argc >= 4 ? atoi(argv[3]) : 1000;

    std::cout << "Received " << argc -1 << "parameters" << std::endl;
    for(uint32_t i = 1; i < argc; i++) {
        std::cout << "Argument: " << i << " " << argv[i] << std::endl;
    }

    if (core_init() != RLC_OK) {
        core_clean();
        std::cerr << "Error: could not initialize the relic library" << std::endl;
    }

    if (pc_param_set_any() != RLC_OK) {
        core_clean();
        std::cerr << "Error: could not initialize the relic library" << std::endl;
    }

    std::vector<std::vector<uint8_t>> test_data;
    test_data.reserve(num_operations);

    std::vector<std::vector<uint8_t>> test_keys;
    test_keys.reserve(num_operations);

    std::vector<std::vector<uint8_t>> HMACs;
    HMACs.reserve(num_operations);

    std::cout << "Performing Setup" << std::endl;
    for (uint32_t i = 0; i < num_operations; i++) {
        std::stringstream ss;
        ss << "random message" << ((i + 1));
        std::string message_data(ss.str());
        std::vector<uint8_t> data_vec(message_data.begin(), message_data.end());
        test_data.push_back(data_vec);

        ss.clear();
        ss << "secret key" << ((i + 1));
        std::string key_data(ss.str());
        std::vector<uint8_t> key_vec(key_data.begin(), key_data.end());
        test_keys.push_back(key_vec);
    }
    std::cout << "Finished Setup" << std::endl;

    std::cout << "Starting HMAC microbenchmark with num_operations=" << num_operations << std::endl;
    //void md_hmac(uint8_t *mac, const uint8_t *in, size_t in_len, const uint8_t *key, size_t key_len)

    size_t sign_time = 0;
    size_t verify_time = 0;

    // HMAC Creation
    auto start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        std::vector<uint8_t> hmac(RLC_MD_LEN);
        md_hmac(hmac.data(), test_data.at(i).data(), test_data.at(i).size(), test_keys.at(i).data(), test_keys.at(i).size());
        HMACs.emplace_back(hmac);
    }
    auto end = std::chrono::high_resolution_clock::now();
    auto ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    std::cout << "Created " << num_operations << " HMACs in " << ms_int.count() << " µs" << std::endl;
    sign_time = ms_int.count();
    // HMAC Verification
    start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        std::vector<uint8_t> hmac(RLC_MD_LEN);
        md_hmac(hmac.data(), test_data.at(i).data(), test_data.at(i).size(), test_keys.at(i).data(), test_keys.at(i).size());
        if(!(hmac==HMACs.at(i)))
            std::cerr << "HMACs do not match" << std::endl;
    }
    end = std::chrono::high_resolution_clock::now();
    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    std::cout << "Verified " << num_operations << " HMACs in " << ms_int.count() << " µs" << std::endl;
    verify_time = ms_int.count();

    std::cout << "Average HMAC generation time: "
              << sign_time / num_operations << "µs"
              << std::endl;

    std::cout << "Average HMAC verification time: "
              << verify_time / num_operations << "µs"
              << std::endl;

    std::cout << "Starting ECDSA microbenchmark with num_operations=" << num_operations << std::endl;
    ep_param_set(SECG_K256);
    ep_param_print();

    sign_time = 0;
    verify_time = 0;

    bn_t privKey;
    ec_t pubKey;

    bn_null(privKey);
    ec_null(pubKey);

    // int cp_ecdsa_gen(bn_t d, ec_t q)
    if (cp_ecdsa_gen(privKey, pubKey) != RLC_OK) {
        std::cerr << "ECDSA key generation was not successful" << std::endl;
    }

    std::vector<std::pair<bn_st, bn_st>> ecdsa_signatures;
    ecdsa_signatures.reserve(num_operations);

    // Signing
    start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        bn_st r;
        bn_st s;
        // cp_ecdsa_sig(bn_t r, bn_t s, const uint8_t *msg, size_t len, int hash, const bn_t d)
        if (cp_ecdsa_sig(&r, &s, test_data.at(i).data(), test_data.at(i).size(), 0, privKey) != RLC_OK) {
            std::cerr << "ECDSA signing was not successful" << std::endl;
        }
        ecdsa_signatures.emplace_back(r, s);
    }
    end = std::chrono::high_resolution_clock::now();
    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    std::cout << "Created " << num_operations << " signatures in " << ms_int.count() << " µs" << std::endl;
    sign_time = ms_int.count();

    // Verification
    start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        // int cp_ecdsa_ver(const bn_t r, const bn_t s, const uint8_t *msg, size_t len, int hash, const ec_t q)
        if (cp_ecdsa_ver(&ecdsa_signatures.at(i).first, &ecdsa_signatures.at(i).second, test_data.at(i).data(),
                         test_data.at(i).size(), 0, pubKey) != 1) {
            std::cerr << "ECDSA verification was not successful" << std::endl;
        }
    }
    end = std::chrono::high_resolution_clock::now();
    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    std::cout << "Verified " << num_operations << " signatures in " << ms_int.count() << " µs" << std::endl;
    verify_time = ms_int.count();

    std::cout << "Average signing time: "
              << sign_time / num_operations << "µs"
              << std::endl;
    std::cout << "Average verification time: "
              << verify_time / num_operations
              << "µs" << std::endl;


    ep_param_set(BN_P256);
    ep_param_print();

    std::cout << "Starting BLS microbenchmark with num_signers=" << num_signers
              << ", threshold=" << threshold << ", num_operations=" << num_operations << std::endl;


    std::pair<std::pair<std::vector<PrivateKey>, std::vector<PublicKey>>, PublicKey>
            keys = util::generate_sample_keys(num_signers, threshold);

    std::vector<PrivateKey> privateKeys = keys.first.first;
    std::vector<PublicKey> publicKeys = keys.first.second;

    PublicKey groupPublicKey = keys.second;

    size_t signing_time = 0;
    size_t verification_time = 0;
    size_t non_verified_aggregation_time = 0;
    size_t optimistic_aggregation_time = 0;
    size_t default_aggregation_time = 0;
    size_t batch_verification_10_time = 0;
    size_t batch_verification_100_time = 0;
    size_t batch_verification_1000_time = 0;

    std::vector<Signature> signatures;
    signatures.reserve(num_operations);

    // Signing
    start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        signatures.emplace_back(privateKeys.at(0).sign(test_data.at(i)));
    }
    end = std::chrono::high_resolution_clock::now();
    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    std::cout << "Created " << num_operations << " signatures in " << ms_int.count() << "µs" << std::endl;
    signing_time = ms_int.count();

    // Verification
    start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        publicKeys.at(0).verify(test_data.at(i), signatures.at(i));
    }
    end = std::chrono::high_resolution_clock::now();
    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    std::cout << "Verified " << num_operations << " signatures in " << ms_int.count() << "µs" << std::endl;
    verification_time = ms_int.count();

    // Aggregation
    std::vector<std::vector<Signature>> signature_shares;
    signature_shares.reserve(num_operations);
    for (uint32_t i = 0; i < num_operations; i++) {
        std::vector<Signature> sig_shares;
        sig_shares.reserve(threshold);
        for(uint32_t j = 1; j <= threshold; j++)
            sig_shares.emplace_back(privateKeys.at(j).sign(test_data.at(i)));

        signature_shares.emplace_back(sig_shares);
    }

    start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        Signature aggregatedSignature(signature_shares.at(i));
    }
    end = std::chrono::high_resolution_clock::now();

    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    non_verified_aggregation_time = ms_int.count();

    std::cout << "Non-Verified Aggregation of " << num_operations << " signatures took " << ms_int.count() << "µs"
              << std::endl;

    // Pre-computed aggregation Aggregation
    /*
    LagrangeCoefficients lagrangeCoefficients(num_signers, threshold);

    start = std::chrono::high_resolution_clock::now();
    for (uint32_t i = 0; i < num_operations; i++) {
        std::set<uint32_t> indices;

        for(auto& share: signature_shares.at(i))
            indices.emplace(share.get_index());

        Signature aggregatedSignature(signature_shares.at(i), lagrangeCoefficients.get_coefficients(indices));
    }
    end = std::chrono::high_resolution_clock::now();

    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    non_verified_aggregation_time = ms_int.count();

    std::cout << "Non-Verified Aggregation of " << num_operations << " signatures took " << ms_int.count() << "µs"
              << std::endl;
    */

    // Batch Verification

    std::vector<std::vector<uint8_t>> test_data_10(test_data.begin(), test_data.begin() + 10);
    std::vector<std::vector<uint8_t>> test_data_100(test_data.begin(), test_data.begin() + 100);

    std::vector<Signature> signatures_10(signatures.begin(), signatures.begin() + 10);
    std::vector<Signature> signatures_100(signatures.begin(), signatures.begin() + 100);

    start = std::chrono::high_resolution_clock::now();
    publicKeys.at(0).aggregatedVerify(test_data_10, signatures_10);
    end = std::chrono::high_resolution_clock::now();

    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);

    std::cout << "Aggregated verification of " << 10 << " signatures took " << ms_int.count() << "µs"
              << std::endl;

    batch_verification_10_time = ms_int.count();

    start = std::chrono::high_resolution_clock::now();
    publicKeys.at(0).aggregatedVerify(test_data_100, signatures_100);
    end = std::chrono::high_resolution_clock::now();

    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);

    std::cout << "Aggregated verification of " << 100 << " signatures took " << ms_int.count() << "µs"
              << std::endl;

    batch_verification_100_time = ms_int.count();

    start = std::chrono::high_resolution_clock::now();
    publicKeys.at(0).aggregatedVerify(test_data, signatures);
    end = std::chrono::high_resolution_clock::now();

    ms_int = std::chrono::duration_cast<std::chrono::microseconds>(end - start);

    std::cout << "Aggregated verification of " << num_operations << " signatures took " << ms_int.count() << "µs"
              << std::endl;

    batch_verification_1000_time = ms_int.count();

    std::cout << "Average signing time: "
              << 1.0 * signing_time / num_operations << "µs"
              << std::endl;
    std::cout << "Average verification time: "
              << 1.0 * verification_time / num_operations
              << "µs" << std::endl;
    std::cout << "Average aggregation time: "
              << 1.0 * non_verified_aggregation_time / num_operations
              << "µs" << std::endl;
    std::cout << "Average batch verification time with 10 requests: "
              << batch_verification_10_time << "µs" << std::endl;
    std::cout << "Average batch verification time with 100 requests: "
              << batch_verification_100_time << "µs" << std::endl;
    std::cout << "Average batch verification time with 1000 requests: "
              << batch_verification_1000_time << "µs" << std::endl;

    return 0;
}