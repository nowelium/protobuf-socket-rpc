// Copyright (c) 2009 Shardul Deo
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package com.googlecode.protobuf.socketrpc;

import java.util.HashMap;
import java.util.Map;

import com.google.protobuf.BlockingService;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.Service;
import com.google.protobuf.ServiceException;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos.ErrorReason;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos.Request;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos.Response;
import com.googlecode.protobuf.socketrpc.SocketRpcProtos.Response.Builder;

/**
 * Proxy that handles the RPC received by the server and forwards it to the
 * appropriate service.
 *
 * @author Shardul Deo
 */
class RpcForwarder {

  private final Map<String, Service> serviceMap =
    new HashMap<String, Service>();
  private final Map<String, BlockingService> blockingServiceMap =
    new HashMap<String, BlockingService>();

  /**
   * Register an RPC service implementation to this forwarder.
   */
  public void registerService(Service service) {
    serviceMap.put(service.getDescriptorForType().getFullName(), service);
  }

  /**
   * Register an RPC blocking service implementation to this forwarder.
   */
  public void registerBlockingService(BlockingService service) {
    blockingServiceMap.put(service.getDescriptorForType().getFullName(),
        service);
  }

  /**
   * Handle the RPC request by forwarding it to the correct service/method.
   *
   * @throws RpcException If there was some error executing the RPC.
   */
  public SocketRpcProtos.Response doRPC(SocketRpcProtos.Request rpcRequest)
      throws RpcException {
    // Get the service, first try BlockingService
    BlockingService blockingService = blockingServiceMap.get(
        rpcRequest.getServiceName());
    if (blockingService != null) {
      return forwardToBlockingService(rpcRequest, blockingService);
    }

    // Now try Service
    Service service = serviceMap.get(rpcRequest.getServiceName());
    if (service == null) {
      throw new RpcException(ErrorReason.SERVICE_NOT_FOUND,
          "Could not find service: " + rpcRequest.getServiceName(), null);
    }
    return forwardToService(rpcRequest, service);
  }

  private Response forwardToBlockingService(Request rpcRequest,
      BlockingService blockingService) throws RpcException {
    // Get matching method
    MethodDescriptor method = getMethod(rpcRequest,
        blockingService.getDescriptorForType());

    // Create request for method
    Message request = getRequestProto(rpcRequest,
        blockingService.getRequestPrototype(method));

    // Call method
    SocketRpcController socketController = new SocketRpcController();
    try {
      Message response = blockingService.callBlockingMethod(method,
          socketController, request);
      return createRpcResponse(response, true, socketController);
    } catch (ServiceException e) {
      throw new RpcException(ErrorReason.RPC_FAILED, e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new RpcException(ErrorReason.RPC_ERROR,
          "Error running method " + method.getFullName(), e);
    }
  }

  private Response forwardToService(SocketRpcProtos.Request rpcRequest,
      Service service) throws RpcException {
    // Get matching method
    MethodDescriptor method = getMethod(rpcRequest,
        service.getDescriptorForType());

    // Create request for method
    Message request = getRequestProto(rpcRequest,
        service.getRequestPrototype(method));

    // Call method
    SocketRpcController socketController = new SocketRpcController();
    Callback callback = new Callback();
    try {
      // TODO: Make this truly async by calling the method in another thread so
      // that the callback is async.
      service.callMethod(method, socketController, request, callback);
    } catch (RuntimeException e) {
      throw new RpcException(ErrorReason.RPC_ERROR,
          "Error running method " + method.getFullName(), e);
    }

    // Build and return response (callback invocation is optional)
    return createRpcResponse(callback.response, callback.invoked,
        socketController);
  }

  /**
   * Get matching method.
   */
  private MethodDescriptor getMethod(SocketRpcProtos.Request rpcRequest,
      ServiceDescriptor descriptor) throws RpcException {
    MethodDescriptor method = descriptor.findMethodByName(
        rpcRequest.getMethodName());
    if (method == null) {
      throw new RpcException(
          ErrorReason.METHOD_NOT_FOUND,
          String.format("Could not find method %s in service %s",
              rpcRequest.getMethodName(), descriptor.getFullName()),
          null);
    }
    return method;
  }

  /**
   * Get request protobuf for the RPC method.
   */
  private Message getRequestProto(SocketRpcProtos.Request rpcRequest,
      Message requestPrototype) throws RpcException {
    Message.Builder builder;
    try {
      builder = requestPrototype.newBuilderForType()
          .mergeFrom(rpcRequest.getRequestProto());
      if (!builder.isInitialized()) {
        throw new RpcException(ErrorReason.BAD_REQUEST_PROTO,
            "Invalid request proto", null);
      }
    } catch (InvalidProtocolBufferException e) {
      throw new RpcException(ErrorReason.BAD_REQUEST_PROTO,
          "Invalid request proto", e);
    }
    return builder.build();
  }

  /**
   * Create RPC response protobuf from method invocation results.
   */
  private SocketRpcProtos.Response createRpcResponse(Message response,
      boolean callbackInvoked, SocketRpcController socketController) {
    Builder responseBuilder = SocketRpcProtos.Response.newBuilder();
    if (response != null) {
      responseBuilder.setCallback(true).setResponseProto(
          response.toByteString());
    } else {
      // Set whether callback was called (in case of async)
      responseBuilder.setCallback(callbackInvoked);
    }
    if (socketController.failed()) {
      responseBuilder.setError(socketController.errorText());
      responseBuilder.setErrorReason(ErrorReason.RPC_FAILED);
    }
    return responseBuilder.build();
  }

  /**
   * Callback that just saves the response and the fact that it was invoked.
   */
  private static class Callback implements RpcCallback<Message> {

    private Message response;
    private boolean invoked = false;

    public void run(Message response) {
      this.response = response;
      invoked = true;
    }
  }

  /**
   * Signifies error while handling RPC.
   */
  static class RpcException extends Exception {
    private static final long serialVersionUID = 5564350850262733223L;
    public final ErrorReason errorReason;
    public final String msg;

    public RpcException(ErrorReason errorReason, String msg, Exception cause) {
      super(msg, cause);
      this.errorReason = errorReason;
      this.msg = msg;
    }
  }
}
