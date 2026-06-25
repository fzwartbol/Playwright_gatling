package com.loadtest;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Embedded OIDC token issuer (Resource Owner Password Credentials grant).
 *
 * POST /auth/token
 *   Body (application/x-www-form-urlencoded):
 *     grant_type=password&username=user1&password=pass1&client_id=gwt-demo
 *   Response:
 *     {"access_token":"eyJ...","token_type":"Bearer","expires_in":3600}
 *
 * Uses a committed RSA key pair (src/main/resources/demo-rsa-*.pem) so the key
 * is stable across server restarts. Liberty caches JWKS by key-id; a rotating
 * key (generated at startup) breaks caching and causes signature validation
 * failures after the first app reload.
 *
 * In production this servlet is absent — set OIDC_TOKEN_URL to the real IDP
 * (Keycloak, IBM Security Verify, Azure AD, etc.) in the Gatling env vars.
 *
 * WARNING: The private key in demo-rsa-private.pem is committed to the repo.
 * It is for local load-test demo only. Never use it in production.
 */
@WebServlet("/auth/token")
public class TokenServlet extends HttpServlet {

    static final RSAKey RSA_KEY;
    static {
        try {
            RSAPrivateKey priv = loadPrivateKey("/demo-rsa-private.pem");
            RSAPublicKey  pub  = loadPublicKey("/demo-rsa-public.pem");
            RSA_KEY = new RSAKey.Builder(pub)
                .privateKey(priv)
                .keyID("demo-key-1")
                .build();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Map<String, String> USERS = Map.of(
        "user1",  "pass1",  "user2",  "pass2",  "user3",  "pass3",
        "user4",  "pass4",  "user5",  "pass5",  "user6",  "pass6",
        "user7",  "pass7",  "user8",  "pass8",  "user9",  "pass9",
        "user10", "pass10"
    );

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String grantType = req.getParameter("grant_type");
        String username  = req.getParameter("username");
        String password  = req.getParameter("password");

        if (!"password".equals(grantType)) {
            resp.setStatus(400);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"unsupported_grant_type\"}");
            return;
        }

        if (username == null || !USERS.getOrDefault(username, "").equals(password)) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"invalid_grant\",\"error_description\":\"Bad credentials\"}");
            return;
        }

        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8090/auth")
                .subject(username)
                .audience("gwt-demo")
                .issueTime(new Date(now))
                .expirationTime(new Date(now + 3_600_000L))
                .claim("scope", "openid")
                .build();

            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("demo-key-1").build(),
                claims
            );
            jwt.sign(new RSASSASigner(RSA_KEY.toPrivateKey()));

            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(
                "{\"access_token\":\"" + jwt.serialize() + "\"" +
                ",\"token_type\":\"Bearer\"" +
                ",\"expires_in\":3600}");

        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"server_error\",\"error_description\":\"" + e.getMessage() + "\"}");
        }
    }

    private static RSAPrivateKey loadPrivateKey(String resource) throws Exception {
        String pem = readResource(resource)
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static RSAPublicKey loadPublicKey(String resource) throws Exception {
        String pem = readResource(resource)
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(der));
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = TokenServlet.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
