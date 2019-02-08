package io.my.vertx.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.googlecode.jsonrpc4j.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

class JsonRpcException extends Exception {
    ErrorResolver.JsonError error;
    public JsonRpcException(ErrorResolver.JsonError jsonError) {
        super(jsonError.message);
        this.error = jsonError;
    }
}

@Slf4j
public class JsonRpcVerticle extends AbstractVerticle {

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

    private static final Logger log = LoggerFactory.getLogger(JsonRpcVerticle.class);

    private NetSocket socket;
    private ObjectMapper mapper;

    private Map<Long, Future<JsonNode>> futureMap = new HashMap<>();

    private Future<JsonNode> makeFuture(long id) {
        Future<JsonNode> future = Future.future();
        futureMap.put(id, future);
        return future;
    }

    public JsonRpcVerticle(NetSocket socket) {
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
                ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
                OutputStream output = new ByteBufOutputStream(buf);
                //should fetch the response message, and fire the invoke reponse listener
                handleJsonNode(node, output);
                Buffer buffer1 = Buffer.buffer(buf);
                log.info("output: {}" + buffer1);
                socket.write(buffer1);
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


    void handleJsonNode(final JsonNode node, OutputStream output) throws IOException {
        if (node.isArray()) {
            writeAndFlushValueError(output, createResponseError(VERSION, null, ErrorResolver.JsonError.INVALID_REQUEST));
            return;
        }
        if (node.isObject()) {

            long id = node.get(ID).asLong();

            if (isResponse((ObjectNode) node)) {
                //check id, and fire the response listener accordingly
                //if id is not exist, the just discard it and make a log.
                if (futureMap.containsKey(id)) {
                    log.info("Handle Result: {}", node);
                    futureMap.get(id).complete(node);
                } else {
                    log.info("No Handle Result: {}", node);
                }
                return;
            }

            if (!isValidRequest((ObjectNode) node)) {
                writeAndFlushValueError(output, createResponseError(VERSION, null, ErrorResolver.JsonError.INVALID_REQUEST));
            } else {
                handleRequest((ObjectNode) node, output);
            }
        }
    }

    private void writeAndFlushValue(OutputStream output, ObjectNode value) throws IOException {
        log.debug("Response: {}", value);
        mapper.writeValue(output, value);
    }

    private void writeAndFlushValueError(OutputStream output, ErrorObjectWithJsonError value) throws IOException {
        log.debug("failed {}", value);
        writeAndFlushValue(output, value.node);
    }

    private ErrorObjectWithJsonError createResponseError(String jsonRpc, Long id, ErrorResolver.JsonError errorObject) {
        ObjectNode response = mapper.createObjectNode();
        ObjectNode error = mapper.createObjectNode();
        error.put(ERROR_CODE, errorObject.code);
        error.put(ERROR_MESSAGE, errorObject.message);
        response.put(JSONRPC, jsonRpc);
        response.put(ID, id);
        response.set(ERROR, error);
        return new ErrorObjectWithJsonError(response, errorObject);
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

    protected void handleRequest(ObjectNode node, OutputStream output) {
    }
}
