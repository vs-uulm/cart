//
// Created by Alexander Heß on 07.06.24.
//

#ifndef THRESHSIG_WRAPPER_CART_FFM_BINDING_H
#define THRESHSIG_WRAPPER_CART_FFM_BINDING_H

#ifdef __cplusplus
extern "C" {
#endif

    extern int init_relic(void);

    extern int init_keys(int num_signers, const int* thresholds, int num_threshold, int max_faults, int signerIndex);

    extern int sign(const unsigned char* data, int data_len, unsigned char* signature, int threshold);

    extern int verify(const unsigned char* data, int data_len, const unsigned char* signature, int sig_len, int signer_index, int threshold);

#ifdef __cplusplus
}
#endif
#endif //THRESHSIG_WRAPPER_CART_FFM_BINDING_H
