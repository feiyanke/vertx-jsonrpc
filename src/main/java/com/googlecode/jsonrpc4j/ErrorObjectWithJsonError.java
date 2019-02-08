package com.googlecode.jsonrpc4j;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class ErrorObjectWithJsonError {

    public final ObjectNode node;
    public final ErrorResolver.JsonError error;

    public ErrorObjectWithJsonError(ObjectNode node, ErrorResolver.JsonError error) {
        this.node = node;
        this.error = error;
    }

    @Override
    public String toString() {
        return "ErrorObjectWithJsonError{" +
                "node=" + node +
                ", error=" + error +
                '}';
    }
}
