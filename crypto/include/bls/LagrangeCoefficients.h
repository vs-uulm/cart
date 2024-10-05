//
// Created by Alexander Heß on 06.03.24.
//

#include <set>
#include <unordered_map>
#include <memory>
#include <vector>

extern "C" {
    #include <relic/relic.h>
};

#ifndef THRESHSIG_WRAPPER_LAGRANGECOEFFICIENTS_H
#define THRESHSIG_WRAPPER_LAGRANGECOEFFICIENTS_H

#include <cstdint>

class LagrangeCoefficients {
public:
    LagrangeCoefficients(uint32_t n, uint32_t t);

    const std::unordered_map<uint32_t, bn_st>& get_coefficients(const std::vector<uint32_t>& indices);

private:
    uint32_t n_;
    uint32_t t_;

    bn_t group_order_;

    void compute_coefficients(std::vector<uint32_t>& indices, uint32_t len, uint32_t startPosition, std::vector<uint32_t>& combination);

    std::unique_ptr<std::unordered_map<uint32_t, std::unordered_map<uint32_t, bn_st>>> lagrange_coefficients_;
};

#endif //THRESHSIG_WRAPPER_LAGRANGECOEFFICIENTS_H
