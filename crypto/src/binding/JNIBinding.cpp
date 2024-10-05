//
// Created by Alexander Heß on 21.02.24.
//
#include <iostream>
#include <vector>
#include <cstdint>
#include <stdexcept>
#include <memory>
#include <string>
#include <sstream>
#include <iomanip>
#include <unordered_map>

extern "C" {
#include <relic/relic.h>
}

#include "binding/cart_binding_JNIBinding.h"
#include "bls/PrivateKey.h"
#include "bls/PublicKey.h"
#include "bls/util.h"

std::unordered_map<uint32_t, std::shared_ptr<std::vector<PrivateKey>>> private_keys_;

std::unordered_map<uint32_t, std::shared_ptr<std::vector<PublicKey>>> public_keys_;

std::unordered_map<uint32_t, std::shared_ptr<PublicKey>> group_public_keys_;

uint32_t num_signers_;
uint32_t max_faults_;
int32_t index_;

bool signing_initialized_;
bool verification_initialized_;

enum VerificationStrategy {
    DEFAULT = 0, OPTIMISTIC = 1, NON_VERIFIED = 2
};

bool init_relic() {
    if (core_get() == nullptr) {
        if (core_init() != RLC_OK) {
            core_clean();
            std::cerr << "Error: could not initialize the relic library" << std::endl;
            return false;
        }

        if (pc_param_set_any() != RLC_OK) {
            core_clean();
            std::cerr << "Error: could not initialize the relic library" << std::endl;
            return false;
        }
    }
    return true;
}


/*
 * Class:     cart_binding_JNIBinding
 * Method:    init_keys
 * Signature: (I[III)Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_init_1keys
        (JNIEnv *env, jclass, jint num_signers, jintArray thresholds, jint max_faults, jint index) {

    std::vector<uint32_t> thresholds_(env->GetArrayLength(thresholds));
    env->GetIntArrayRegion(thresholds, 0, env->GetArrayLength(thresholds),
                           reinterpret_cast<jint *>(thresholds_.data()));

    if (num_signers < 2) {
        std::cerr << "Initialization failed: number of signers " << num_signers << " is too low" << std::endl;
        throw new std::invalid_argument("Invalid parameters.");
    }

    for (auto threshold: thresholds_) {
        if (num_signers < threshold) {
            std::cerr << "Initialization failed: number of signers " << num_signers << " is smaller than threshold "
                      << threshold << std::endl;
            throw new std::invalid_argument("Invalid parameters.");
        }

        if (threshold < 1) {
            std::cerr << "Initialization failed: threshold " << threshold << " is too low" << std::endl;
            throw new std::invalid_argument("Invalid parameters.");
        }
    }

    if (index < 0 || index >= num_signers)
        throw new std::out_of_range("Invalid signer index.");

    num_signers_ = static_cast<uint32_t>(num_signers);
    max_faults_ = static_cast<uint32_t>(max_faults);
    index_ = static_cast<uint32_t>(index);

    if (!init_relic())
        return false;

    for (uint32_t t: thresholds_) {
        auto keys = util::generate_sample_keys(num_signers, t);

        private_keys_.insert(std::make_pair(t, std::make_shared<std::vector<PrivateKey>>(keys.first.first)));
        public_keys_.insert(std::make_pair(t, std::make_shared<std::vector<PublicKey>>(keys.first.second)));
        group_public_keys_.insert(std::make_pair(t, std::make_shared<PublicKey>(keys.second)));
    }

    signing_initialized_ = true;
    verification_initialized_ = true;
    std::cout << "JNI initialization was successful" << std::endl;
    return true;
}

/*
 * Class:     cart_binding_JNIBinding
 * Method:    init_relic
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_init_1relic(JNIEnv *, jclass) {
    return init_relic();
}

/*
 * Class:     cart_bindings_JNIBindings
 * Method:    getGroupPublicKey
 * Signature: (I)[B
 */
JNIEXPORT jbyteArray JNICALL Java_cart_binding_JNIBinding_getGroupPublicKey
    (JNIEnv *env, jclass, jint threshold) {
    if (!signing_initialized_)
        throw new std::runtime_error("Error: not initialized!");

    g2_t public_key_;
    g2_new(public_key_);

    group_public_keys_.at(threshold)->get_public_key(public_key_);

    //std::cout << "Retrieving public key for threshold " << threshold << std::endl;
    //group_public_keys_.at(threshold)->print();
    //std::cout << std::endl;

    size_t encoded_length = g2_size_bin(public_key_, true);
    std::vector<uint8_t> encoded_public_key(encoded_length);
    g2_write_bin(encoded_public_key.data(), encoded_public_key.size(), public_key_, true);

    g2_free(public_key_);

    jbyteArray output_array = env->NewByteArray(encoded_length);
    env->SetByteArrayRegion(output_array, 0, encoded_length,
                            reinterpret_cast<jbyte *>(encoded_public_key.data()));

    return output_array;
}

/*
 * Class:     cart_binding_JNIBinding
 * Method:    initPublicKeys
 * Signature: ([[B[I)Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_initPublicKeys
    (JNIEnv *env, jclass, jobjectArray public_keys, jintArray thresholds) {

    size_t num_keys_ = env->GetArrayLength(public_keys);

    for (int i = 0; i < num_keys_; i++) {
        auto encoded_public_key_ = (jbyteArray) env->GetObjectArrayElement(public_keys, i);

        uint8_t key_length = env->GetArrayLength(encoded_public_key_);

        std::vector<uint8_t> public_key_(key_length);
        env->GetByteArrayRegion(encoded_public_key_, 0, key_length, reinterpret_cast<jbyte *>(public_key_.data()));

        uint32_t threshold_ = 0;
        env->GetIntArrayRegion(thresholds, i, 1, reinterpret_cast<jint *>(&threshold_));

        group_public_keys_.insert(std::make_pair(threshold_, std::make_shared<PublicKey>(public_key_)));
    }

    verification_initialized_ = true;
    std::cout << "Public Key initialization was successful" << std::endl;
    return true;
}

/*
 * Class:     cart_bindings_JNIBindings
 * Method:    sign
 * Signature: ([B)[B
 */

JNIEXPORT jbyteArray JNICALL Java_cart_binding_JNIBinding_sign
    (JNIEnv *env, jclass, jbyteArray data, jint threshold) {
    if (!signing_initialized_)
        throw new std::runtime_error("Error: not initialized!");

    std::vector<uint8_t> data_(env->GetArrayLength(data));
    env->GetByteArrayRegion(data, 0, env->GetArrayLength(data), reinterpret_cast<jbyte *>(data_.data()));

    std::vector<uint8_t> encoded_signature;
    private_keys_.at(threshold)->at(index_).sign(data_, &encoded_signature);

    // write to the output array
    jbyteArray output_array = env->NewByteArray(encoded_signature.size());
    env->SetByteArrayRegion(output_array, 0, encoded_signature.size(),
                            reinterpret_cast<jbyte *>(encoded_signature.data()));

    return output_array;
}

/*
 * Class:     cart_bindings_JNIBindings
 * Method:    verify
 * Signature: ([B[B)Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_verify
    (JNIEnv *env, jclass, jbyteArray data, jbyteArray signature, jint signerIndex, jint threshold) {
    if (!signing_initialized_)
        throw new std::runtime_error("Error: not initialized!");

    std::vector<uint8_t> data_(env->GetArrayLength(data));
    env->GetByteArrayRegion(data, 0, env->GetArrayLength(data), reinterpret_cast<jbyte *>(data_.data()));

    std::vector<uint8_t> signature_(env->GetArrayLength(signature));
    env->GetByteArrayRegion(signature, 0, env->GetArrayLength(signature), reinterpret_cast<jbyte *>(signature_.data()));

    int32_t index = signerIndex - 1;
    if (index >= 0 && index < num_signers_) {
        std::cout << "Verifying using key with index" << index << std::endl;
        return public_keys_.at(threshold)->at(index).verify(data_, signature_);
    } else {
        throw new std::runtime_error("Error: unknown signer index used for verification");
    }
}

/*
 * Class:     cart_binding_JNIBinding
 * Method:    verifyPublic
 * Signature: ([B[BI)Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_verifyPublic
        (JNIEnv *env, jclass, jbyteArray data, jbyteArray signature, jint threshold) {
    if (!verification_initialized_)
        throw new std::runtime_error("Error: not initialized!");

    std::vector<uint8_t> data_(env->GetArrayLength(data));
    env->GetByteArrayRegion(data, 0, env->GetArrayLength(data), reinterpret_cast<jbyte *>(data_.data()));

    std::vector<uint8_t> signature_(env->GetArrayLength(signature));
    env->GetByteArrayRegion(signature, 0, env->GetArrayLength(signature), reinterpret_cast<jbyte *>(signature_.data()));

    return group_public_keys_.at(threshold)->verify(data_, signature_);
}


/*
 * Class:     cart_binding_JNIBinding
 * Method:    aggregate
 * Signature: ([[B[I[BII)[B
 */
JNIEXPORT jbyteArray JNICALL Java_cart_binding_JNIBinding_aggregate
    (JNIEnv *env, jclass, jobjectArray signature_shares, jintArray indices, jbyteArray data, jint threshold,
     jint verification_strategy) {
    if (!signing_initialized_)
        throw std::runtime_error("Error: not initialized!");

    if (signature_shares == nullptr) {
        std::cout << "Aggregate JNI Error: signature shares array is null" << std::endl;
        return nullptr;
    }

    std::vector<uint8_t> data_(env->GetArrayLength(data));
    env->GetByteArrayRegion(data, 0, env->GetArrayLength(data), reinterpret_cast<jbyte *>(data_.data()));

    auto verification_strategy_ = static_cast<VerificationStrategy>(verification_strategy);

    std::vector<Signature> signature_shares_;
    signature_shares_.reserve(threshold);
    for (int i = 0; i < threshold; i++) {
        auto encoded_share_ = (jbyteArray) env->GetObjectArrayElement(signature_shares, i);

        if (encoded_share_ == nullptr) {
            std::cout << "Aggregate JNI Error: signature share at position " << i << " is null" << std::endl;
            return nullptr;
        }
        uint8_t sig_length = env->GetArrayLength(encoded_share_);

        std::vector<uint8_t> signature_share_(sig_length);
        env->GetByteArrayRegion(encoded_share_, 0, sig_length, reinterpret_cast<jbyte *>(signature_share_.data()));

        uint32_t signer_index_ = 0;
        env->GetIntArrayRegion(indices, i, 1, reinterpret_cast<jint *>(&signer_index_));
        signature_shares_.emplace_back(signature_share_, signer_index_);
    }

    // Opt for the optimistic strategy first, if specified
    if (verification_strategy_ != DEFAULT) {
        Signature aggregated_signature(signature_shares_);
        if (verification_strategy_ == NON_VERIFIED ||
            group_public_keys_.at(threshold)->verify(data_, aggregated_signature)) {
            size_t sig_size = g1_size_bin(aggregated_signature.get_signature(), 1);

            // assemble the encoded signature
            std::vector<uint8_t> encoded_signature(sig_size);
            g1_write_bin(encoded_signature.data(), encoded_signature.size(), aggregated_signature.get_signature(), 1);
            jbyteArray output_array = env->NewByteArray(encoded_signature.size());
            env->SetByteArrayRegion(output_array, 0, encoded_signature.size(),
                                    reinterpret_cast<jbyte *>(encoded_signature.data()));
            return output_array;
        }
        std::cout << "Optimistic strategy failed" << std::endl;
    }

    // Use the pessimistic strategy as a backup
    std::vector<Signature> verified_shares_;
    verified_shares_.reserve(threshold);
    for (uint32_t i = 0; i < signature_shares_.size() && verified_shares_.size() < threshold; i++) {
        if (public_keys_.at(threshold)->at(signature_shares_.at(i).get_index() - 1).verify(data_,
                                                                                       signature_shares_.at(i))) {
            verified_shares_.emplace_back(signature_shares_.at(i));
        } else {
            std::cout << "Detected invalid share with index=" << signature_shares_.at(i).get_index() -1 << std::endl;
        }
    }

    if (verified_shares_.size() >= threshold) {
        Signature aggregated_signature(verified_shares_);
        size_t sig_size = g1_size_bin(aggregated_signature.get_signature(), 1);

        // assemble the encoded signature
        std::vector<uint8_t> encoded_signature(sig_size);
        g1_write_bin(encoded_signature.data(), encoded_signature.size(), aggregated_signature.get_signature(), 1);
        jbyteArray output_array = env->NewByteArray(encoded_signature.size());
        env->SetByteArrayRegion(output_array, 0, encoded_signature.size(),
                                reinterpret_cast<jbyte *>(encoded_signature.data()));
        return output_array;
    }

    return nullptr;
}

/*
 * Class:     cart_binding_JNIBinding
 * Method:    aggregateVerify
 * Signature: ([[B[[BII)Z
 */
JNIEXPORT jboolean JNICALL
Java_cart_binding_JNIBinding_aggregateVerify
    (JNIEnv *env, jclass, jobjectArray data, jobjectArray signatures, jint signerIndex, jint threshold) {
    if (!signing_initialized_)
        throw new std::runtime_error("Error: not initialized!");

    if (signatures == nullptr) {
        std::cout << "Aggregate Verify JNI Error: signature array is null" << std::endl;
        return false;
    }

    if (data == nullptr) {
        std::cout << "Aggregate Verify JNI Error: data array is null" << std::endl;
        return false;
    }

    uint32_t num_signatures = env->GetArrayLength(signatures);
    if (env->GetArrayLength(data) != num_signatures) {
        std::cout << "Aggregate Verify JNI Error: array dimensions do not match" << std::endl;
        return false;
    }

    std::vector<std::vector<uint8_t>> data_;
    std::vector<std::vector<uint8_t>> signatures_;

    data_.reserve(num_signatures);
    signatures_.reserve(num_signatures);

    for (uint32_t i = 0; i < num_signatures; i++) {
        auto encoded_data_ = (jbyteArray) env->GetObjectArrayElement(data, i);

        if (encoded_data_ == nullptr) {
            std::cout << "Aggregate Verify JNI Error: encoded data at position " << i << " is null" << std::endl;
            return false;
        }

        uint8_t data_length = env->GetArrayLength(encoded_data_);

        std::vector<uint8_t> signed_data_(data_length);
        env->GetByteArrayRegion(encoded_data_, 0, data_length, reinterpret_cast<jbyte *>(signed_data_.data()));
        data_.emplace_back(signed_data_);

        auto encoded_signature_ = (jbyteArray) env->GetObjectArrayElement(signatures, i);

        if (encoded_signature_ == nullptr) {
            std::cout << "Aggregate Verify JNI Error: encoded signature is null" << std::endl;
            return false;
        }
        uint8_t sig_length = env->GetArrayLength(encoded_signature_);

        std::vector<uint8_t> signature_(sig_length);
        env->GetByteArrayRegion(encoded_signature_, 0, sig_length, reinterpret_cast<jbyte *>(signature_.data()));
        signatures_.emplace_back(signature_);
    }

    int index = signerIndex - 1;
    if (index == -1) {
        return group_public_keys_.at(threshold)->aggregatedVerify(data_, signatures_);
    } else if (index >= 0 && index < num_signers_) {
        return public_keys_.at(threshold)->at(index).aggregatedVerify(data_, signatures_);
    } else {
        std::cerr << "Error: unknown signer index used for verification" << std::endl;
        throw new std::runtime_error("Error: unknown signer index used for verification");
    }
}
