//
// Created by Alexander Heß on 16.08.22.
//

#include <unordered_set>
#include <iostream>

#include "bls/Signature.h"
#include "util/util.h"

Signature::Signature(g1_t signature, size_t index) : index_(index) {
    g1_new(signature_);
    g1_copy(signature_, signature);
}

Signature::Signature(const std::string& signature, size_t index) : index_(index) {
    g1_new(signature_);
    g1_read_bin(signature_, reinterpret_cast<const uint8_t*>(signature.data()), signature.size());
}

Signature::Signature(std::vector<uint8_t>& signature, size_t index) : index_(index) {
    g1_new(signature_)
    g1_read_bin(signature_, signature.data(), signature.size());
}

Signature::Signature(std::vector<Signature>& sig_shares) : index_{-1} {
    // assemble the signature
    std::vector<size_t> indices(sig_shares.size());

    for(uint32_t i = 0; i < sig_shares.size(); i++) {
        indices.at(i) = sig_shares.at(i).index_;
    }

    bn_t group_order_;
    bn_new(group_order_);
    ep_curve_get_ord(group_order_);

    // Compute the lagrange coefficients
    bn_t lagrange_coefficients[sig_shares.size()];

    for (int i = 0; i < sig_shares.size(); i++) {
        bn_null(lagrage_coefficients[i]);
        bn_new(lagrange_coefficients[i]);
        bn_set_dig(lagrange_coefficients[i], 1);

        for (int j = 0; j < sig_shares.size(); j++) {
            if (i != j) {
                bn_t intermediate;
                bn_new(intermediate);
                bn_set_dig(intermediate, indices.at(j));
                bn_sub_dig(intermediate, intermediate, indices.at(i));
                bn_mod_basic(intermediate, intermediate, group_order_);
                bn_mod_inv(intermediate, intermediate, group_order_);

                bn_mul_dig(intermediate, intermediate, indices.at(j));
                bn_mod_basic(intermediate, intermediate, group_order_);

                bn_mul_basic(lagrange_coefficients[i], lagrange_coefficients[i], intermediate);
                bn_mod_basic(lagrange_coefficients[i], lagrange_coefficients[i], group_order_);

                bn_clean(intermediate);
            }
        }
    }

    // Compute the aggregated signature;
    g1_new(signature_);
    g1_set_infty(signature_);

    g1_t intermediate;
    g1_new(intermediate);
    for(int i = 0; i < sig_shares.size(); i++) {
        g1_mul(intermediate, sig_shares.at(i).get_signature(), lagrange_coefficients[i]);
        g1_add(signature_, signature_, intermediate);

        g1_set_infty(intermediate);

        bn_free(lagrange_coefficients[i]);
    }
    g1_free(intermediate);
    g1_norm(signature_, signature_);
}

Signature::Signature(std::vector<Signature>& sig_shares, const std::unordered_map<uint32_t, bn_st>& lagrange_coefficients) : index_{-1} {
    // Compute the aggregated signature;
    g1_new(signature_);
    g1_set_infty(signature_);

    g1_t intermediate;
    g1_new(intermediate);
    for(auto & sig_share : sig_shares) {
        g1_mul(intermediate, sig_share.get_signature(), &lagrange_coefficients.at(sig_share.get_index()));
        g1_add(signature_, signature_, intermediate);
        g1_set_infty(intermediate);
    }
    g1_free(intermediate);
    g1_norm(signature_, signature_);
}

Signature::~Signature() {
    g1_free(signature_);
}

const g1_st* Signature::get_signature() {
    return signature_;
}

std::string Signature::to_string() {
    int size_compressed = g1_size_bin(signature_, 1);
    std::vector<uint8_t> encodedSig(size_compressed);
    g1_write_bin(encodedSig.data(), encodedSig.size(), signature_, 1);

    std::string encodedString(encodedSig.begin(), encodedSig.end());
    return encodedString;
}

int Signature::get_index() {
    return index_;
}

void Signature::print() {
    g1_print(signature_);
}
