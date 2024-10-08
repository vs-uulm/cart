/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class cart_binding_JNIBinding */

#ifndef _Included_cart_binding_JNIBinding
#define _Included_cart_binding_JNIBinding
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     cart_binding_JNIBinding
 * Method:    init_keys
 * Signature: (I[III)Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_init_1keys
        (JNIEnv *, jclass, jint, jintArray, jint, jint);

/*
 * Class:     cart_binding_JNIBinding
 * Method:    init_relic
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_init_1relic
        (JNIEnv *, jclass);

/*
 * Class:     cart_binding_JNIBinding
 * Method:    getGroupPublicKey
 * Signature: (I)[B
 */
JNIEXPORT jbyteArray JNICALL Java_cart_binding_JNIBinding_getGroupPublicKey
        (JNIEnv *, jclass, jint);

/*
 * Class:     cart_binding_JNIBinding
 * Method:    initPublicKeys
 * Signature: ([[B[I)Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_initPublicKeys
        (JNIEnv *, jclass, jobjectArray, jintArray);

/*
 * Class:     cart_binding_JNIBinding
 * Method:    sign
 * Signature: ([BI)[B
 */
JNIEXPORT jbyteArray JNICALL Java_cart_binding_JNIBinding_sign
        (JNIEnv *, jclass, jbyteArray, jint);

/*
 * Class:     cart_binding_JNIBinding
 * Method:    verify
 * Signature: ([B[BII)Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_verify
        (JNIEnv *, jclass, jbyteArray, jbyteArray, jint, jint);

/*
 * Class:     cart_binding_JNIBinding
 * Method:    verifyPublic
 * Signature: ([B[BI)Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_verifyPublic
        (JNIEnv *, jclass, jbyteArray, jbyteArray, jint);

/*
 * Class:     cart_binding_JNIBinding
 * Method:    aggregate
 * Signature: ([[B[I[BII)[B
 */
JNIEXPORT jbyteArray JNICALL Java_cart_binding_JNIBinding_aggregate
        (JNIEnv *, jclass, jobjectArray, jintArray, jbyteArray, jint, jint);

/*
 * Class:     cart_binding_JNIBinding
 * Method:    aggregateVerify
 * Signature: ([[B[[BII)Z
 */
JNIEXPORT jboolean JNICALL Java_cart_binding_JNIBinding_aggregateVerify
        (JNIEnv *, jclass, jobjectArray, jobjectArray, jint, jint);

#ifdef __cplusplus
}
#endif
#endif
