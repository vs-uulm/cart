//
// Created by Alexander Heß on 15.08.22.
//
#include <iostream>
#include <iomanip>
#include <sstream>
#include "bls/PublicKey.h"

PublicKey::PublicKey(PrivateKey private_key, size_t index) : index_(index) {
    bn_t private_value;
    bn_new(private_value);

    private_key.get_key(private_value);
    g2_new(public_key_);
    g2_mul_gen(public_key_, private_value);
    bn_clean(private_value);
}

PublicKey::PublicKey(std::vector<PrivateKey> &private_key_shares) : index_(-1) {
    // assemble the signature
    std::vector<size_t> indices(private_key_shares.size());

    for (uint32_t i = 0; i < private_key_shares.size(); i++)
        indices.at(i) = private_key_shares.at(i).get_index();

    bn_t group_order_;
    bn_new(group_order_);
    ep_curve_get_ord(group_order_);


    // Compute the lagrange coefficients

    bn_t *lagrange_coefficients = new bn_t[private_key_shares.size()];
    for (int i = 0; i < private_key_shares.size(); i++) {
        bn_new(lagrange_coefficients[i]);
        bn_set_dig(lagrange_coefficients[i], 1);

        for (int j = 0; j < private_key_shares.size(); j++) {
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

    // Compute the common public key
    bn_t private_key;
    bn_new(private_key);
    bn_zero(private_key);

    bn_t intermediate;
    bn_new(intermediate);
    for (int i = 0; i < private_key_shares.size(); i++) {
        bn_zero(intermediate);
        private_key_shares.at(i).get_key(intermediate);
        bn_mul_basic(intermediate, intermediate, lagrange_coefficients[i]);
        bn_add(private_key, private_key, intermediate);
    }

    bn_mod_basic(private_key, private_key, group_order_);
    bn_clean(intermediate);

    g2_new(public_key_);
    g2_mul_gen(public_key_, private_key);
    g2_norm(public_key_, public_key_);

    bn_clean(private_key);
}

// constructor to load an encoded group public key
PublicKey::PublicKey(std::vector<uint8_t>& encoded) : index_(-1) {
    g2_new(public_key_);
    g2_read_bin(public_key_, encoded.data(), encoded.size());
}

PublicKey::~PublicKey() {
    g2_free(public_key_);
}

bool PublicKey::verify(std::vector<uint8_t> &message, std::vector<uint8_t> &signature) {
    g1_t g1_signature;
    g1_null(g1_signature);
    g1_new(g1_signature);

    g1_read_bin(g1_signature, signature.data(), signature.size());
    int result = cp_bls_ver(g1_signature, message.data(), message.size(), public_key_);

    if (result == 1)
        return true;
    else
        return false;
}

bool PublicKey::verify(const unsigned char* data, uint32_t data_len, const unsigned char* signature, uint32_t sig_len) {
    g1_t g1_signature;
    g1_null(g1_signature);
    g1_new(g1_signature);

    g1_read_bin(g1_signature, signature, sig_len);
    int result = cp_bls_ver(g1_signature, data, data_len, public_key_);

    if (result == 1)
        return true;
    else
        return false;
}

int PublicKey::modified_bls_ver(const g1_t sig, const g1_t msg, const g2_t q) {
    g1_t p[2];
    g2_t r[2];
    gt_t e;
    int result = 0;

    g1_null(p[0]);
    g1_null(p[1]);
    g2_null(r[0]);
    g2_null(r[1]);
    gt_null(e);

    RLC_TRY {
                        g1_new(p[0]);
                        g1_new(p[1]);
                        g2_new(r[0]);
                        g2_new(r[1]);
                        gt_new(e);

                        g1_copy(p[0], msg);
                        g1_copy(p[1], sig);
                        g2_copy(r[0], q);
                        g2_get_gen(r[1]);
                        g2_neg(r[1], r[1]);

                        pc_map_sim(e, p, r, 2);
                        if (gt_is_unity(e) && g2_is_valid(q)) {
                            result = 1;
                        }
                    } RLC_CATCH_ANY {
            RLC_THROW(ERR_CAUGHT);
        } RLC_FINALLY {
            g1_free(p[0]);
            g1_free(p[1]);
            g2_free(r[0]);
            g2_free(r[1]);
            gt_free(e);
        }
    return result;
}


bool PublicKey::aggregatedVerify(std::vector<std::vector<uint8_t>>& data, std::vector<std::vector<uint8_t>>& signatures) {
    g1_t aggregated_signature;
    g1_t aggregated_message;
    g1_t intermediate_signature;
    g1_t intermediate_message;

    g1_null(aggregated_signature);
    g1_null(aggregated_message);
    g1_null(intermediate_signature);
    g1_null(intermediate_message);

    g1_new(aggregated_signature);
    g1_new(aggregated_message);
    g1_new(intermediate_signature);
    g1_new(intermediate_message);

    // catch malformed invocation
    if (data.size() != signatures.size()) {
        std::cerr << "Aborting: vector size mismatch" << std::endl;
        return false;
    }

    g1_set_infty(aggregated_signature);
    g1_set_infty(aggregated_message);

    for(uint32_t i = 0; i < data.size(); i++) {
        g1_read_bin(intermediate_signature, signatures.at(i).data(), signatures.at(i).size());
        g1_map(intermediate_message, data.at(i).data(), data.at(i).size());

        g1_add(aggregated_signature, aggregated_signature, intermediate_signature);
        g1_add(aggregated_message, aggregated_message, intermediate_message);

        g1_null(intermediate_message);
        g1_null(intermediate_signature);

        g1_norm(aggregated_message, aggregated_message);
        g1_norm(aggregated_signature, aggregated_signature);
    }

    int result = modified_bls_ver(aggregated_signature, aggregated_message, public_key_);

    g1_free(intermediate_signature);
    g1_free(intermediate_message);

    g1_free(aggregated_signature);
    g1_free(aggregated_message);

    if(result == 1)
        return true;
    else
        return false;
}

bool PublicKey::aggregatedVerify(std::vector<std::vector<uint8_t>>& data, std::vector<Signature>& signatures) {
    g1_t aggregated_signature;
    g1_t aggregated_message;
    g1_t intermediate_signature;
    g1_t intermediate_message;

    g1_null(aggregated_signature);
    g1_null(aggregated_message);
    g1_null(intermediate_signature);
    g1_null(intermediate_message);

    g1_new(aggregated_signature);
    g1_new(aggregated_message);
    g1_new(intermediate_signature);
    g1_new(intermediate_message);

    // catch malformed invocation
    if (data.size() != signatures.size()) {
        std::cerr << "Aborting: vector size mismatch" << std::endl;
        return false;
    }

    g1_set_infty(aggregated_signature);
    g1_set_infty(aggregated_message);

    for(uint32_t i = 0; i < data.size(); i++) {
        g1_copy(intermediate_signature, signatures.at(i).get_signature());
        g1_map(intermediate_message, data.at(i).data(), data.at(i).size());

        g1_add(aggregated_signature, aggregated_signature, intermediate_signature);
        g1_add(aggregated_message, aggregated_message, intermediate_message);

        g1_null(intermediate_message);
        g1_null(intermediate_signature);

        g1_norm(aggregated_message, aggregated_message);
        g1_norm(aggregated_signature, aggregated_signature);
    }

    int result = modified_bls_ver(aggregated_signature, aggregated_message, public_key_);

    g1_free(intermediate_signature);
    g1_free(intermediate_message);

    g1_free(aggregated_signature);
    g1_free(aggregated_message);

    if(result == 1)
        return true;
    else
        return false;
}


bool PublicKey::verify(std::vector<uint8_t> &message, Signature &signature) {
    g1_t g1_signature;

    g1_copy(g1_signature, signature.get_signature());
    int result = cp_bls_ver(g1_signature, message.data(), message.size(), public_key_);

    if (result != 1)
        std::cout << "Public Key "<< index_ << ": broken signature" << std::endl;

    if (result == 1)
        return true;
    else
        return false;
}

void PublicKey::get_public_key(g2_t public_key) {
    g2_copy(public_key, public_key_);
}

size_t PublicKey::get_index() const {
    return index_;
}

void PublicKey::print() {
    g2_print(public_key_);
}

void PublicKey::print_encoded() {
    size_t encoded_length = g2_size_bin(public_key_, true);

    std::vector<uint8_t> encoded_public_key(encoded_length);
    g2_write_bin(encoded_public_key.data(), encoded_public_key.size(), public_key_, true);

    std::stringstream ss;
    for(uint8_t c : encoded_public_key)
        ss << std::hex << std::setfill('0') << std::setw(2) << (int) c;

    std::cout << ss.str() << std::endl;
}
