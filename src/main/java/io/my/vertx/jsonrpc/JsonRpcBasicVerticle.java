package io.my.vertx.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.googlecode.jsonrpc4j.*;
import io.vertx.core.Future;
import io.vertx.core.net.NetSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Set;

import static com.googlecode.jsonrpc4j.ReflectionUtil.findBestMethodByParamsNode;
import static com.googlecode.jsonrpc4j.ReflectionUtil.findCandidateMethods;
import static com.googlecode.jsonrpc4j.Util.hasNonNullData;

public class JsonRpcBasicVerticle extends JsonRpcAbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcBasicVerticle.class);
    private Object service;
    private String serviceName;

    public JsonRpcBasicVerticle(NetSocket socket, Object service) {
        super(socket);
        this.service = service;
        this.serviceName = ReflectionUtil.getServiceName(service);
    }

    @Override
    protected void handleRequest(ObjectNode node, Future<ObjectNode> future) {
        long id = node.get(ID).asLong();
        String jsonRpc = hasNonNullData(node, JSONRPC) ? node.get(JSONRPC).asText() : VERSION;
        if (!hasNonNullData(node, METHOD)) {
            future.complete(createResponseError(jsonRpc, id, ErrorResolver.JsonError.METHOD_NOT_FOUND));
            return;
        }

        final String fullMethodName = node.get(METHOD).asText();
        final String partialMethodName = getMethodName(fullMethodName);
        final String serviceName = getServiceName(fullMethodName);

        Set<Method> methods = findCandidateMethods(getClasses(serviceName), partialMethodName);
        if (methods.isEmpty()) {
            future.complete(createResponseError(jsonRpc, id, ErrorResolver.JsonError.METHOD_NOT_FOUND));
            return;
        }
        AMethodWithItsArgs methodArgs = findBestMethodByParamsNode(methods, node.get(PARAMS));
        if (methodArgs == null) {
            future.complete(createResponseError(jsonRpc, id, ErrorResolver.JsonError.METHOD_PARAMS_INVALID));
            return;
        }

        try {
            Object target = getHandler(serviceName);
            JsonNode result = ReflectionUtil.invoke(target, methodArgs.method, methodArgs.arguments);
            future.complete(createResponseSuccess(jsonRpc, id, result));
        } catch (Throwable e) {
            future.fail(e);
        }
    }

    protected String getServiceName(final String methodName) {
        if (methodName != null) {
            int ndx = methodName.indexOf('.');
            if (ndx > 0) {
                return methodName.substring(0, ndx);
            }
        }
        return methodName;
    }

    protected String getMethodName(final String methodName) {
        if (methodName != null) {
            int ndx = methodName.indexOf('.');
            if (ndx > 0) {
                return methodName.substring(ndx + 1);
            }
        }
        return methodName;
    }

    protected Class<?>[] getClasses(final String serviceName) {
        if (serviceName.equals(this.serviceName)) {
            return new Class[]{service.getClass()};
        } else {
            return new Class[]{};
        }
    }

    protected Object getHandler(String serviceName) {
        return service;
    }
}
