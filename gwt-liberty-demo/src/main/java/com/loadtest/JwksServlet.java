package com.loadtest;

import com.nimbusds.jose.jwk.RSAKey;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * JWKS (JSON Web Key Set) endpoint for Liberty's openidConnectClient.
 *
 * GET /auth/jwks
 *   Response: {"keys":[{<RSA public key in JWK format>}]}
 *
 * Liberty's openidConnectClient fetches this URL (configured as jwkEndpointUrl
 * in server.xml) when validating the first Bearer token. The public key here
 * must correspond to the private key used by TokenServlet to sign JWTs.
 */
@WebServlet("/auth/jwks")
public class JwksServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        RSAKey publicKey = TokenServlet.RSA_KEY.toPublicJWK();
        resp.setContentType("application/json;charset=UTF-8");
        // Cache-Control: private so Liberty can re-fetch when keys rotate
        resp.setHeader("Cache-Control", "private, no-cache");
        resp.getWriter().write("{\"keys\":[" + publicKey.toJSONString() + "]}");
    }
}
