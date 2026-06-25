package com.loadtest.gwt;

import com.google.gwt.user.client.rpc.RpcTokenException;
import com.google.gwt.user.client.rpc.XsrfToken;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import com.google.gwt.user.server.rpc.XsrfTokenServiceServlet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * GWT XsrfTokenServiceServlet for Liberty OIDC deployments.
 *
 * Liberty's openidConnectClient validates Bearer JWTs statelessly — no HttpSession is
 * created by the OIDC layer. The stock XsrfTokenServiceServlet.generateTokenValue() reads
 * the JSESSIONID cookie from the incoming request, but on the first /app/xsrf call the
 * client has no such cookie yet.
 *
 * Fix: override getNewXsrfToken() to derive the token from the server-side session object
 * (created by SessionInitFilter before this servlet runs). MyServiceImpl.validateXsrfToken()
 * applies the same derivation so both sides agree.
 */
public class GwtXsrfServlet extends XsrfTokenServiceServlet {

    @Override
    public XsrfToken getNewXsrfToken() throws RpcTokenException {
        HttpSession session = getThreadLocalRequest().getSession(false);
        if (session == null) {
            throw new RpcTokenException(
                "No HttpSession found — SessionInitFilter may not be registered");
        }
        return new XsrfToken(session.getId());
    }

    @Override
    protected SerializationPolicy doGetSerializationPolicy(
            HttpServletRequest request, String moduleBaseURL, String strongName) {
        return PermissivePolicy.INSTANCE;
    }
}
