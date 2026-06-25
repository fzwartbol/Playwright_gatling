package com.loadtest;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Ensures an HttpSession exists before any GWT RPC servlet processes the request.
 *
 * Liberty's openidConnectClient with inboundPropagation="required" validates Bearer
 * tokens statelessly — it does NOT create an HttpSession. GWT's XSRF mechanism
 * (XsrfTokenServiceServlet.getNewXsrfToken) requires a session via getSession(false),
 * so without this filter it always throws "Session cookie is not set or empty".
 *
 * This filter bridges stateless OIDC auth with session-based GWT XSRF:
 *   - First call to /app/xsrf: getSession(true) creates the session, JSESSIONID is
 *     set in the response cookie, and the XSRF token is derived from the session ID.
 *   - Subsequent calls to /app/open: Gatling sends JSESSIONID automatically, so
 *     getSession(false) finds the existing session and XSRF validation succeeds.
 */
@WebFilter("/app/*")
public class SessionInitFilter implements Filter {

    @Override
    public void init(FilterConfig fc) {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ((HttpServletRequest) request).getSession(true);
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {}
}
