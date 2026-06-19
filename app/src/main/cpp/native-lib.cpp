#include <jni.h>
#include <sodium.h>
#include <string.h>
#include <stdlib.h>

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    if (sodium_init() < 0) {
        return -1;
    }
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_secretboxEasy(
        JNIEnv* env,
        jobject,
        jbyteArray input,
        jbyteArray nonce,
        jbyteArray key
) {
    jsize len = env->GetArrayLength(input);

    unsigned char* m = (unsigned char*) malloc(len);
    unsigned char* n = (unsigned char*) malloc(crypto_secretbox_NONCEBYTES);
    unsigned char* k = (unsigned char*) malloc(crypto_secretbox_KEYBYTES);

    env->GetByteArrayRegion(input, 0, len, (jbyte*)m);
    env->GetByteArrayRegion(nonce, 0, crypto_secretbox_NONCEBYTES, (jbyte*)n);
    env->GetByteArrayRegion(key, 0, crypto_secretbox_KEYBYTES, (jbyte*)k);

    unsigned long long clen = len + crypto_secretbox_MACBYTES;
    unsigned char* c = (unsigned char*) malloc(clen);

    crypto_secretbox_easy(c, m, len, n, k);

    jbyteArray out = env->NewByteArray(clen);
    env->SetByteArrayRegion(out, 0, clen, (jbyte*)c);

    free(m); free(n); free(k); free(c);

    return out;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_secretboxOpenEasy(
        JNIEnv* env,
        jobject,
        jbyteArray input,
        jbyteArray nonce,
        jbyteArray key
) {
    jsize len = env->GetArrayLength(input);

    unsigned char* c = (unsigned char*) malloc(len);
    unsigned char* n = (unsigned char*) malloc(crypto_secretbox_NONCEBYTES);
    unsigned char* k = (unsigned char*) malloc(crypto_secretbox_KEYBYTES);

    env->GetByteArrayRegion(input, 0, len, (jbyte*)c);
    env->GetByteArrayRegion(nonce, 0, crypto_secretbox_NONCEBYTES, (jbyte*)n);
    env->GetByteArrayRegion(key, 0, crypto_secretbox_KEYBYTES, (jbyte*)k);

    unsigned char* m = (unsigned char*) malloc(len);

    if (crypto_secretbox_open_easy(m, c, len, n, k) != 0) {
        free(m); free(c); free(n); free(k);
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(len - crypto_secretbox_MACBYTES);
    env->SetByteArrayRegion(out, 0, len - crypto_secretbox_MACBYTES, (jbyte*)m);

    free(m); free(c); free(n); free(k);

    return out;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_kxKeypair(
        JNIEnv* env,
        jobject
) {
    unsigned char pk[crypto_kx_PUBLICKEYBYTES];
    unsigned char sk[crypto_kx_SECRETKEYBYTES];

    crypto_kx_keypair(pk, sk);

    jbyteArray pub = env->NewByteArray(crypto_kx_PUBLICKEYBYTES);
    jbyteArray sec = env->NewByteArray(crypto_kx_SECRETKEYBYTES);

    env->SetByteArrayRegion(pub, 0, crypto_kx_PUBLICKEYBYTES, (jbyte*)pk);
    env->SetByteArrayRegion(sec, 0, crypto_kx_SECRETKEYBYTES, (jbyte*)sk);

    jclass cls = env->FindClass("com/example/carriex/crypto/model/KeyPair");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([B[B)V");

    return env->NewObject(cls, ctor, pub, sec);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_kxClientSessionKeys(
        JNIEnv* env,
        jobject,
        jbyteArray clientPk,
        jbyteArray clientSk,
        jbyteArray serverPk
) {
    unsigned char cp[crypto_kx_PUBLICKEYBYTES];
    unsigned char cs[crypto_kx_SECRETKEYBYTES];
    unsigned char sp[crypto_kx_PUBLICKEYBYTES];
    unsigned char rx[crypto_kx_SESSIONKEYBYTES];
    unsigned char tx[crypto_kx_SESSIONKEYBYTES];

    env->GetByteArrayRegion(clientPk, 0, crypto_kx_PUBLICKEYBYTES, (jbyte*)cp);
    env->GetByteArrayRegion(clientSk, 0, crypto_kx_SECRETKEYBYTES, (jbyte*)cs);
    env->GetByteArrayRegion(serverPk, 0, crypto_kx_PUBLICKEYBYTES, (jbyte*)sp);

    if (crypto_kx_client_session_keys(rx, tx, cp, cs, sp) != 0) {
        return nullptr;
    }

    jbyteArray rx_arr = env->NewByteArray(crypto_kx_SESSIONKEYBYTES);
    jbyteArray tx_arr = env->NewByteArray(crypto_kx_SESSIONKEYBYTES);

    env->SetByteArrayRegion(rx_arr, 0, crypto_kx_SESSIONKEYBYTES, (jbyte*)rx);
    env->SetByteArrayRegion(tx_arr, 0, crypto_kx_SESSIONKEYBYTES, (jbyte*)tx);

    jclass cls = env->FindClass("com/example/carriex/crypto/model/SessionKeys");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([B[B)V");

    return env->NewObject(cls, ctor, rx_arr, tx_arr);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_kxServerSessionKeys(
        JNIEnv* env,
        jobject,
        jbyteArray serverPk,
        jbyteArray serverSk,
        jbyteArray clientPk
) {
    unsigned char sp[crypto_kx_PUBLICKEYBYTES];
    unsigned char ss[crypto_kx_SECRETKEYBYTES];
    unsigned char cp[crypto_kx_PUBLICKEYBYTES];
    unsigned char rx[crypto_kx_SESSIONKEYBYTES];
    unsigned char tx[crypto_kx_SESSIONKEYBYTES];

    env->GetByteArrayRegion(serverPk, 0, crypto_kx_PUBLICKEYBYTES, (jbyte*)sp);
    env->GetByteArrayRegion(serverSk, 0, crypto_kx_SECRETKEYBYTES, (jbyte*)ss);
    env->GetByteArrayRegion(clientPk, 0, crypto_kx_PUBLICKEYBYTES, (jbyte*)cp);

    if (crypto_kx_server_session_keys(rx, tx, sp, ss, cp) != 0) {
        return nullptr;
    }

    jbyteArray rx_arr = env->NewByteArray(crypto_kx_SESSIONKEYBYTES);
    jbyteArray tx_arr = env->NewByteArray(crypto_kx_SESSIONKEYBYTES);

    env->SetByteArrayRegion(rx_arr, 0, crypto_kx_SESSIONKEYBYTES, (jbyte*)rx);
    env->SetByteArrayRegion(tx_arr, 0, crypto_kx_SESSIONKEYBYTES, (jbyte*)tx);

    jclass cls = env->FindClass("com/example/carriex/crypto/model/SessionKeys");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([B[B)V");

    return env->NewObject(cls, ctor, rx_arr, tx_arr);
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_boxSeal(
        JNIEnv* env,
        jobject,
        jbyteArray input,
        jbyteArray pk
) {
    jsize len = env->GetArrayLength(input);

    unsigned char* m = (unsigned char*) malloc(len);
    unsigned char* pub = (unsigned char*) malloc(crypto_box_PUBLICKEYBYTES);

    env->GetByteArrayRegion(input, 0, len, (jbyte*)m);
    env->GetByteArrayRegion(pk, 0, crypto_box_PUBLICKEYBYTES, (jbyte*)pub);

    unsigned long long clen = len + crypto_box_SEALBYTES;
    unsigned char* c = (unsigned char*) malloc(clen);

    crypto_box_seal(c, m, len, pub);

    jbyteArray out = env->NewByteArray(clen);
    env->SetByteArrayRegion(out, 0, clen, (jbyte*)c);

    free(m); free(pub); free(c);

    return out;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_boxSealOpen(
        JNIEnv* env,
        jobject,
        jbyteArray input,
        jbyteArray pk,
        jbyteArray sk
) {
    jsize len = env->GetArrayLength(input);

    unsigned char* c = (unsigned char*) malloc(len);
    unsigned char* pub = (unsigned char*) malloc(crypto_box_PUBLICKEYBYTES);
    unsigned char* sec = (unsigned char*) malloc(crypto_box_SECRETKEYBYTES);

    env->GetByteArrayRegion(input, 0, len, (jbyte*)c);
    env->GetByteArrayRegion(pk, 0, crypto_box_PUBLICKEYBYTES, (jbyte*)pub);
    env->GetByteArrayRegion(sk, 0, crypto_box_SECRETKEYBYTES, (jbyte*)sec);

    unsigned char* m = (unsigned char*) malloc(len);

    if (crypto_box_seal_open(m, c, len, pub, sec) != 0) {
        free(m); free(c); free(pub); free(sec);
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(len - crypto_box_SEALBYTES);
    env->SetByteArrayRegion(out, 0, len - crypto_box_SEALBYTES, (jbyte*)m);

    free(m); free(c); free(pub); free(sec);

    return out;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_randombytes(
        JNIEnv* env,
        jobject,
        jint size
) {
    unsigned char* buf = (unsigned char*) malloc(size);
    randombytes_buf(buf, size);

    jbyteArray out = env->NewByteArray(size);
    env->SetByteArrayRegion(out, 0, size, (jbyte*)buf);

    free(buf);
    return out;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_aeadXchacha20poly1305Encrypt(
        JNIEnv* env,
        jobject,
        jbyteArray m,
        jbyteArray ad,
        jbyteArray npub,
        jbyteArray k
) {
    jsize mlen = env->GetArrayLength(m);
    jsize adlen = (ad != nullptr) ? env->GetArrayLength(ad) : 0;

    unsigned char* m_buf = (unsigned char*) malloc(mlen);
    unsigned char* ad_buf = (adlen > 0) ? (unsigned char*) malloc(adlen) : nullptr;
    unsigned char* npub_buf = (unsigned char*) malloc(crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
    unsigned char* k_buf = (unsigned char*) malloc(crypto_aead_xchacha20poly1305_ietf_KEYBYTES);

    env->GetByteArrayRegion(m, 0, mlen, (jbyte*)m_buf);
    if (adlen > 0) {
        env->GetByteArrayRegion(ad, 0, adlen, (jbyte*)ad_buf);
    }
    env->GetByteArrayRegion(npub, 0, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES, (jbyte*)npub_buf);
    env->GetByteArrayRegion(k, 0, crypto_aead_xchacha20poly1305_ietf_KEYBYTES, (jbyte*)k_buf);

    unsigned long long clen = mlen + crypto_aead_xchacha20poly1305_ietf_ABYTES;
    unsigned char* c_buf = (unsigned char*) malloc(clen);

    crypto_aead_xchacha20poly1305_ietf_encrypt(c_buf, &clen, m_buf, mlen, ad_buf, adlen, nullptr, npub_buf, k_buf);

    jbyteArray out = env->NewByteArray(clen);
    env->SetByteArrayRegion(out, 0, clen, (jbyte*)c_buf);

    free(m_buf); if (ad_buf) free(ad_buf); free(npub_buf); free(k_buf); free(c_buf);

    return out;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_aeadXchacha20poly1305Decrypt(
        JNIEnv* env,
        jobject,
        jbyteArray c,
        jbyteArray ad,
        jbyteArray npub,
        jbyteArray k
) {
    jsize clen = env->GetArrayLength(c);
    jsize adlen = (ad != nullptr) ? env->GetArrayLength(ad) : 0;

    unsigned char* c_buf = (unsigned char*) malloc(clen);
    unsigned char* ad_buf = (adlen > 0) ? (unsigned char*) malloc(adlen) : nullptr;
    unsigned char* npub_buf = (unsigned char*) malloc(crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
    unsigned char* k_buf = (unsigned char*) malloc(crypto_aead_xchacha20poly1305_ietf_KEYBYTES);

    env->GetByteArrayRegion(c, 0, clen, (jbyte*)c_buf);
    if (adlen > 0) {
        env->GetByteArrayRegion(ad, 0, adlen, (jbyte*)ad_buf);
    }
    env->GetByteArrayRegion(npub, 0, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES, (jbyte*)npub_buf);
    env->GetByteArrayRegion(k, 0, crypto_aead_xchacha20poly1305_ietf_KEYBYTES, (jbyte*)k_buf);

    unsigned long long mlen = clen - crypto_aead_xchacha20poly1305_ietf_ABYTES;
    unsigned char* m_buf = (unsigned char*) malloc(mlen);

    if (crypto_aead_xchacha20poly1305_ietf_decrypt(m_buf, &mlen, nullptr, c_buf, clen, ad_buf, adlen, npub_buf, k_buf) != 0) {
        free(c_buf); if (ad_buf) free(ad_buf); free(npub_buf); free(k_buf); free(m_buf);
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(mlen);
    env->SetByteArrayRegion(out, 0, mlen, (jbyte*)m_buf);

    free(c_buf); if (ad_buf) free(ad_buf); free(npub_buf); free(k_buf); free(m_buf);

    return out;
}
