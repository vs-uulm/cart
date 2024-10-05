//
// Created by Alexander Heß on 15.08.22.
//

#ifndef THRESHSIG_WRAPPER_PRIVATEKEY_H
#define THRESHSIG_WRAPPER_PRIVATEKEY_H


#include <vector>
#include <mutex>
#include "Signature.h"

extern "C" {
#include <relic/relic.h>
};

class PrivateKey {
private:
    bn_t private_key_;

    //optional index that indicates whether this is a private key-share
    int index_;

public:
    PrivateKey();

    PrivateKey(int index);

    PrivateKey(bn_t private_key, int index);

    Signature sign(std::vector<uint8_t>& data);

    void sign(std::vector<uint8_t>& data, std::vector<uint8_t>* signature);

    size_t sign(const unsigned char* data, int data_len, unsigned char* signature);

    ~PrivateKey();

    int get_index();

    void get_key(bn_t value);

    void print();
};


#endif //THRESHSIG_WRAPPER_PRIVATEKEY_H
