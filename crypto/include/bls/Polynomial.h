//
// Created by Alexander Heß on 15.08.22.
//

#ifndef THRESHSIG_WRAPPER_POLYNOMIAL_H
#define THRESHSIG_WRAPPER_POLYNOMIAL_H

#include <vector>
extern "C" {
    #include <relic/relic.h>
};

#include <cwchar>

class Polynomial {
private:
    size_t degree_;

    bn_t group_order_;

    std::vector<bn_st*> coefficients_;

public:
    explicit Polynomial(size_t degree, size_t seed = 1337);

    ~Polynomial();

    bn_st* evaluate(size_t x);
};


#endif //THRESHSIG_WRAPPER_POLYNOMIAL_H
