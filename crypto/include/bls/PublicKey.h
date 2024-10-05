//
// Created by Alexander Heß on 15.08.22.
//

#ifndef THRESHSIG_WRAPPER_PUBLICKEY_H
#define THRESHSIG_WRAPPER_PUBLICKEY_H

#include <vector>
#include <mutex>
#include "PrivateKey.h"

extern "C" {
    #include <relic/relic.h>
};

class PublicKey {
private:
    int index_;

    g2_t public_key_;

    int modified_bls_ver(const g1_t sig, const g1_t msg, const g2_t q);

public:
    explicit PublicKey(PrivateKey private_key, size_t index = -1);

    // Constructor to build a common public_key
    explicit PublicKey(std::vector<PrivateKey>& private_key_shares);

    explicit PublicKey(std::vector<uint8_t>& encoded);

    ~PublicKey();

    bool verify(std::vector<uint8_t>& message, std::vector<uint8_t>& signature);

    bool verify(std::vector<uint8_t>& message, Signature& signature);

    bool verify(const unsigned char* data, uint32_t data_len, const unsigned char* signature, uint32_t sig_len);

    bool aggregatedVerify(std::vector<std::vector<uint8_t>>& message, std::vector<std::vector<uint8_t>>& signature);

    bool aggregatedVerify(std::vector<std::vector<uint8_t>>& message, std::vector<Signature>& signature);

    void print();

    void print_encoded();

    void get_public_key(g2_t public_key);

    size_t get_index() const;
};


#endif //THRESHSIG_WRAPPER_PUBLICKEY_H
