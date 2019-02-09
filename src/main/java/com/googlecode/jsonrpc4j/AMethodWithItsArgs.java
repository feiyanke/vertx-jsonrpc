package com.googlecode.jsonrpc4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Simple inner class for the {@code findXXX} methods.
 */
public class AMethodWithItsArgs {
    public final List<JsonNode> arguments = new ArrayList<>();
    public final Method method;

    public AMethodWithItsArgs(Method method, int paramCount, ArrayNode paramNodes) {
        this(method);
        collectArgumentsBasedOnCount(method, paramCount, paramNodes);
    }

    public AMethodWithItsArgs(Method method) {
        this.method = method;
    }

    private void collectArgumentsBasedOnCount(Method method, int paramCount, ArrayNode paramNodes) {
        int numParameters = method.getParameterTypes().length;
        for (int i = 0; i < numParameters; i++) {
            if (i < paramCount) {
                addArgument(paramNodes.get(i));
            } else {
                addArgument(NullNode.getInstance());
            }
        }
    }

    public AMethodWithItsArgs(Method method, Set<String> paramNames, List<JsonRpcParam> allNames, ObjectNode paramNodes) {
        this(method);
        collectArgumentsBasedOnName(method, paramNames, allNames, paramNodes);
    }

    public AMethodWithItsArgs(Method method, JsonNode jsonNode) {
        this(method);
        collectVarargsFromNode(jsonNode);
    }

    private void collectArgumentsBasedOnName(Method method, Set<String> paramNames, List<JsonRpcParam> allNames, ObjectNode paramNodes) {
        Class<?>[] types = method.getParameterTypes();
        int numParameters = types.length;
        for (int i = 0; i < numParameters; i++) {
            JsonRpcParam param = allNames.get(i);
            if (param != null && paramNames.contains(param.value())) {
                if (types[i].isArray() && method.isVarArgs() && numParameters == 1) {
                    collectVarargsFromNode(paramNodes.get(param.value()));
                } else {
                    addArgument(paramNodes.get(param.value()));
                }
            } else {
                addArgument(NullNode.getInstance());
            }
        }
    }

    private void collectVarargsFromNode(JsonNode node) {
        if (node.isArray()) {
            ArrayNode arrayNode = ArrayNode.class.cast(node);
            for (int i = 0; i < node.size(); i++) {
                addArgument(arrayNode.get(i));
            }
        }

        if (node.isObject()) {
            ObjectNode objectNode = ObjectNode.class.cast(node);
            Iterator<Map.Entry<String,JsonNode>> items = objectNode.fields();
            while (items.hasNext()) {
                Map.Entry<String,JsonNode> item = items.next();
                JsonNode name = JsonNodeFactory.instance.objectNode().put(item.getKey(),item.getKey());
                addArgument(name.get(item.getKey()));
                addArgument(item.getValue());
            }
        }
    }

    public void addArgument(JsonNode argumentJsonNode) {
        arguments.add(argumentJsonNode);
}
}
