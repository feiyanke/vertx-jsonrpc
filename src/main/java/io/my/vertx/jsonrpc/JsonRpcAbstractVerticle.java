package io.my.vertx.jsonrpc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.googlecode.jsonrpc4j.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static com.googlecode.jsonrpc4j.JsonRpcBasicServer.RESULT;

public class JsonRpcAbstractVerticle extends AbstractVerticle {

    public static final String PARAMS = "params";
    public static final String METHOD = "method";
    public static final String JSONRPC = "jsonrpc";
    public static final String ID = "id";
    public static final String ERROR = "error";
    public static final String ERROR_MESSAGE = "message";
    public static final String ERROR_CODE = "code";
    public static final String DATA = "data";
    public static final String RESULT = "result";
    public static final String VERSION = "2.0";
    public static final String NULL = "null";

    private static final Logger log = LoggerFactory.getLogger(JsonRpcAbstractVerticle.class);

    private NetSocket socket;
    private ObjectMapper mapper;

    private Map<Long, Promise<JsonNode>> promiseMap = new HashMap<>();

    private JsonRpcClient jsonRpcClient = new JsonRpcClient();

    private Promise<JsonNode> makePromise(long id) {
        Promise<JsonNode> promise = new DefaultPromise<JsonNode>(vertx.nettyEventLoopGroup().next());
        promiseMap.put(id, promise);
        return promise;
    }

    public JsonRpcAbstractVerticle(NetSocket socket) {
        this.socket = socket;
        this.mapper = new ObjectMapper();
    }

    @Override
    public void start(Future<Void> startFuture) {
        log.info(socket.remoteAddress() + " : connected");
        socket.handler(buffer -> {
            //now using one event loop thread to handler all request
            //should modify to another event loop thread.

            //1. parse json string to json object or array
            //2. if array, just invoke object handler for each (not support now)
            //3. if object, invoke object handler:
            //4. 	if is response msg, check id and call listener, else do nothing
            // (listener method run in event loop, should not block)
            //5.	if is method msg, check the method name and call method, else throw exception
            // (method should run in worker thread, in order not to block the event loop)
            //6.	if else, throw exception
            //7. if the method call finished, then get the result
            //8. if the method call get the exception, the get the exception and rethrow it.
            //9. if no exception, encode the result to json string and send it back with the same socket
            //10. if exception, encode the exception to json string and send it back with the same socket
            log.info("{} receive : {}", socket.remoteAddress(), buffer);
            InputStream input = new ByteBufInputStream(buffer.getByteBuf());
            ReadContext readContext = ReadContext.getReadContext(input, mapper);
            try {
                readContext.assertReadable();
                JsonNode node = readContext.nextValue();

                //should fetch the response message, and fire the invoke reponse listener
                handleJsonNode(node, objectNode -> {
                    ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
                    OutputStream output = new ByteBufOutputStream(buf);
                    try {
                        writeAndFlushValue(output, objectNode);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    Buffer buffer1 = Buffer.buffer(buf);
                    log.info("output: {}" + buffer1);
                    socket.write(buffer1);
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        socket.closeHandler(v -> {
            log.info("{} closed", socket.remoteAddress());
            vertx.undeploy(deploymentID());
        });
        socket.exceptionHandler(e -> {
            e.printStackTrace();
            log.info("{} exception", socket.remoteAddress());
            vertx.undeploy(deploymentID());
        });
//        socket.drainHandler(v -> {
//            log.info("{} drain", socket.remoteAddress());
//        });
//        socket.endHandler(v -> {
//            log.info("{} end", socket.remoteAddress());
//        });
    }

    @Override
    public void stop() throws Exception {
        socket.close();
    }


    private void handleJsonNode(JsonNode node, Handler<ObjectNode> handler) {

        if (node.isArray()) {
            handler.handle(createResponseError(VERSION, 0, ErrorResolver.JsonError.INVALID_REQUEST));
            return;
        }

        if (node.isObject()) {

            long id = node.get(ID).asLong();
            String jsonRpc = node.get(VERSION).asText();

            if (isResponse((ObjectNode) node)) {
                //check id, and fire the response listener accordingly
                //if id is not exist, the just discard it and make a log.
                if (promiseMap.containsKey(id)) {
                    if (isError((ObjectNode) node)) {
                        log.info("Handle Error: {}", node);
                        //TODO
                        promiseMap.get(id).setFailure(new Exception());
                    } else {
                        log.info("Handle Result: {}", node);
                        promiseMap.get(id).setSuccess(node);
                    }

                } else {
                    log.info("No Handle Result: {}", node);
                }
                return;
            }

            if (!isValidRequest((ObjectNode) node)) {
                handler.handle(createResponseError(VERSION, id, ErrorResolver.JsonError.INVALID_REQUEST));
            } else {
                //should execute in worker thread
                vertx.<ObjectNode>executeBlocking(f-> handleRequest((ObjectNode) node, f), false, ar -> {
                    if (ar.succeeded()) {
                        if (!isNotificationRequest(id)) {
                            handler.handle(ar.result());
                        }
                    } else {
                        handler.handle(ar.result());
                    }
                });
            }
        }
    }

    /**
     * Creates a success response.
     *
     * @param jsonRpc the version string
     * @param id      the id of the request
     * @param result  the result object
     * @return the response object
     */
    protected ObjectNode createResponseSuccess(String jsonRpc, long id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put(JSONRPC, jsonRpc);
        response.put(ID, id);
        response.set(RESULT, result);
        return response;
    }

    protected void writeAndFlushValue(OutputStream output, ObjectNode value) throws IOException {
        log.debug("Response: {}", value);
        mapper.writeValue(output, value);
    }

    protected void writeAndFlushValueError(OutputStream output, ErrorObjectWithJsonError value) throws IOException {
        log.debug("failed {}", value);
        writeAndFlushValue(output, value.node);
    }

    protected ObjectNode createResponseError(String jsonRpc, long id, ErrorResolver.JsonError errorObject) {
        ObjectNode response = mapper.createObjectNode();
        ObjectNode error = mapper.createObjectNode();
        error.put(ERROR_CODE, errorObject.code);
        error.put(ERROR_MESSAGE, errorObject.message);
        response.put(JSONRPC, jsonRpc);
        response.put(ID, id);
        response.set(ERROR, error);
        return response;
    }

    private boolean isResponse(ObjectNode node) {
        return node.has(RESULT) || node.has(ERROR);
    }

    private boolean isResult(ObjectNode node) {
        return node.has(RESULT);
    }

    private boolean isError(ObjectNode node) {
        return node.has(ERROR);
    }

    private boolean isNotificationRequest(long id) {
        return id == 0;
    }

    private boolean isValidRequest(ObjectNode node) {
        return hasMethodAndVersion(node);
    }

    private boolean hasMethodAndVersion(ObjectNode node) {
        return node.has(JSONRPC) && node.has(METHOD);
    }

    protected void handleRequest(ObjectNode node, Future<ObjectNode> future) {
        log.info("handleRequest: {}", node);
        future.fail(new NotImplementedException());
    }

    private long id = 1;

    protected Promise<JsonNode> makeInvoke(String methodName, Object argument) throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        OutputStream output = new ByteBufOutputStream(buf);
        jsonRpcClient.invoke(methodName, argument, output, String.valueOf(id));
        id+=1;
        Buffer buffer1 = Buffer.buffer(buf);
        log.info("invoke: {}" + buffer1);
        socket.write(buffer1);
        return makePromise(id);
    }

    protected Object readResponse(Type returnType, Promise<JsonNode> promise) throws ExecutionException, InterruptedException, IOException {
        JsonNode node = promise.get();
        JsonParser returnJsonParser = mapper.treeAsTokens(node.get(RESULT));
        JavaType returnJavaType = mapper.getTypeFactory().constructType(returnType);
        return mapper.readValue(returnJsonParser, returnJavaType);
    }

    class ClientInvocationHandler implements InvocationHandler {

        private String serviceName;
        public ClientInvocationHandler(Class clazz) {
            this.serviceName = ReflectionUtil.getServiceName(clazz);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            final Object arguments = ReflectionUtil.parseArguments(method, args);
            final String methodName = serviceName + "." + ReflectionUtil.getMethodName(method);
            return readResponse(method.getGenericReturnType(), makeInvoke(methodName, arguments));

        }
    }

    @SuppressWarnings("unchecked")
    public <T> T createClientProxy(Class<T> proxyInterface) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{proxyInterface}, new ClientInvocationHandler(proxyInterface));
    }
}
