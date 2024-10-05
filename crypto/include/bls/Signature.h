//
// Created by Alexander Heß on 16.08.22.
//

#ifndef THRESHSIG_WRAPPER_SIGNATURE_H
#define THRESHSIG_WRAPPER_SIGNATURE_H

#include <vector>
#include <string>
#include <unordered_map>

extern "C" {
    #include <relic/relic.h>
};

class Signature {
private:
    int index_;

    g1_t signature_;

public:

    Signature(g1_t signature, size_t index=-1);

    Signature(std::vector<uint8_t>& signature, size_t index=-1);

    Signature(const std::string& signature, size_t index=-1);

    // Constructor for assembling a threshold signature
    Signature(std::vector<Signature>& sig_shares);

    // Constructor for assembling a threshold signature using pre-computed Lagrange coefficients
    Signature(std::vector<Signature>& sig_shares, const std::unordered_map<uint32_t, bn_st>& lagrange_coefficients);

    // Experimental constructor for assembling a threshold signature
    Signature(std::vector<Signature>& sig_shares, uint32_t n, uint32_t t, uint32_t f, uint32_t trusted_index);

    ~Signature();

    int get_index();

    const g1_st* get_signature();

    std::string to_string();

    void print();
};


#endif //THRESHSIG_WRAPPER_SIGNATURE_H
