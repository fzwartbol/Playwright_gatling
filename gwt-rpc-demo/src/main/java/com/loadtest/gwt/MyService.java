package com.loadtest.gwt;

import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.XsrfProtectedService;

/**
 * Real GWT RPC service interface. Extending XsrfProtectedService enforces that
 * every call must include a valid XsrfToken in the GWT-RPC stream (flags=2).
 * The server (MyServiceImpl) validates the token against the HTTP session.
 */
public interface MyService extends RemoteService, XsrfProtectedService {
    String open();
    String submit(String name, String email);
}
