package com.loadtest.gwt;

import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.server.rpc.SerializationPolicy;

/**
 * Accepts all types for deserialization and serialization.
 *
 * Normally the server loads a generated .gwt.rpc file (named by the permutation
 * strong name) that whitelists allowed types. Without a real GWT compile there
 * is no .gwt.rpc file, so we override the policy to accept everything. This is
 * safe for a load-test demo where we control both ends.
 *
 * When flags=2 is present in the GWT-RPC stream, GWT automatically extracts and
 * deserializes the XsrfToken. Because shouldDeserializeFields/validateDeserialize
 * both accept XsrfToken here, deserialization succeeds and XsrfProtectedServiceServlet
 * can validate the token against the HTTP session.
 */
public class PermissivePolicy extends SerializationPolicy {

    public static final PermissivePolicy INSTANCE = new PermissivePolicy();

    private PermissivePolicy() {}

    @Override
    public boolean shouldDeserializeFields(Class<?> clazz) { return true; }

    @Override
    public boolean shouldSerializeFields(Class<?> clazz) { return true; }

    @Override
    public void validateDeserialize(Class<?> clazz) throws SerializationException {
        // Accept all types
    }

    @Override
    public void validateSerialize(Class<?> clazz) throws SerializationException {
        // Accept all types
    }
}
