//
// Created by Alexander Heß on 05.09.22.
//

#ifndef THRESHSIG_WRAPPER_BLSKEYWRAPPER_H
#define THRESHSIG_WRAPPER_BLSKEYWRAPPER_H

#include <vector>
#include "threshsig-wrapper/bls/PrivateKey.h"
#include "threshsig-wrapper/bls/PublicKey.h"


class BLSKeyWrapper {
private:
    std::shared_ptr<std::vector<PrivateKey>> private_keys_;

    std::shared_ptr<std::vector<PublicKey>> public_keys_;

    std::shared_ptr<PublicKey> common_public_key_;

    size_t signer_index_;

public:
    BLSKeyWrapper(size_t num_signers, size_t threshold, size_t signer_index = -1);

    PrivateKey& get_private_key();

    PublicKey& get_public_key(size_t signer_index);

    PublicKey& get_common_public_key();

    size_t get_signer_index();
};


#endif //THRESHSIG_WRAPPER_BLSKEYWRAPPER_H
