package com.loadtest.gwt;

import com.google.gwt.user.server.rpc.SerializationPolicy;
import com.google.gwt.user.server.rpc.XsrfTokenServiceServlet;

import javax.servlet.http.HttpServletRequest;

/**
 * Real GWT XsrfTokenServiceServlet.
 *
 * getNewXsrfToken() generates a token derived from the HTTP session ID using
 * GWT's built-in XsrfUtil.buildTokenValue(). The Gatling VU carries the
 * JSESSIONID cookie, so the same session is used on every subsequent call,
 * and XsrfProtectedServiceServlet can re-derive the expected token to validate.
 *
 * doGetSerializationPolicy is overridden to return the permissive policy so no
 * .gwt.rpc file is required.
 */
public class GwtXsrfServlet extends XsrfTokenServiceServlet {

    @Override
    protected SerializationPolicy doGetSerializationPolicy(
            HttpServletRequest request, String moduleBaseURL, String strongName) {
        return PermissivePolicy.INSTANCE;
    }
}
