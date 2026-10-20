"""Independent, test-only X3DH/XEdDSA vectors. Requires Python cryptography.

Uses public test scalars and slow integer arithmetic, NEVER for production keys.
Native Android verification must agree with these signatures and DH/HKDF results.
Run from Carriex: ../Carriex-server/.venv/Scripts/python tools/x3dh_reference_vectors.py
"""
import hashlib
import json
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

P = 2**255 - 19
Q = 2**252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, -1, P) % P
Y = 4 * pow(5, -1, P) % P
X = pow((Y * Y - 1) * pow(D * Y * Y + 1, -1, P) % P, (P + 3) // 8, P)
if (X * X - (Y * Y - 1) * pow(D * Y * Y + 1, -1, P)) % P:
    X = X * pow(2, (P - 1) // 4, P) % P
if X % 2:
    X = P - X


def add(a, b):
    x, y = a
    u, v = b
    factor = D * x * u * y * v % P
    return ((x * v + y * u) * pow(1 + factor, -1, P) % P,
            (y * v + x * u) * pow(1 - factor, -1, P) % P)


def mul(scalar):
    result, point = (0, 1), (X, Y)
    while scalar:
        if scalar & 1:
            result = add(result, point)
        point = add(point, point)
        scalar >>= 1
    return result


def encode(point):
    return (point[1] | ((point[0] & 1) << 255)).to_bytes(32, "little")


def xeddsa(secret, message, random):
    clamped = bytearray(secret)
    clamped[0] &= 248
    clamped[31] = (clamped[31] & 127) | 64
    scalar = int.from_bytes(clamped, "little") % Q
    public = mul(scalar)
    if public[0] & 1:
        scalar = -scalar % Q
        public = mul(scalar)
    nonce = int.from_bytes(hashlib.sha512(b"\xfe" + b"\xff" * 31 + scalar.to_bytes(32, "little") + message + random).digest(), "little") % Q
    r = encode(mul(nonce))
    h = int.from_bytes(hashlib.sha512(r + encode(public) + message).digest(), "little") % Q
    signature = r + ((nonce + h * scalar) % Q).to_bytes(32, "little")
    ed25519.Ed25519PublicKey.from_public_bytes(encode(public)).verify(signature, message)
    return signature


def key(seed):
    secret = bytes((seed + i) % 256 for i in range(32))
    private = x25519.X25519PrivateKey.from_private_bytes(secret)
    public = private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return secret, public, private


def main():
    a, e, b, s, o = [key(seed) for seed in (0, 32, 64, 96, 128)]
    material = [a[2].exchange(s[2].public_key()), e[2].exchange(b[2].public_key()),
                e[2].exchange(s[2].public_key()), e[2].exchange(o[2].public_key())]
    roots = [HKDF(algorithm=hashes.SHA256(), length=32, salt=bytes(32), info=b"Carriex X3DH v2")
             .derive(b"\xff" * 32 + b"".join(material[:count])).hex() for count in (3, 4)]
    vectors = {"keys": [{"secret": entry[0].hex(), "public": entry[1].hex()} for entry in (a, e, b, s, o)],
               "root3": roots[0], "root4": roots[1], "message": (b"\x05" + s[1]).hex(),
               "random": bytes(range(64)).hex(),
               "signatures": [xeddsa(entry[0], b"\x05" + s[1], bytes(range(64))).hex() for entry in (a, b)]}
    target = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/x3dh-reference.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(vectors, indent=2) + "\n", encoding="utf-8")
    print(target)


if __name__ == "__main__":
    main()
