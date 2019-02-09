package com.googlecode.jsonrpc4j;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utilities for reflection.
 */
public abstract class ReflectionUtil {
	
	private static final Map<String, Set<Method>> methodCache = new ConcurrentHashMap<>();
	
	private static final Map<Method, List<Class<?>>> parameterTypeCache = new ConcurrentHashMap<>();
	
	private static final Map<Method, List<Annotation>> methodAnnotationCache = new ConcurrentHashMap<>();
	
	private static final Map<Method, List<List<Annotation>>> methodParamAnnotationCache = new ConcurrentHashMap<>();

    private static Class<? extends Annotation> WEB_PARAM_ANNOTATION_CLASS;
    private static Method WEB_PARAM_NAME_METHOD;
    public static final String WEB_PARAM_ANNOTATION_CLASS_LOADER = "javax.jws.WebParam";
    public static final String NAME = "name";
    private static final Logger logger = LoggerFactory.getLogger(ReflectionUtil.class);
    public static boolean allowExtraParams = false;
    public static boolean allowLessParams = false;
    public static ConvertedParameterTransformer convertedParameterTransformer = null;
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void loadAnnotationSupportEngine() {
        final ClassLoader classLoader = ReflectionUtil.class.getClassLoader();
        try {
            WEB_PARAM_ANNOTATION_CLASS = classLoader.loadClass(WEB_PARAM_ANNOTATION_CLASS_LOADER).asSubclass(Annotation.class);
            WEB_PARAM_NAME_METHOD = WEB_PARAM_ANNOTATION_CLASS.getMethod(NAME);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            logger.error("Could not find {}.{}", WEB_PARAM_ANNOTATION_CLASS_LOADER, NAME, e);
        }
    }

	public static String getServiceName(Object service) {
	    Class clazz = service.getClass();
	    if (clazz.isAnnotationPresent(JsonRpcService.class)) {
            JsonRpcService jsonRpcService = (JsonRpcService) clazz.getAnnotation(JsonRpcService.class);
            return jsonRpcService.value();
        } else {
	        return clazz.getName();
        }
    }
	/**
	 * Finds methods with the given name on the given class.
	 *
	 * @param classes                    the classes
	 * @param name                       the method name
	 * @return the methods
	 */
	public static Set<Method> findCandidateMethods(Class<?>[] classes, String name) {
		StringBuilder sb = new StringBuilder();
		for (Class<?> clazz : classes) {
			sb.append(clazz.getName()).append("::");
		}
		String cacheKey = sb.append(name).toString();
		if (methodCache.containsKey(cacheKey)) {
			return methodCache.get(cacheKey);
		}
		Set<Method> methods = new HashSet<>();
		for (Class<?> clazz : classes) {
            for (Method method : clazz.getMethods()) {
                if (method.isAnnotationPresent(JsonRpcMethod.class)) {
                    JsonRpcMethod methodAnnotation = method.getAnnotation(JsonRpcMethod.class);

                    if (methodAnnotation.required()) {
                        if (methodAnnotation.value().equals(name)) {
                            methods.add(method);
                        }
                    } else if (methodAnnotation.value().equals(name) || method.getName().equals(name)) {
                        methods.add(method);
                    }
                } else if (method.getName().equals(name)) {
                    methods.add(method);
                }
            }
        }
		methods = Collections.unmodifiableSet(methods);
		methodCache.put(cacheKey, methods);
		return methods;
	}
	
	/**
	 * Returns the parameter types for the given {@link Method}.
	 *
	 * @param method the {@link Method}
	 * @return the parameter types
	 */
	static List<Class<?>> getParameterTypes(Method method) {
		if (parameterTypeCache.containsKey(method)) {
			return parameterTypeCache.get(method);
		}
		List<Class<?>> types = new ArrayList<>();
		Collections.addAll(types, method.getParameterTypes());
		types = Collections.unmodifiableList(types);
		parameterTypeCache.put(method, types);
		return types;
	}
	
	/**
	 * Returns {@link Annotation}s of the given type defined
	 * on the given {@link Method}.
	 *
	 * @param <T>    the {@link Annotation} type
	 * @param method the {@link Method}
	 * @param type   the type
	 * @return the {@link Annotation}s
	 */
	public static <T extends Annotation> List<T> getAnnotations(Method method, Class<T> type) {
		return filterAnnotations(getAnnotations(method), type);
	}
	
	private static <T extends Annotation> List<T> filterAnnotations(Collection<Annotation> annotations, Class<T> type) {
		List<T> result = new ArrayList<>();
		for (Annotation annotation : annotations) {
			if (type.isInstance(annotation)) {
				result.add(type.cast(annotation));
			}
		}
		return result;
	}
	
	/**
	 * Returns all of the {@link Annotation}s defined on
	 * the given {@link Method}.
	 *
	 * @param method the {@link Method}
	 * @return the {@link Annotation}s
	 */
	private static List<Annotation> getAnnotations(Method method) {
		if (methodAnnotationCache.containsKey(method)) {
			return methodAnnotationCache.get(method);
		}
		List<Annotation> annotations = new ArrayList<>();
		Collections.addAll(annotations, method.getAnnotations());
		annotations = Collections.unmodifiableList(annotations);
		methodAnnotationCache.put(method, annotations);
		return annotations;
	}
	
	/**
	 * Returns the first {@link Annotation} of the given type
	 * defined on the given {@link Method}.
	 *
	 * @param <T>    the type
	 * @param method the method
	 * @param type   the type of annotation
	 * @return the annotation or null
	 */
	public static <T extends Annotation> T getAnnotation(Method method, Class<T> type) {
		for (Annotation a : getAnnotations(method)) {
			if (type.isInstance(a)) {
				return type.cast(a);
			}
		}
		return null;
	}
	
	/**
	 * Returns the parameter {@link Annotation}s of the
	 * given type for the given {@link Method}.
	 *
	 * @param <T>    the {@link Annotation} type
	 * @param type   the type
	 * @param method the {@link Method}
	 * @return the {@link Annotation}s
	 */
	static <T extends Annotation> List<List<T>> getParameterAnnotations(Method method, Class<T> type) {
		List<List<T>> annotations = new ArrayList<>();
		for (List<Annotation> paramAnnotations : getParameterAnnotations(method)) {
			annotations.add(filterAnnotations(paramAnnotations, type));
		}
		return annotations;
	}
	
	/**
	 * Returns the parameter {@link Annotation}s for the
	 * given {@link Method}.
	 *
	 * @param method the {@link Method}
	 * @return the {@link Annotation}s
	 */
	private static List<List<Annotation>> getParameterAnnotations(Method method) {
		if (methodParamAnnotationCache.containsKey(method)) {
			return methodParamAnnotationCache.get(method);
		}
		List<List<Annotation>> annotations = new ArrayList<>();
		for (Annotation[] paramAnnotations : method.getParameterAnnotations()) {
			List<Annotation> listAnnotations = new ArrayList<>();
			Collections.addAll(listAnnotations, paramAnnotations);
			annotations.add(listAnnotations);
		}
		annotations = Collections.unmodifiableList(annotations);
		methodParamAnnotationCache.put(method, annotations);
		return annotations;
	}
	
	/**
	 * Parses the given arguments for the given method optionally
	 * turning them into named parameters.
	 *
	 * @param method    the method
	 * @param arguments the arguments
	 * @return the parsed arguments
	 */
	public static Object parseArguments(Method method, Object[] arguments) {

		JsonRpcParamsPassMode paramsPassMode = JsonRpcParamsPassMode.AUTO;
		JsonRpcMethod jsonRpcMethod = getAnnotation(method, JsonRpcMethod.class);
		if (jsonRpcMethod != null)
			paramsPassMode = jsonRpcMethod.paramsPassMode();

		Map<String, Object> namedParams = getNamedParameters(method, arguments);

		switch (paramsPassMode) {
			case ARRAY:
				if (namedParams.size() > 0) {
					Object[] parsed = new Object[namedParams.size()];
					int i = 0;
					for (Object value : namedParams.values()) {
						parsed[i++] = value;
					}
					return parsed;
				} else {
					return arguments != null ? arguments : new Object[]{};
				}
			case OBJECT:
				if (namedParams.size() > 0) {
					return namedParams;
				} else {
					if (arguments == null) {
                        return new Object[]{};
                    }
					throw new IllegalArgumentException(
							"OBJECT parameters pass mode is impossible without declaring JsonRpcParam annotations for all parameters on method "
									+ method.getName());
				}
			case AUTO:
			default:
				if (namedParams.size() > 0) {
					return namedParams;
				} else {
					return arguments != null ? arguments : new Object[]{};
				}
		}
	}
	
	/**
	 * Checks method for @JsonRpcParam annotations and returns named parameters.
	 *
	 * @param method    the method
	 * @param arguments the arguments
	 * @return named parameters or empty if no annotations found
	 * @throws IllegalArgumentException if some parameters are annotated and others not
	 */
	private static Map<String, Object> getNamedParameters(Method method, Object[] arguments) {
		
		Map<String, Object> namedParams = new LinkedHashMap<>();
		
		Annotation[][] paramAnnotations = method.getParameterAnnotations();
		for (int i = 0; i < paramAnnotations.length; i++) {
			Annotation[] ann = paramAnnotations[i];
			for (Annotation an : ann) {
				if (JsonRpcParam.class.isInstance(an)) {
					JsonRpcParam jAnn = (JsonRpcParam) an;
					namedParams.put(jAnn.value(), arguments[i]);
					break;
				}
			}
		}
		
		if (arguments != null && arguments.length > 0 && namedParams.size() > 0 && namedParams.size() != arguments.length) {
			throw new IllegalArgumentException("JsonRpcParam annotations were not found for all parameters on method " + method.getName());
		}
		
		return namedParams;
	}

	public static void clearCache() {
		methodCache.clear();
		parameterTypeCache.clear();
		methodAnnotationCache.clear();
		methodParamAnnotationCache.clear();
	}

    private static class ParameterCount {
        private final int typeCount;
        private final int nameCount;
        private final List<JsonRpcParam> allNames;
        private final Method method;

        public ParameterCount(Set<String> paramNames, ObjectNode paramNodes, List<Class<?>> parameterTypes, Method method) {
            this.allNames = getAnnotatedParameterNames(method);
            this.method = method;
            int typeCount = 0;
            int nameCount = 0;
            int at = 0;

            for (JsonRpcParam name : this.allNames) {
                if (missingAnnotation(name)) {
                    continue;
                }
                String paramName = name.value();
                boolean hasParamName = paramNames.contains(paramName);
                if (hasParamName) {
                    nameCount += 1;
                }
                if (hasParamName && isMatchingType(paramNodes.get(paramName), parameterTypes.get(at))) {
                    typeCount += 1;
                }
                at += 1;
            }
            this.typeCount = typeCount;
            this.nameCount = nameCount;
        }

        @SuppressWarnings("Convert2streamapi")
        private List<JsonRpcParam> getAnnotatedParameterNames(Method method) {
            List<JsonRpcParam> parameterNames = new ArrayList<>();
            for (List<? extends Annotation> webParamAnnotation : getWebParameterAnnotations(method)) {
                if (!webParamAnnotation.isEmpty()) {
                    parameterNames.add(createNewJsonRcpParamType(webParamAnnotation.get(0)));
                }
            }
            for (List<JsonRpcParam> annotation : getJsonRpcParamAnnotations(method)) {
                if (!annotation.isEmpty()) {
                    parameterNames.add(annotation.get(0));
                }
            }
            return parameterNames;
        }

        private List<? extends List<? extends Annotation>> getWebParameterAnnotations(Method method) {
            if (WEB_PARAM_ANNOTATION_CLASS == null) {
                return new ArrayList<>();
            }
            return ReflectionUtil.getParameterAnnotations(method, WEB_PARAM_ANNOTATION_CLASS);
        }

        private JsonRpcParam createNewJsonRcpParamType(final Annotation annotation) {
            return new JsonRpcParam() {
                public Class<? extends Annotation> annotationType() {
                    return JsonRpcParam.class;
                }

                public String value() {
                    try {
                        return (String) WEB_PARAM_NAME_METHOD.invoke(annotation);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }

        private List<List<JsonRpcParam>> getJsonRpcParamAnnotations(Method method) {
            return ReflectionUtil.getParameterAnnotations(method, JsonRpcParam.class);
        }

        public ParameterCount() {
            typeCount = -1;
            nameCount = -1;
            allNames = null;
            method = null;
        }

        public int getTypeCount() {
            return typeCount;
        }

        public int getNameCount() {
            return nameCount;
        }

    }

    /**
     * Finds the {@link Method} from the supplied {@link Set} that
     * best matches the rest of the arguments supplied and returns
     * it as a {@link AMethodWithItsArgs} class.
     *
     * @param methods    the {@link Method}s
     * @param paramsNode the {@link JsonNode} passed as the parameters
     * @return the {@link AMethodWithItsArgs}
     */
    public static AMethodWithItsArgs findBestMethodByParamsNode(Set<Method> methods, JsonNode paramsNode) {
        if (hasNoParameters(paramsNode)) {
            return findBestMethodUsingParamIndexes(methods, 0, null);
        }
        AMethodWithItsArgs matchedMethod;
        if (paramsNode.isArray()) {
            matchedMethod = findBestMethodUsingParamIndexes(methods, paramsNode.size(), ArrayNode.class.cast(paramsNode));
        } else if (paramsNode.isObject()) {
            matchedMethod = findBestMethodUsingParamNames(methods, collectFieldNames(paramsNode), ObjectNode.class.cast(paramsNode));
        } else {
            throw new IllegalArgumentException("Unknown params node type: " + paramsNode.toString());
        }
        if (matchedMethod == null) {
            matchedMethod = findBestMethodForVarargs(methods, paramsNode);
        }
        return matchedMethod;
    }

    private static Set<String> collectFieldNames(JsonNode paramsNode) {
        Set<String> fieldNames = new HashSet<>();
        Iterator<String> itr = paramsNode.fieldNames();
        while (itr.hasNext()) {
            fieldNames.add(itr.next());
        }
        return fieldNames;
    }

    private static boolean isNullNodeOrValue(JsonNode node) {
        return node == null || node.isNull();
    }

    private static boolean hasNoParameters(JsonNode paramsNode) {
        return isNullNodeOrValue(paramsNode);
    }

    /**
     * Finds the {@link Method} from the supplied {@link Set} that
     * best matches the rest of the arguments supplied and returns
     * it as a {@link AMethodWithItsArgs} class.
     *
     * @param methods    the {@link Method}s
     * @param paramCount the number of expect parameters
     * @param paramNodes the parameters for matching types
     * @return the {@link AMethodWithItsArgs}
     */
    private static AMethodWithItsArgs findBestMethodUsingParamIndexes(Set<Method> methods, int paramCount, ArrayNode paramNodes) {
        int numParams = isNullNodeOrValue(paramNodes) ? 0 : paramNodes.size();
        int bestParamNumDiff = Integer.MAX_VALUE;
        Set<Method> matchedMethods = collectMethodsMatchingParamCount(methods, paramCount, bestParamNumDiff);
        if (matchedMethods.isEmpty()) {
            return null;
        }
        Method bestMethod = getBestMatchingArgTypeMethod(paramNodes, numParams, matchedMethods);
        return new AMethodWithItsArgs(bestMethod, paramCount, paramNodes);
    }

    private static Method getBestMatchingArgTypeMethod(ArrayNode paramNodes, int numParams, Set<Method> matchedMethods) {
        if (matchedMethods.size() == 1 || numParams == 0) {
            return matchedMethods.iterator().next();
        }
        Method bestMethod = null;
        int mostMatches = Integer.MIN_VALUE;
        for (Method method : matchedMethods) {
            List<Class<?>> parameterTypes = getParameterTypes(method);
            int numMatches = getNumArgTypeMatches(paramNodes, numParams, parameterTypes);
            if (hasMoreMatches(mostMatches, numMatches)) {
                mostMatches = numMatches;
                bestMethod = method;
            }
        }
        return bestMethod;
    }

    /**
     * Finds the {@link Method} from the supplied {@link Set} that
     * matches the method name annotation and have varargs.
     * it as a {@link AMethodWithItsArgs} class.
     *
     * @param methods    the {@link Method}s
     * @param paramsNode the {@link JsonNode} of request
     * @return the {@link AMethodWithItsArgs}
     */
    private static AMethodWithItsArgs findBestMethodForVarargs(Set<Method> methods, JsonNode paramsNode) {
        for (Method method : methods) {
            if (method.getParameterTypes().length != 1) {
                continue;
            }
            if (method.isVarArgs()) {
                return new AMethodWithItsArgs(method, paramsNode);
            }
        }
        return null;
    }

    private static int getNumArgTypeMatches(ArrayNode paramNodes, int numParams, List<Class<?>> parameterTypes) {
        int numMatches = 0;
        for (int i = 0; i < parameterTypes.size() && i < numParams; i++) {
            if (isMatchingType(paramNodes.get(i), parameterTypes.get(i))) {
                numMatches++;
            }
        }
        return numMatches;
    }

    private static Set<Method> collectMethodsMatchingParamCount(Set<Method> methods, int paramCount, int bestParamNumDiff) {
        Set<Method> matchedMethods = new HashSet<>();
        // check every method
        for (Method method : methods) {
            Class<?>[] paramTypes = method.getParameterTypes();
            final int paramNumDiff = paramTypes.length - paramCount;
            if (hasLessOrEqualAbsParamDiff(bestParamNumDiff, paramNumDiff) && acceptParamCount(paramNumDiff)) {
                if (hasLessAbsParamDiff(bestParamNumDiff, paramNumDiff)) {
                    matchedMethods.clear();
                }
                matchedMethods.add(method);
                bestParamNumDiff = paramNumDiff;
            }
        }
        return matchedMethods;
    }

    private static boolean hasLessAbsParamDiff(int bestParamNumDiff, int paramNumDiff) {
        return Math.abs(paramNumDiff) < Math.abs(bestParamNumDiff);
    }

    private static boolean acceptParamCount(int paramNumDiff) {
        return paramNumDiff == 0 || acceptNonExactParam(paramNumDiff);
    }

    private static boolean acceptNonExactParam(int paramNumDiff) {
        return acceptMoreParam(paramNumDiff) || acceptLessParam(paramNumDiff);
    }

    private static boolean acceptLessParam(int paramNumDiff) {
        return allowLessParams && paramNumDiff > 0;
    }

    private static boolean acceptMoreParam(int paramNumDiff) {
        return allowExtraParams && paramNumDiff < 0;
    }

    private static boolean hasLessOrEqualAbsParamDiff(int bestParamNumDiff, int paramNumDiff) {
        return Math.abs(paramNumDiff) <= Math.abs(bestParamNumDiff);
    }

    /**
     * Finds the {@link Method} from the supplied {@link Set} that best matches the rest of the arguments supplied and
     * returns it as a {@link AMethodWithItsArgs} class.
     *
     * @param methods    the {@link Method}s
     * @param paramNames the parameter allNames
     * @param paramNodes the parameters for matching types
     * @return the {@link AMethodWithItsArgs}
     */
    private static AMethodWithItsArgs findBestMethodUsingParamNames(Set<Method> methods, Set<String> paramNames, ObjectNode paramNodes) {
        ParameterCount max = new ParameterCount();

        for (Method method : methods) {
            List<Class<?>> parameterTypes = getParameterTypes(method);

            int typeNameCountDiff = parameterTypes.size() - paramNames.size();
            if (!acceptParamCount(typeNameCountDiff)) {
                continue;
            }

            ParameterCount parStat = new ParameterCount(paramNames, paramNodes, parameterTypes, method);
            if (!acceptParamCount(parStat.nameCount - paramNames.size())) {
                continue;
            }
            if (hasMoreMatches(max.nameCount, parStat.nameCount) || parStat.nameCount == max.nameCount && hasMoreMatches(max.typeCount, parStat.typeCount)) {
                max = parStat;
            }
        }
        if (max.method == null) {
            return null;
        }
        return new AMethodWithItsArgs(max.method, paramNames, max.allNames, paramNodes);

    }

    private static boolean hasMoreMatches(int maxMatchingParams, int numMatchingParams) {
        return numMatchingParams > maxMatchingParams;
    }

    private static boolean missingAnnotation(JsonRpcParam name) {
        return name == null;
    }

    /**
     * Determines whether or not the given {@link JsonNode} matches
     * the given type.  This method is limited to a few java types
     * only and shouldn't be used to determine with great accuracy
     * whether or not the types match.
     *
     * @param node the {@link JsonNode}
     * @param type the {@link Class}
     * @return true if the types match, false otherwise
     */
    @SuppressWarnings("SimplifiableIfStatement")
    private static boolean isMatchingType(JsonNode node, Class<?> type) {
        if (node.isNull()) {
            return true;
        }
        if (node.isTextual()) {
            return String.class.isAssignableFrom(type);
        }
        if (node.isNumber()) {
            return isNumericAssignable(type);
        }
        if (node.isArray() && type.isArray()) {
            return node.size() > 0 && isMatchingType(node.get(0), type.getComponentType());
        }
        if (node.isArray()) {
            return type.isArray() || Collection.class.isAssignableFrom(type);
        }
        if (node.isBinary()) {
            return byteOrCharAssignable(type);
        }
        if (node.isBoolean()) {
            return boolean.class.isAssignableFrom(type) || Boolean.class.isAssignableFrom(type);
        }
        if (node.isObject() || node.isPojo()) {
            return !type.isPrimitive() && !String.class.isAssignableFrom(type) &&
                    !Number.class.isAssignableFrom(type) && !Boolean.class.isAssignableFrom(type);
        }
        return false;
    }

    private static boolean byteOrCharAssignable(Class<?> type) {
        return byte[].class.isAssignableFrom(type) || Byte[].class.isAssignableFrom(type) ||
                char[].class.isAssignableFrom(type) || Character[].class.isAssignableFrom(type);
    }

    private static boolean isNumericAssignable(Class<?> type) {
        return Number.class.isAssignableFrom(type) || short.class.isAssignableFrom(type) || int.class.isAssignableFrom(type)
                || long.class.isAssignableFrom(type) || float.class.isAssignableFrom(type) || double.class.isAssignableFrom(type);
    }

    /**
     * Invokes the given method on the {@code handler} passing
     * the given params (after converting them to beans\objects)
     * to it.
     *
     * @param target optional service name used to locate the target object
     *               to invoke the Method on
     * @param method the method to invoke
     * @param params the params to pass to the method
     * @return the return value (or null if no return)
     * @throws IOException               on error
     * @throws IllegalAccessException    on error
     * @throws InvocationTargetException on error
     */
    public static JsonNode invoke(Object target, Method method, List<JsonNode> params) throws IOException, IllegalAccessException, InvocationTargetException {
        logger.debug("Invoking method: {} with args {}", method.getName(), params);

        Object result;

        if (method.getGenericParameterTypes().length == 1 && method.isVarArgs()) {
            Class<?> componentType = method.getParameterTypes()[0].getComponentType();
            result = componentType.isPrimitive() ?
                    invokePrimitiveVarargs(target, method, params, componentType) :
                    invokeNonPrimitiveVarargs(target, method, params, componentType);
        } else {
            Object[] convertedParams = convertJsonToParameters(method, params);
            if (convertedParameterTransformer != null) {
                convertedParams = convertedParameterTransformer.transformConvertedParameters(target, convertedParams);
            }
            result = method.invoke(target, convertedParams);
        }

        logger.debug("Invoked method: {}, result {}", method.getName(), result);

        return hasReturnValue(method) ? mapper.valueToTree(result) : null;
    }

    private static Object invokePrimitiveVarargs(Object target, Method method, List<JsonNode> params, Class<?> componentType) throws IllegalAccessException, InvocationTargetException {
        // need to cast to object here in order to support primitives.
        Object convertedParams = Array.newInstance(componentType, params.size());

        for (int i = 0; i < params.size(); i++) {
            JsonNode jsonNode = params.get(i);
            Class<?> type = JsonUtil.getJavaTypeForJsonType(jsonNode);
            Object object = mapper.convertValue(jsonNode, type);
            logger.debug("[{}] param: {} -> {}", method.getName(), i, type.getName());
            Array.set(convertedParams, i, object);
        }

        return method.invoke(target, convertedParams);
    }

    private static Object invokeNonPrimitiveVarargs(Object target, Method method, List<JsonNode> params, Class<?> componentType) throws IllegalAccessException, InvocationTargetException {
        Object[] convertedParams = (Object[]) Array.newInstance(componentType, params.size());

        for (int i = 0; i < params.size(); i++) {
            JsonNode jsonNode = params.get(i);
            Class<?> type = JsonUtil.getJavaTypeForJsonType(jsonNode);
            Object object = mapper.convertValue(jsonNode, type);
            logger.debug("[{}] param: {} -> {}", method.getName(), i, type.getName());
            convertedParams[i] = object;
        }

        return method.invoke(target, new Object[] { convertedParams });
    }

    private static boolean hasReturnValue(Method m) {
        return m.getGenericReturnType() != null;
    }

    private static Object[] convertJsonToParameters(Method m, List<JsonNode> params) throws IOException {
        Object[] convertedParams = new Object[params.size()];
        Type[] parameterTypes = m.getGenericParameterTypes();

        for (int i = 0; i < parameterTypes.length; i++) {
            JsonParser paramJsonParser = mapper.treeAsTokens(params.get(i));
            JavaType paramJavaType = mapper.getTypeFactory().constructType(parameterTypes[i]);

            convertedParams[i] = mapper.readerFor(paramJavaType)
                    .with(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                    .readValue(paramJsonParser);
        }
        return convertedParams;
    }
}
