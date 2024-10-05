//
// Created by Alexander Heß on 07.06.24.
//

extern "C" {
#include <relic/relic.h>
}

#include <memory>
#include <unordered_map>
#include <iostream>

#include "binding/FFMBinding.h"
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

enum VerificationStrategy {
    DEFAULT = 0, OPTIMISTIC = 1, NON_VERIFIED = 2
};

extern "C" int init_relic() {
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
        return true;
    }
    return false;
}

extern "C" int init_keys(int num_signers, const int* thresholds, int num_thresholds, int max_faults, int signerIndex) {
    std::vector<int> thresholds_(thresholds, thresholds+num_thresholds);
    if (num_signers < 2) {
        std::cerr << "Initialization failed: number of signers " << num_signers << " is too low" << std::endl;
        throw std::invalid_argument("Invalid parameters.");
    }

    for(auto threshold : thresholds_) {
        if (num_signers < threshold) {
            std::cerr << "Initialization failed: number of signers " << num_signers << " is smaller than threshold " << threshold << std::endl;
            throw std::invalid_argument("Invalid parameters.");
        }

        if(threshold < 1) {
            std::cerr << "Initialization failed: threshold " << threshold << " is too low" << std::endl;
            throw std::invalid_argument("Invalid parameters.");
        }
    }

    if (signerIndex < 1 || signerIndex > num_signers)
        throw std::out_of_range("Invalid signer index.");

    num_signers_ = num_signers;
    max_faults_ = max_faults;
    index_ = signerIndex - 1;

    if (!init_relic())
        return 1;

    for (uint32_t t: thresholds_) {
        auto keys = util::generate_sample_keys(num_signers, t);

        private_keys_.insert(std::make_pair(t, std::make_shared<std::vector<PrivateKey>>(keys.first.first)));
        public_keys_.insert(std::make_pair(t,std::make_shared<std::vector<PublicKey>>(keys.first.second)));
        group_public_keys_.insert(std::make_pair(t, std::make_shared<PublicKey>(keys.second)));
    }

    signing_initialized_ = true;
    std::cout << "FFM initialization was successful" << std::endl;
    return 0;

}


extern "C" int sign(const unsigned char* data, int data_len, unsigned char* signature, int threshold) {
    if (!signing_initialized_)
        throw std::runtime_error("Error: not initialized!");

    return private_keys_.at(threshold)->at(index_).sign(data, data_len, signature);
}

extern "C" int verify(const unsigned char* data, int data_len, const unsigned char* signature, int sig_len, int signer_index, int threshold) {
    if (!signing_initialized_)
        throw std::runtime_error("Error: not initialized!");

    int32_t index = signer_index - 1;
    if(index == -1) {
        return group_public_keys_.at(threshold)->verify(data, data_len, signature, sig_len);
    } else if(index >= 0 && index < num_signers_) {
        return public_keys_.at(threshold)->at(index).verify(data, data_len, signature, sig_len);
    } else {
        throw std::runtime_error("Error: unknown signer index used for verification");
    }
}

/*

std::vector<uint8_t> ffm::sign(std::vector<uint8_t> &data, uint32_t threshold) {
    std::vector<uint8_t> signature;
    private_keys_.at(threshold)->at(index_).sign(data, &signature);

    return signature;
}

bool ffm::verify(std::vector<uint8_t> &data, std::vector<uint8_t> &signature,uint32_t signer_index, uint32_t threshold) {
    if (!signing_initialized_)
        throw new std::runtime_error("Error: not initialized!");

    int32_t index = signer_index -1;
    if(index == -1) {
        return group_public_keys_.at(threshold)->verify(data, signature);
    } else if(index >= 0 && index < num_signers_) {
        return public_keys_.at(threshold)->at(index).verify(data, signature);
    } else {
        throw new std::runtime_error("Error: unknown signer index used for verification");
    }
}
*/