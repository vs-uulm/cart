//
// Created by Alexander Heß on 06.03.24.
//
#include "bls/LagrangeCoefficients.h"

#include <vector>
#include <iostream>

LagrangeCoefficients::LagrangeCoefficients(uint32_t n, uint32_t t) : n_{n}, t_{t} {
    lagrange_coefficients_ = std::make_unique<std::unordered_map<uint32_t, std::unordered_map<uint32_t, bn_st>>>();

    bn_new(group_order_);
    ep_curve_get_ord(group_order_);

    std::vector<uint32_t> indices(n_);
    for(uint32_t i = 0; i < n_; i++)
        indices.at(i) = i+1;

    std::cout << "Generating combinations" << std::endl;
    std::vector<uint32_t> combination(t);
    compute_coefficients(indices, t, 0, combination);

    std::vector<uint32_t> keys;
    keys.reserve(lagrange_coefficients_->size());

    /*
    for(auto& entry : *lagrange_coefficients_) {
        std::cout << "Combination Key:" << entry.first << std::endl;
        for(auto& coefficient : entry.second) {
            std::cout << "Index " << coefficient.first << " ";
            bn_print(&coefficient.second);
        }
    }
    */
}

void LagrangeCoefficients::compute_coefficients(std::vector<uint32_t>& indices, uint32_t length, uint32_t startPosition,
                                                std::vector<uint32_t>& combination) {

    if(length == 0) {
        uint32_t key = 0;
        std::unordered_map<uint32_t, bn_st> lagrange_coefficients;
        for (uint32_t i = 0, weight = 1; i < combination.size(); i++, weight*=10) {
            bn_t coefficient;
            bn_new(coefficient);
            bn_set_dig(coefficient, 1);

            for (uint32_t j = 0; j < combination.size(); j++) {
                if (i != j) {
                    bn_t intermediate;
                    bn_new(intermediate);

                    bn_set_dig(intermediate, combination.at(j));
                    bn_sub_dig(intermediate, intermediate, combination.at(i));
                    bn_mod_basic(intermediate, intermediate, group_order_);
                    bn_mod_inv(intermediate, intermediate, group_order_);

                    bn_mul_dig(intermediate, intermediate, combination.at(j));
                    bn_mod_basic(intermediate, intermediate, group_order_);

                    bn_mul_basic(coefficient, coefficient, intermediate);
                    bn_mod_basic(coefficient, coefficient, group_order_);

                    bn_clean(intermediate);
                }
            }
            bn_st coefficient_;
            bn_copy(&coefficient_, coefficient);
            bn_clean(coefficient);

            key += combination.at(i) * combination.at(i) % UINT32_MAX;
            lagrange_coefficients.emplace(combination.at(i), coefficient_);
        }
        lagrange_coefficients_->emplace(key, lagrange_coefficients);
        /*
        std::cout << "[";
        for(uint32_t i : combination) {
            std::cout << i << " ";
        }
        std::cout << "] with sum: " << key << std::endl;
         */
        return;
    }
    for (uint32_t i = startPosition; i <= indices.size() - length; i++) {
        combination[combination.size() - length] = indices.at(i);
        compute_coefficients(indices, length-1, i+1, combination);
    }
}

const std::unordered_map<uint32_t, bn_st> &LagrangeCoefficients::get_coefficients(const std::vector<uint32_t>& indices) {
    uint32_t key = 0;
    //uint32_t weight = 1;
    for(uint32_t index : indices) {
        //std::cout << index << " ";
        key += index * index % UINT32_MAX;
        //weight *= 10;
    }
    //std::cout << std::endl;
    return lagrange_coefficients_->at(key);
}