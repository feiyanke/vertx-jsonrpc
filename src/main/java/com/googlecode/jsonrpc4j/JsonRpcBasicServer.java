package com.googlecode.jsonrpc4j;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.googlecode.jsonrpc4j.ErrorResolver.JsonError;
import net.iharder.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static com.googlecode.jsonrpc4j.ErrorResolver.JsonError.ERROR_NOT_HANDLED;
import static com.googlecode.jsonrpc4j.ReflectionUtil.findCandidateMethods;
import static com.googlecode.jsonrpc4j.ReflectionUtil.findBestMethodByParamsNode;
import static com.googlecode.jsonrpc4j.ReflectionUtil.getParameterTypes;
import static com.googlecode.jsonrpc4j.Util.hasNonNullData;

/**
 * A JSON-RPC request server reads JSON-RPC requests from an input stream and writes responses to an output stream.
 * Can even run on Android system.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class JsonRpcBasicServer {
	
	public static final String JSONRPC_CONTENT_TYPE = "application/json-rpc";
	public static final String PARAMS = "params";
	public static final String METHOD = "method";
	public static final String JSONRPC = "jsonrpc";
	public static final String ID = "id";
	public static final String CONTENT_ENCODING = "Content-Encoding";
	public static final String ACCEPT_ENCODING = "Accept-Encoding";
	public static final String ERROR = "error";
	public static final String ERROR_MESSAGE = "message";
	public static final String ERROR_CODE = "code";
	public static final String DATA = "data";
	public static final String RESULT = "result";
	public static final String EXCEPTION_TYPE_NAME = "exceptionTypeName";
	public static final String VERSION = "2.0";
	public static final int CODE_OK = 0;
	public static final String NULL = "null";
	private static final Logger logger = LoggerFactory.getLogger(JsonRpcBasicServer.class);
	private static final ErrorResolver DEFAULT_ERROR_RESOLVER = new MultipleErrorResolver(AnnotationsErrorResolver.INSTANCE, DefaultErrorResolver.INSTANCE);
	private static Pattern BASE64_PATTERN = Pattern.compile("[A-Za-z0-9_=-]+");

	
	static {
		ReflectionUtil.loadAnnotationSupportEngine();
	}
	
	private final ObjectMapper mapper;
	private final Class<?> remoteInterface;
	private final Object handler;
	protected HttpStatusCodeProvider httpStatusCodeProvider = null;
	private boolean backwardsCompatible = true;
	private boolean rethrowExceptions = false;
	private RequestInterceptor requestInterceptor = null;
	private ErrorResolver errorResolver = null;
	private InvocationListener invocationListener = null;
	private boolean shouldLogInvocationErrors = true;
	private List<JsonRpcInterceptor> interceptorList = new ArrayList<>();
	
	/**
	 * Creates the server with the given {@link ObjectMapper} delegating
	 * all calls to the given {@code handler}.
	 *
	 * @param mapper  the {@link ObjectMapper}
	 * @param handler the {@code handler}
	 */
	public JsonRpcBasicServer(final ObjectMapper mapper, final Object handler) {
		this(mapper, handler, null);
	}
	
	/**
	 * Creates the server with the given {@link ObjectMapper} delegating
	 * all calls to the given {@code handler} {@link Object} but only
	 * methods available on the {@code remoteInterface}.
	 *
	 * @param mapper          the {@link ObjectMapper}
	 * @param handler         the {@code handler}
	 * @param remoteInterface the interface
	 */
	public JsonRpcBasicServer(final ObjectMapper mapper, final Object handler, final Class<?> remoteInterface) {
		this.mapper = mapper;
		this.handler = handler;
		this.remoteInterface = remoteInterface;
		if (handler != null) {
			logger.debug("created server for interface {} with handler {}", remoteInterface, handler.getClass());
		}
	}
	
	/**
	 * Creates the server with a default {@link ObjectMapper} delegating
	 * all calls to the given {@code handler} {@link Object} but only
	 * methods available on the {@code remoteInterface}.
	 *
	 * @param handler         the {@code handler}
	 * @param remoteInterface the interface
	 */
	public JsonRpcBasicServer(final Object handler, final Class<?> remoteInterface) {
		this(new ObjectMapper(), handler, remoteInterface);
	}
	
	/**
	 * Creates the server with a default {@link ObjectMapper} delegating
	 * all calls to the given {@code handler}.
	 *
	 * @param handler the {@code handler}
	 */
	public JsonRpcBasicServer(final Object handler) {
		this(new ObjectMapper(), handler, null);
	}
	
	/**
	 * Returns parameters into an {@link InputStream} of JSON data.
	 *
	 * @param method the method
	 * @param id     the id
	 * @param params the base64 encoded params
	 * @return the {@link InputStream}
	 * @throws IOException on error
	 */
	static InputStream createInputStream(String method, String id, String params) throws IOException {
		
		StringBuilder envelope = new StringBuilder();
		
		envelope.append("{\"");
		envelope.append(JSONRPC);
		envelope.append("\":\"");
		envelope.append(VERSION);
		envelope.append("\",\"");
		envelope.append(ID);
		envelope.append("\":");
		
		// the 'id' value is assumed to be numerical.
		
		if (null != id && !id.isEmpty()) {
			envelope.append(id);
		} else {
			envelope.append("null");
		}
		
		envelope.append(",\"");
		envelope.append(METHOD);
		envelope.append("\":");
		
		if (null != method && !method.isEmpty()) {
			envelope.append('"');
			envelope.append(method);
			envelope.append('"');
		} else {
			envelope.append("null");
		}
		envelope.append(",\"");
		envelope.append(PARAMS);
		envelope.append("\":");
		
		if (null != params && !params.isEmpty()) {
			String decodedParams;
			
			// some specifications suggest that the GET "params" query parameter should be Base64 encoded and
			// some suggest not.  Try to deal with both scenarios -- the code here was previously only doing
			// Base64 decoding.
			// http://www.simple-is-better.org/json-rpc/transport_http.html
			// http://www.jsonrpc.org/historical/json-rpc-over-http.html#encoded-parameters
			
			if (BASE64_PATTERN.matcher(params).matches()) {
				decodedParams = new String(Base64.decode(params), StandardCharsets.UTF_8);
			} else {
				switch (params.charAt(0)) {
					case '[':
					case '{':
						decodedParams = params;
						break;
					
					default:
						throw new IOException("badly formed 'param' parameter starting with; [" + params.charAt(0) + "]");
				}
			}
			
			envelope.append(decodedParams);
		} else {
			envelope.append("[]");
		}
		
		envelope.append('}');
		
		return new ByteArrayInputStream(envelope.toString().getBytes(StandardCharsets.UTF_8));
	}
	
	public RequestInterceptor getRequestInterceptor() {
		return requestInterceptor;
	}
	
	public void setRequestInterceptor(RequestInterceptor requestInterceptor) {
		this.requestInterceptor = requestInterceptor;
	}
	
	/**
	 * Handles a single request from the given {@link InputStream},
	 * that is to say that a single {@link JsonNode} is read from
	 * the stream and treated as a JSON-RPC request.  All responses
	 * are written to the given {@link OutputStream}.
	 *
	 * @param input  the {@link InputStream}
	 * @param output the {@link OutputStream}
	 * @return the error code, or {@code 0} if none
	 * @throws IOException on error
	 */
	public int handleRequest(final InputStream input, final OutputStream output) throws IOException {
		final ReadContext readContext = ReadContext.getReadContext(input, mapper);
		try {
			readContext.assertReadable();
			final JsonNode jsonNode = readContext.nextValue();
			for (JsonRpcInterceptor interceptor : interceptorList) {
				interceptor.preHandleJson(jsonNode);
			}
			return handleJsonNodeRequest(jsonNode, output).code;
		} catch (JsonParseException | JsonMappingException e) {
			return writeAndFlushValueError(output, createResponseError(VERSION, NULL, JsonError.PARSE_ERROR)).code;
		}
	}
	
	/**
	 * Returns the handler's class or interfaces.  The variable serviceName is ignored in this class.
	 *
	 * @param serviceName the optional name of a service
	 * @return the class
	 */
	protected Class<?>[] getHandlerInterfaces(final String serviceName) {
		if (remoteInterface != null) {
			return new Class<?>[]{remoteInterface};
		} else if (Proxy.isProxyClass(handler.getClass())) {
			return handler.getClass().getInterfaces();
		} else {
			return new Class<?>[]{handler.getClass()};
		}
	}
	
	/**
	 * Handles the given {@link JsonNode} and writes the responses to the given {@link OutputStream}.
	 *
	 * @param node   the {@link JsonNode}
	 * @param output the {@link OutputStream}
	 * @return the error code, or {@code 0} if none
	 * @throws IOException on error
	 */
	protected JsonError handleJsonNodeRequest(final JsonNode node, final OutputStream output) throws IOException {
		if (node.isArray()) {
			return handleArray(ArrayNode.class.cast(node), output);
		}
		if (node.isObject()) {
			return handleObject(ObjectNode.class.cast(node), output);
		}
		return this.writeAndFlushValueError(output, this.createResponseError(VERSION, NULL, JsonError.INVALID_REQUEST));
	}
	
	/**
	 * Handles the given {@link ArrayNode} and writes the
	 * responses to the given {@link OutputStream}.
	 *
	 * @param node   the {@link JsonNode}
	 * @param output the {@link OutputStream}
	 * @return the error code, or {@code 0} if none
	 * @throws IOException on error
	 */
	private JsonError handleArray(ArrayNode node, OutputStream output) throws IOException {
		logger.debug("Handling {} requests", node.size());
		JsonError result = JsonError.OK;
		output.write('[');
		int errorCount = 0;
		for (int i = 0; i < node.size(); i++) {
			JsonError nodeResult = handleJsonNodeRequest(node.get(i), output);
			if (isError(nodeResult)) {
				result = JsonError.BULK_ERROR;
				errorCount += 1;
			}
			if (i != node.size() - 1) {
				output.write(',');
			}
		}
		output.write(']');
		logger.debug("served {} requests, error {}, result {}", node.size(), errorCount, result);
		// noinspection unchecked
		return result;
	}
	
	private boolean isError(JsonError result) {
		return result.code != JsonError.OK.code;
	}

	/**
	 * Handles the given {@link ObjectNode} and writes the
	 * responses to the given {@link OutputStream}.
	 *
	 * @param node   the {@link JsonNode}
	 * @param output the {@link OutputStream}
	 * @return the error code, or {@code 0} if none
	 * @throws IOException on error
	 */
	private JsonError handleObject(final ObjectNode node, final OutputStream output) throws IOException {
		logger.debug("Request: {}", node);
		
		if (!isValidRequest(node)) {
			return writeAndFlushValueError(output, createResponseError(VERSION, NULL, JsonError.INVALID_REQUEST));
		}
		Object id = parseId(node.get(ID));
		
		String jsonRpc = hasNonNullData(node, JSONRPC) ? node.get(JSONRPC).asText() : VERSION;
		if (!hasNonNullData(node, METHOD)) {
			return writeAndFlushValueError(output, createResponseError(jsonRpc, id, JsonError.METHOD_NOT_FOUND));
		}

		final String fullMethodName = node.get(METHOD).asText();
		final String partialMethodName = getMethodName(fullMethodName);
		final String serviceName = getServiceName(fullMethodName);

		Set<Method> methods = findCandidateMethods(getHandlerInterfaces(serviceName), partialMethodName);
		if (methods.isEmpty()) {
			return writeAndFlushValueError(output, createResponseError(jsonRpc, id, JsonError.METHOD_NOT_FOUND));
		}
		AMethodWithItsArgs methodArgs = findBestMethodByParamsNode(methods, node.get(PARAMS));
		if (methodArgs == null) {
			return writeAndFlushValueError(output, createResponseError(jsonRpc, id, JsonError.METHOD_PARAMS_INVALID));
		}
		try (InvokeListenerHandler handler = new InvokeListenerHandler(methodArgs, invocationListener)) {
			try {
				if (this.requestInterceptor != null) {
					this.requestInterceptor.interceptRequest(node);
				}
				Object target = getHandler(serviceName);
				// interceptors preHandle
				for (JsonRpcInterceptor interceptor : interceptorList) {
					interceptor.preHandle(target, methodArgs.method, methodArgs.arguments);
				}
				// invocation
				JsonNode result = ReflectionUtil.invoke(target, methodArgs.method, methodArgs.arguments);
				handler.result = result;
				// interceptors postHandle
				for (JsonRpcInterceptor interceptor : interceptorList) {
					interceptor.postHandle(target, methodArgs.method, methodArgs.arguments, result);
				}
				if (!isNotificationRequest(id)) {
					ObjectNode response = createResponseSuccess(jsonRpc, id, handler.result);
					writeAndFlushValue(output, response);
				}
				return JsonError.OK;
			} catch (Throwable e) {
				handler.error = e;
				return handleError(output, id, jsonRpc, methodArgs, e);
			}
		}
	}
	
	private JsonError handleError(OutputStream output, Object id, String jsonRpc, AMethodWithItsArgs methodArgs, Throwable e) throws IOException {
		Throwable unwrappedException = getException(e);
		
		if (shouldLogInvocationErrors) {
			logger.warn("Error in JSON-RPC Service", unwrappedException);
		}
		
		JsonError error = resolveError(methodArgs, unwrappedException);
		writeAndFlushValueError(output, createResponseError(jsonRpc, id, error));
		if (rethrowExceptions) {
			throw new RuntimeException(unwrappedException);
		}
		return error;
	}
	
	private Throwable getException(final Throwable thrown) {
		Throwable e = thrown;
		while (InvocationTargetException.class.isInstance(e)) {
			// noinspection ThrowableResultOfMethodCallIgnored
			e = InvocationTargetException.class.cast(e).getTargetException();
			while (UndeclaredThrowableException.class.isInstance(e)) {
				// noinspection ThrowableResultOfMethodCallIgnored
				e = UndeclaredThrowableException.class.cast(e).getUndeclaredThrowable();
			}
		}
		return e;
	}
	
	private JsonError resolveError(AMethodWithItsArgs methodArgs, Throwable e) {
		JsonError error;
		final ErrorResolver currentResolver = errorResolver == null ? DEFAULT_ERROR_RESOLVER : errorResolver;
		error = currentResolver.resolveError(e, methodArgs.method, methodArgs.arguments);
		if (error == null) {
			error = new JsonError(ERROR_NOT_HANDLED.code, e.getMessage(), e.getClass().getName());
		}
		return error;
	}
	
	private boolean isNotificationRequest(Object id) {
		return id == null;
	}
	
	private boolean isValidRequest(ObjectNode node) {
		return backwardsCompatible || hasMethodAndVersion(node);
	}
	
	private boolean hasMethodAndVersion(ObjectNode node) {
		return node.has(JSONRPC) && node.has(METHOD);
	}
	
	/**
	 * Get the service name from the methodNode.  In this class, it is always
	 * <code>null</code>.  Subclasses may parse the methodNode for service name.
	 *
	 * @param methodName the JsonNode for the method
	 * @return the name of the service, or <code>null</code>
	 */
	protected String getServiceName(final String methodName) {
		return null;
	}
	
	/**
	 * Get the method name from the methodNode.
	 *
	 * @param methodName the JsonNode for the method
	 * @return the name of the method that should be invoked
	 */
	protected String getMethodName(final String methodName) {
		return methodName;
	}
	
	/**
	 * Get the handler (object) that should be invoked to execute the specified
	 * RPC method.  Used by subclasses to return handlers specific to a service.
	 *
	 * @param serviceName an optional service name
	 * @return the handler to invoke the RPC call against
	 */
	protected Object getHandler(String serviceName) {
		return handler;
	}

	
	/**
	 * Convenience method for creating an error response.
	 *
	 * @param jsonRpc     the jsonrpc string
	 * @param id          the id
	 * @param errorObject the error data (if any)
	 * @return the error response
	 */
	private ErrorObjectWithJsonError createResponseError(String jsonRpc, Object id, JsonError errorObject) {
		ObjectNode response = mapper.createObjectNode();
		ObjectNode error = mapper.createObjectNode();
		error.put(ERROR_CODE, errorObject.code);
		error.put(ERROR_MESSAGE, errorObject.message);
		if (errorObject.data != null) {
			error.set(DATA, mapper.valueToTree(errorObject.data));
		}
		response.put(JSONRPC, jsonRpc);
		if (Integer.class.isInstance(id)) {
			response.put(ID, Integer.class.cast(id).intValue());
		} else if (Long.class.isInstance(id)) {
			response.put(ID, Long.class.cast(id).longValue());
		} else if (Float.class.isInstance(id)) {
			response.put(ID, Float.class.cast(id).floatValue());
		} else if (Double.class.isInstance(id)) {
			response.put(ID, Double.class.cast(id).doubleValue());
		} else if (BigDecimal.class.isInstance(id)) {
			response.put(ID, BigDecimal.class.cast(id));
		} else {
			response.put(ID, String.class.cast(id));
		}
		response.set(ERROR, error);
		return new ErrorObjectWithJsonError(response, errorObject);
	}
	
	/**
	 * Creates a success response.
	 *
	 * @param jsonRpc the version string
	 * @param id      the id of the request
	 * @param result  the result object
	 * @return the response object
	 */
	private ObjectNode createResponseSuccess(String jsonRpc, Object id, JsonNode result) {
		ObjectNode response = mapper.createObjectNode();
		response.put(JSONRPC, jsonRpc);
		if (Integer.class.isInstance(id)) {
			response.put(ID, Integer.class.cast(id).intValue());
		} else if (Long.class.isInstance(id)) {
			response.put(ID, Long.class.cast(id).longValue());
		} else if (Float.class.isInstance(id)) {
			response.put(ID, Float.class.cast(id).floatValue());
		} else if (Double.class.isInstance(id)) {
			response.put(ID, Double.class.cast(id).doubleValue());
		} else if (BigDecimal.class.isInstance(id)) {
			response.put(ID, BigDecimal.class.cast(id));
		} else {
			response.put(ID, String.class.cast(id));
		}
		response.set(RESULT, result);
		return response;
	}
	

	
	private JsonError writeAndFlushValueError(OutputStream output, ErrorObjectWithJsonError value) throws IOException {
		logger.debug("failed {}", value);
		writeAndFlushValue(output, value.node);
		return value.error;
	}
	
	/**
	 * Writes and flushes a value to the given {@link OutputStream}
	 * and prevents Jackson from closing it. Also writes newline.
	 *
	 * @param output the {@link OutputStream}
	 * @param value  the value to write
	 * @throws IOException on error
	 */
	private void writeAndFlushValue(OutputStream output, ObjectNode value) throws IOException {
		logger.debug("Response: {}", value);

		for (JsonRpcInterceptor interceptor : interceptorList) {
			interceptor.postHandleJson(value);
		}
		mapper.writeValue(new NoCloseOutputStream(output), value);
		output.write('\n');
	}
	
	private Object parseId(JsonNode node) {
		if (isNullNodeOrValue(node)) {
			return null;
		}
		if (node.isDouble()) {
			return node.asDouble();
		}
		if (node.isFloatingPointNumber()) {
			return node.asDouble();
		}
		if (node.isInt()) {
			return node.asInt();
		}
		if (node.isLong()) {
			return node.asLong();
		}
		//TODO(donequis): consider parsing bigints
		if (node.isIntegralNumber()) {
			return node.asInt();
		}
		if (node.isTextual()) {
			return node.asText();
		}
		throw new IllegalArgumentException("Unknown id type");
	}
	
	private boolean isNullNodeOrValue(JsonNode node) {
		return node == null || node.isNull();
	}
	
	/**
	 * Sets whether or not the server should be backwards
	 * compatible to JSON-RPC 1.0.  This only includes the
	 * omission of the jsonrpc property on the request object,
	 * not the class hinting.
	 *
	 * @param backwardsCompatible the backwardsCompatible to set
	 */
	public void setBackwardsCompatible(boolean backwardsCompatible) {
		this.backwardsCompatible = backwardsCompatible;
	}
	
	/**
	 * Sets whether or not the server should re-throw exceptions.
	 *
	 * @param rethrowExceptions true or false
	 */
	public void setRethrowExceptions(boolean rethrowExceptions) {
		this.rethrowExceptions = rethrowExceptions;
	}
	
	/**
	 * Sets whether or not the server should allow superfluous
	 * parameters to method calls.
	 *
	 * @param allowExtraParams true or false
	 */
	public void setAllowExtraParams(boolean allowExtraParams) {
		ReflectionUtil.allowExtraParams = allowExtraParams;
	}
	
	/**
	 * Sets whether or not the server should allow less parameters
	 * than required to method calls (passing null for missing params).
	 *
	 * @param allowLessParams the allowLessParams to set
	 */
	public void setAllowLessParams(boolean allowLessParams) {
		ReflectionUtil.allowLessParams = allowLessParams;
	}

	/**
	 * Sets the {@link ErrorResolver} used for resolving errors.
	 * Multiple {@link ErrorResolver}s can be used at once by
	 * using the {@link MultipleErrorResolver}.
	 *
	 * @param errorResolver the errorResolver to set
	 * @see MultipleErrorResolver
	 */
	public void setErrorResolver(ErrorResolver errorResolver) {
		this.errorResolver = errorResolver;
	}
	
	/**
	 * Sets the {@link InvocationListener} instance that can be
	 * used to provide feedback for capturing method-invocation
	 * statistics.
	 *
	 * @param invocationListener is the listener to set
	 */
	
	public void setInvocationListener(InvocationListener invocationListener) {
		this.invocationListener = invocationListener;
	}
	
	/**
	 * Sets the {@link HttpStatusCodeProvider} instance to use for HTTP error results.
	 *
	 * @param httpStatusCodeProvider the status code provider to use for translating JSON-RPC error codes into
	 *                               HTTP status messages.
	 */
	public void setHttpStatusCodeProvider(HttpStatusCodeProvider httpStatusCodeProvider) {
		this.httpStatusCodeProvider = httpStatusCodeProvider;
	}
	
	/**
	 * Sets the {@link ConvertedParameterTransformer} instance that can be
	 * used to mutate the deserialized arguments passed to the service method during invocation.
	 *
	 * @param convertedParameterTransformer the transformer to set
	 */
	public void setConvertedParameterTransformer(ConvertedParameterTransformer convertedParameterTransformer) {
		ReflectionUtil.convertedParameterTransformer = convertedParameterTransformer;
	}
	
	/**
	 * If true, then when errors arise in the invocation of JSON-RPC services, the error will be
	 * logged together with the underlying stack trace.  When false, no error will be logged.
	 * An alternative mechanism for logging invocation errors is to employ an implementation of
	 * {@link InvocationListener}.
	 *
	 * @param shouldLogInvocationErrors see method description
	 */
	public void setShouldLogInvocationErrors(boolean shouldLogInvocationErrors) {
		this.shouldLogInvocationErrors = shouldLogInvocationErrors;
	}

	private static class InvokeListenerHandler implements AutoCloseable {
		
		private final long startMs = System.currentTimeMillis();
		private final AMethodWithItsArgs methodArgs;
		private final InvocationListener invocationListener;
		public Throwable error = null;
		public JsonNode result = null;
		
		public InvokeListenerHandler(AMethodWithItsArgs methodArgs, InvocationListener invocationListener) {
			this.methodArgs = methodArgs;
			this.invocationListener = invocationListener;
			if (this.invocationListener != null) {
				this.invocationListener.willInvoke(methodArgs.method, methodArgs.arguments);
			}
		}
		
		@Override
		public void close() {
			if (invocationListener != null) {
				invocationListener.didInvoke(methodArgs.method, methodArgs.arguments, result, error, System.currentTimeMillis() - startMs);
			}
		}
	}
	


	public List<JsonRpcInterceptor> getInterceptorList() {
		return interceptorList;
	}

	public void setInterceptorList(List<JsonRpcInterceptor> interceptorList) {
		if (interceptorList == null) {
			throw new IllegalArgumentException("Interceptors list can't be null");
		}
		this.interceptorList = interceptorList;
	}
}
