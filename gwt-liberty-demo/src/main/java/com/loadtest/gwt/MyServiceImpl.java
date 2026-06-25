package com.loadtest.gwt;

import com.google.gwt.user.client.rpc.RpcToken;
import com.google.gwt.user.client.rpc.RpcTokenException;
import com.google.gwt.user.client.rpc.XsrfToken;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import com.google.gwt.user.server.rpc.XsrfProtectedServiceServlet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.lang.reflect.Method;

/**
 * GWT XsrfProtectedServiceServlet for Liberty OIDC deployments.
 *
 * validateXsrfToken() is overridden to compare the token from the RPC stream against
 * the server-side session ID (set by SessionInitFilter and returned by GwtXsrfServlet).
 * This matches the custom derivation in GwtXsrfServlet.getNewXsrfToken().
 *
 * Username is read from getUserPrincipal() — Liberty's OIDC inbound propagation sets this
 * from the JWT "sub" claim without requiring a session.
 */
public class MyServiceImpl extends XsrfProtectedServiceServlet implements MyService {

    @Override
    protected void validateXsrfToken(RpcToken token, Method method) throws RpcTokenException {
        if (!(token instanceof XsrfToken)) {
            throw new RpcTokenException("Missing or invalid XSRF token type");
        }
        HttpSession session = getThreadLocalRequest().getSession(false);
        if (session == null) {
            throw new RpcTokenException(
                "No HttpSession — call /app/xsrf first to establish a session");
        }
        String expected = session.getId();
        String actual   = ((XsrfToken) token).getToken();
        if (!expected.equals(actual)) {
            throw new RpcTokenException("Invalid XSRF token");
        }
    }

    @Override
    protected SerializationPolicy doGetSerializationPolicy(
            HttpServletRequest request, String moduleBaseURL, String strongName) {
        return PermissivePolicy.INSTANCE;
    }

    @Override
    public String open() {
        HttpServletRequest req = getThreadLocalRequest();
        String user = req.getUserPrincipal() != null
            ? req.getUserPrincipal().getName()
            : "anonymous";
        return "OPEN_OK:user=" + user;
    }

    @Override
    public String submit(String name, String email) {
        return "SUBMIT_OK:name=" + name + ",email=" + email;
    }
}
