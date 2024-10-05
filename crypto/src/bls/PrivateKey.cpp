//
// Created by Alexander Heß on 15.08.22.
//

#include <iostream>
#include "bls/PrivateKey.h"

PrivateKey::PrivateKey() : index_{-1} {
    bn_new(private_key_);

    bn_t group_order;
    bn_new(group_order);
    ep_curve_get_ord(group_order);

    bn_rand_mod(private_key_, group_order);
    bn_clean(group_order);
}

PrivateKey::PrivateKey(int index) : PrivateKey() {
    index_ = index;
}

PrivateKey::PrivateKey(bn_st* private_key, int index) : index_(index) {
    bn_new(private_key_);
    bn_copy(private_key_, private_key);
}

PrivateKey::~PrivateKey() {
    bn_clean(private_key_);
}

Signature PrivateKey::sign(std::vector<uint8_t>& data) {
    g1_t signature;
    g1_new(signature);

    if (cp_bls_sig(signature, data.data(), data.size(), private_key_) != RLC_OK)
        std::cerr << "Could not sign" << std::endl;

    Signature wrapped(signature, index_);
    g1_free(signature);
    return wrapped;
}

void PrivateKey::sign(std::vector<uint8_t>& data, std::vector<uint8_t>* signature) {
    g1_t signature_;
    g1_new(signature_);

    if (cp_bls_sig(signature_, data.data(), data.size(), private_key_) != RLC_OK)
        std::cerr << "Could not sign" << std::endl;

    size_t encoded_size = g1_size_bin(signature_, 1);
    signature->resize(encoded_size);
    g1_write_bin(signature->data(), signature->size(), signature_, 1);
    g1_free(signature_);
}

size_t PrivateKey::sign(const unsigned char* data, int data_len, unsigned char* signature) {
    g1_t signature_;
    g1_new(signature_);

    if (cp_bls_sig(signature_, data, data_len, private_key_) != RLC_OK)
        std::cerr << "Could not sign" << std::endl;

    size_t encoded_size = g1_size_bin(signature_, 1);
    g1_write_bin(signature, encoded_size, signature_, 1);
    g1_free(signature_);

    return encoded_size;
}

int PrivateKey::get_index() {
    return index_;
}

void PrivateKey::get_key(bn_t value) {
    bn_copy(value, private_key_);
}

void PrivateKey::print() {
    bn_print(private_key_);
}