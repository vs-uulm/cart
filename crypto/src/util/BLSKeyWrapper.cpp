//
// Created by Alexander Heß on 05.09.22.
//

#include "util/BLSKeyWrapper.h"
#include "bls/util.h"

#include <iostream>

BLSKeyWrapper::BLSKeyWrapper(size_t num_signers, size_t threshold, size_t signer_index) : signer_index_(signer_index) {
    if(signer_index < 1 || signer_index > num_signers)
        throw new std::out_of_range("Invalid signer index.");

    if(num_signers < threshold || num_signers < 2 || threshold < 1)
        throw new std::invalid_argument("Invalid parameters.");

    if (core_init() != RLC_OK) {
        core_clean();
        return;
    }

    if (pc_param_set_any() != RLC_OK) {
        core_clean();
        return;
    }

    auto keys = util::generate_sample_keys(num_signers, threshold);

    private_keys_ = std::make_shared<std::vector<PrivateKey>>(keys.first.first);
    public_keys_ = std::make_shared<std::vector<PublicKey>>(keys.first.second);
    common_public_key_ = std::make_shared<PublicKey>(keys.second);
}

PrivateKey& BLSKeyWrapper::get_private_key() {
    return private_keys_->at(signer_index_-1);
}

PublicKey& BLSKeyWrapper::get_public_key(size_t signer_index) {
    return public_keys_->at(signer_index-1);
}

PublicKey& BLSKeyWrapper::get_common_public_key() {
    return *common_public_key_;
}

size_t BLSKeyWrapper::get_signer_index() {
    return signer_index_;
}