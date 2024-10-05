//
// Created by Alexander Heß on 15.08.22.
//

#include <cstdlib>

#include "bls/Polynomial.h"

Polynomial::Polynomial(size_t degree, size_t seed) : degree_(degree) {
    bn_new(group_order_);
    ep_curve_get_ord(group_order_);

    coefficients_.reserve(degree_+1);
    // This is used for non-productive deployments only
    srand(seed);
    for(uint32_t i = 0; i <= degree_; i++) {
        bn_st* coefficient = new bn_st;
        bn_new(coefficient);

        bn_set_dig(coefficient, rand());
        bn_mod_basic(coefficient, coefficient, group_order_);

        coefficients_.push_back(coefficient);
    }
}

Polynomial::~Polynomial() {
    bn_clean(group_order_);
    for(int i = 0; i <= degree_; i++) {
        bn_clean(coefficients_.at(i));
        delete coefficients_.at(i);
    }
}

bn_st* Polynomial::evaluate(size_t x) {
    bn_st* result = new bn_st;
    bn_new(result);
    bn_zero(result);

    bn_t intermediate;
    bn_new(intermediate);
    size_t power = 1;
    for (int j = 0; j <= degree_; j++) {
        bn_zero(intermediate);
        bn_mul_dig(intermediate, coefficients_[j], power);
        bn_add(result, result, intermediate);
        power *= x;
    }

    bn_clean(intermediate);
    bn_mod_basic(result, result, group_order_);
    return result;
}