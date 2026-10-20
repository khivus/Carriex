"""Public, deterministic test vectors; never use these private scalars in the app.

Direct X25519 and HKDF calls use Python cryptography independently of Android/JNI.
The three root updates cover Alice init, Bob's first reply, Alice's next chain.
"""
import hashlib
import hmac
import json
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import x25519
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def pair(seed):
    raw = bytes((seed + index) % 256 for index in range(32))
    key = x25519.X25519PrivateKey.from_private_bytes(raw)
    public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return key, {"secret": raw.hex(), "public": public.hex()}


def root_kdf(root, dh):
    value = HKDF(algorithm=hashes.SHA256(), length=64, salt=root,
                 info=b"Carriex Double Ratchet v1 root").derive(dh)
    return value[:32], value[32:]


def messages(chain):
    result = []
    for _ in range(3):
        result.append(hmac.digest(chain, b"\x01", hashlib.sha256).hex())
        chain = hmac.digest(chain, b"\x02", hashlib.sha256)
    return result, chain.hex()


def main():
    b0, bob0 = pair(1)
    a0, alice0 = pair(35)
    b1, bob1 = pair(69)
    a1, alice1 = pair(103)
    secret = bytes(range(32))
    rk0, ca0 = root_kdf(secret, a0.exchange(b0.public_key()))
    rk1, cb0 = root_kdf(rk0, b1.exchange(a0.public_key()))
    rk2, ca1 = root_kdf(rk1, a1.exchange(b1.public_key()))
    result = {"sk": secret.hex(), "bob0": bob0, "alice0": alice0, "bob1": bob1, "alice1": alice1}
    for name, root, chain in (("alice0_chain", rk0, ca0), ("bob1_chain", rk1, cb0), ("alice1_chain", rk2, ca1)):
        keys, advanced = messages(chain)
        result[name] = {"root": root.hex(), "chain": chain.hex(), "message_keys": keys, "after_three": advanced}
    output = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/double-ratchet-reference.json"
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
