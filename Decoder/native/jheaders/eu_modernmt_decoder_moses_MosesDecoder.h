/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class eu_modernmt_decoder_moses_MosesDecoder */

#ifndef _Included_eu_modernmt_decoder_moses_MosesDecoder
#define _Included_eu_modernmt_decoder_moses_MosesDecoder
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     eu_modernmt_decoder_moses_MosesDecoder
 * Method:    init
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_eu_modernmt_decoder_moses_MosesDecoder_init
  (JNIEnv *, jobject, jstring);

/*
 * Class:     eu_modernmt_decoder_moses_MosesDecoder
 * Method:    translate
 * Signature: (Ljava/lang/String;)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_eu_modernmt_decoder_moses_MosesDecoder_translate
  (JNIEnv *, jobject, jstring);

/*
 * Class:     eu_modernmt_decoder_moses_MosesDecoder
 * Method:    dispose
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_eu_modernmt_decoder_moses_MosesDecoder_dispose
  (JNIEnv *, jobject);

#ifdef __cplusplus
}
#endif
#endif
