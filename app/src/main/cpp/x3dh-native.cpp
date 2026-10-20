#include <jni.h>
#include <sodium.h>
#include <array>

// All secret arithmetic is delegated to libsodium. XEdDSA composition follows
// signal.org/docs/specifications/xeddsa/ sections 2.3 and 3 (public domain).
static bool length(JNIEnv* env, jbyteArray bytes, int size) {
    return bytes != nullptr && env->GetArrayLength(bytes) == size;
}
static jbyteArray output(JNIEnv* env, const unsigned char* bytes, int size) {
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(bytes));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_x25519(
        JNIEnv* env, jobject, jbyteArray secret, jbyteArray publicKey) {
    if (!length(env, secret, 32) || !length(env, publicKey, 32)) return nullptr;
    unsigned char sk[32], pk[32], shared[32];
    env->GetByteArrayRegion(secret, 0, 32, reinterpret_cast<jbyte*>(sk));
    env->GetByteArrayRegion(publicKey, 0, 32, reinterpret_cast<jbyte*>(pk));
    const int result = crypto_scalarmult_curve25519(shared, sk, pk);
    jbyteArray out = result == 0 ? output(env, shared, 32) : nullptr;
    sodium_memzero(sk, sizeof sk);
    sodium_memzero(shared, sizeof shared);
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_xeddsaSign(
        JNIEnv* env, jobject, jbyteArray secret, jbyteArray message, jbyteArray random) {
    if (!length(env, secret, 32) || !length(env, random, 64) || message == nullptr || env->GetArrayLength(message) > 4096)
        return nullptr;
    unsigned char wide[64] = {}, a[32], negated[32], A[32], r[32], h[32], ha[32], signature[64];
    unsigned char noise[64], prefix[32], digest[64];
    env->GetByteArrayRegion(secret, 0, 32, reinterpret_cast<jbyte*>(wide));
    // X25519's scalar clamping is also required when keys came from crypto_kx.
    wide[0] &= 248; wide[31] &= 127; wide[31] |= 64;
    crypto_core_ed25519_scalar_reduce(a, wide);
    if (crypto_scalarmult_ed25519_base_noclamp(A, a) != 0) {
        sodium_memzero(wide, sizeof wide); sodium_memzero(a, sizeof a); return nullptr;
    }
    crypto_core_ed25519_scalar_negate(negated, a);
    const unsigned char mask = static_cast<unsigned char>(0 - (A[31] >> 7));
    for (int i = 0; i < 32; ++i) a[i] ^= mask & (a[i] ^ negated[i]);
    A[31] &= 127;
    env->GetByteArrayRegion(random, 0, 64, reinterpret_cast<jbyte*>(noise));
    std::array<unsigned char, 4096> msg{};
    const int count = env->GetArrayLength(message);
    env->GetByteArrayRegion(message, 0, count, reinterpret_cast<jbyte*>(msg.data()));
    for (auto &byte : prefix) byte = 0xff;
    prefix[0] = 0xfe;
    crypto_hash_sha512_state hash;
    crypto_hash_sha512_init(&hash);
    crypto_hash_sha512_update(&hash, prefix, 32);
    crypto_hash_sha512_update(&hash, a, 32);
    crypto_hash_sha512_update(&hash, msg.data(), count);
    crypto_hash_sha512_update(&hash, noise, 64);
    crypto_hash_sha512_final(&hash, digest);
    crypto_core_ed25519_scalar_reduce(r, digest);
    if (crypto_scalarmult_ed25519_base_noclamp(signature, r) != 0) {
        sodium_memzero(a, sizeof a); sodium_memzero(r, sizeof r);
        sodium_memzero(wide, sizeof wide); sodium_memzero(negated, sizeof negated);
        sodium_memzero(noise, sizeof noise); sodium_memzero(digest, sizeof digest); return nullptr;
    }
    crypto_hash_sha512_init(&hash);
    crypto_hash_sha512_update(&hash, signature, 32);
    crypto_hash_sha512_update(&hash, A, 32);
    crypto_hash_sha512_update(&hash, msg.data(), count);
    crypto_hash_sha512_final(&hash, digest);
    crypto_core_ed25519_scalar_reduce(h, digest);
    crypto_core_ed25519_scalar_mul(ha, h, a);
    crypto_core_ed25519_scalar_add(signature + 32, r, ha);
    jbyteArray result = output(env, signature, 64);
    sodium_memzero(wide, sizeof wide); sodium_memzero(a, sizeof a);
    sodium_memzero(negated, sizeof negated); sodium_memzero(r, sizeof r);
    sodium_memzero(h, sizeof h); sodium_memzero(ha, sizeof ha);
    sodium_memzero(noise, sizeof noise); sodium_memzero(digest, sizeof digest);
    sodium_memzero(signature, sizeof signature); sodium_memzero(&hash, sizeof hash);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_carriex_crypto_libsodium_NativeCrypto_ed25519Verify(
        JNIEnv* env, jobject, jbyteArray publicKey, jbyteArray message, jbyteArray signature) {
    if (!length(env, publicKey, 32) || !length(env, signature, 64) || message == nullptr || env->GetArrayLength(message) > 4096)
        return JNI_FALSE;
    unsigned char pk[32], sig[64];
    std::array<unsigned char, 4096> msg{};
    int count = env->GetArrayLength(message);
    env->GetByteArrayRegion(publicKey, 0, 32, reinterpret_cast<jbyte*>(pk));
    env->GetByteArrayRegion(signature, 0, 64, reinterpret_cast<jbyte*>(sig));
    env->GetByteArrayRegion(message, 0, count, reinterpret_cast<jbyte*>(msg.data()));
    return crypto_sign_ed25519_verify_detached(sig, msg.data(), count, pk) == 0;
}
