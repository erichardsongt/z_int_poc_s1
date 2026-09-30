package com.meridian.poc.common;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

/**
 * JWS compact serialisation (RS256) using only JDK crypto primitives.
 *
 * <p>This is JOSE <em>encoding</em> on top of the JDK's {@code SHA256withRSA}, not home-made crypto.
 * It keeps the prototype dependency-free; production code would use a vetted JOSE library
 * (e.g. Nimbus JOSE+JWT) and an HSM/KMS-held signing key.</p>
 */
public final class Jwt {
    private Jwt() {}

    public static final class JwtException extends Exception {
        public JwtException(String msg) { super(msg); }
    }

    public static KeyPair newRsaKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(Map<String, Object> claims, PrivateKey key, String kid) {
        String header = b64(Json.write(Json.obj("alg", "RS256", "typ", "JWT", "kid", kid)).getBytes(StandardCharsets.UTF_8));
        String payload = b64(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Verifies structure, algorithm and signature. Time/audience/issuer checks are the caller's job
     * (see {@code TokenValidator}) because they depend on who is validating.
     */
    public static Map<String, Object> verify(String token, Function<String, PublicKey> keyByKid) throws JwtException {
        if (token == null) throw new JwtException("missing token");
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new JwtException("malformed token");
        Map<String, Object> header;
        Map<String, Object> claims;
        try {
            header = Json.parseObject(new String(unb64(parts[0]), StandardCharsets.UTF_8));
            claims = Json.parseObject(new String(unb64(parts[1]), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new JwtException("malformed token");
        }
        // Pin the algorithm: rejects "none" and HS256-with-public-key confusion attacks.
        if (!"RS256".equals(header.get("alg"))) throw new JwtException("unsupported alg " + header.get("alg"));
        PublicKey key = keyByKid.apply(Json.str(header, "kid"));
        if (key == null) throw new JwtException("unknown signing key " + header.get("kid"));
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(unb64(parts[2]))) throw new JwtException("bad signature");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new JwtException("bad signature");
        }
        return claims;
    }

    /** Decode without verifying – for display only (e.g. showing claims in the demo console). */
    public static Map<String, Object> peek(String token) {
        String[] parts = token.split("\\.");
        return Json.parseObject(new String(unb64(parts[1]), StandardCharsets.UTF_8));
    }

    public static Map<String, Object> toJwk(RSAPublicKey k, String kid) {
        return Json.obj("kty", "RSA", "use", "sig", "alg", "RS256", "kid", kid,
                "n", b64(unsigned(k.getModulus())), "e", b64(unsigned(k.getPublicExponent())));
    }

    public static RSAPublicKey fromJwk(Map<String, Object> jwk) {
        try {
            BigInteger n = new BigInteger(1, unb64(Json.str(jwk, "n")));
            BigInteger e = new BigInteger(1, unb64(Json.str(jwk, "e")));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("invalid JWK", ex);
        }
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    public static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }
}
