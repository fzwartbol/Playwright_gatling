package com.loadtest.gwt;

import com.google.gwt.user.server.rpc.SerializationPolicy;
import com.google.gwt.user.server.rpc.XsrfProtectedServiceServlet;

import javax.servlet.http.HttpServletRequest;

/**
 * Real GWT XsrfProtectedServiceServlet implementation.
 *
 * Because MyService is annotated @XsrfProtect, GWT-RPC enforces that the
 * incoming request stream has flags=2 and contains a valid XsrfToken. The
 * token is validated by validateXsrfToken() in XsrfProtectedServiceServlet,
 * which re-derives the expected token from the JSESSIONID session and compares.
 *
 * If the token is wrong or missing: SerializationException / RpcTokenException.
 * If the session is missing: RpcTokenException("Session not found").
 */
public class MyServiceImpl extends XsrfProtectedServiceServlet implements MyService {

    @Override
    protected SerializationPolicy doGetSerializationPolicy(
            HttpServletRequest request, String moduleBaseURL, String strongName) {
        return PermissivePolicy.INSTANCE;
    }

    @Override
    public String open() {
        String user = (String) getThreadLocalRequest().getSession(false)
                .getAttribute("username");
        return "OPEN_OK:user=" + user;
    }

    @Override
    public String submit(String name, String email) {
        return "SUBMIT_OK:name=" + name + ",email=" + email;
    }
}
