/******************************************************************
 *
 * Copyright 2017 Samsung Electronics All Rights Reserved.
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 ******************************************************************/

package org.edge.protocol.opcua.api;

import java.sql.Timestamp;
import java.util.List;
import org.eclipse.milo.opcua.stack.core.Stack;
import org.edge.protocol.opcua.api.client.EdgeResponse;
import org.edge.protocol.opcua.api.common.EdgeBrowseResult;
import org.edge.protocol.opcua.api.common.EdgeCommandType;
import org.edge.protocol.opcua.api.common.EdgeConfigure;
import org.edge.protocol.opcua.api.common.EdgeDevice;
import org.edge.protocol.opcua.api.common.EdgeEndpointInfo;
import org.edge.protocol.opcua.api.common.EdgeNodeInfo;
import org.edge.protocol.opcua.api.common.EdgeMessage;
import org.edge.protocol.opcua.api.common.EdgeMessageType;
import org.edge.protocol.opcua.api.common.EdgeNodeId;
import org.edge.protocol.opcua.api.common.EdgeNodeIdentifier;
import org.edge.protocol.opcua.api.common.EdgeRequest;
import org.edge.protocol.opcua.api.common.EdgeResult;
import org.edge.protocol.opcua.api.common.EdgeStatusCode;
import org.edge.protocol.opcua.api.common.EdgeVersatility;
import org.edge.protocol.opcua.api.server.EdgeArgumentType;
import org.edge.protocol.opcua.api.server.EdgeNodeItem;
import org.edge.protocol.opcua.api.server.EdgeNodeType;
import org.edge.protocol.opcua.api.server.EdgeReference;
import org.edge.protocol.opcua.command.Browse;
import org.edge.protocol.opcua.command.CommandExecutor;
import org.edge.protocol.opcua.command.Method;
import org.edge.protocol.opcua.command.Read;
import org.edge.protocol.opcua.command.Subscription;
import org.edge.protocol.opcua.command.Write;
import org.edge.protocol.opcua.namespace.EdgeNamespace;
import org.edge.protocol.opcua.namespace.EdgeNamespaceManager;
import org.edge.protocol.opcua.providers.EdgeServices;
import org.edge.protocol.opcua.queue.ErrorHandler;
import org.edge.protocol.opcua.queue.MessageDispatcher;
import org.edge.protocol.opcua.queue.MessageInterface;
import org.edge.protocol.opcua.session.EdgeOpcUaClient;
import org.edge.protocol.opcua.session.EdgeOpcUaServer;
import org.edge.protocol.opcua.session.EdgeSessionManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtocolManager implements MessageInterface {
  private final Logger logger = LoggerFactory.getLogger(getClass());
  private static ProtocolManager protocolManager = null;
  private static MessageDispatcher recvMessageDispatcher = null;
  private static MessageDispatcher sendMessageDispatcher = null;
  private static Object recvLock = new Object();
  private static Object sendLock = new Object();
  private ReceivedMessageCallback recvCallback = null;
  private StatusCallback statusCallback = null;
  private DiscoveryCallback discoveryCallback = null;
  private static int LIMIT_SIZE = 4;

  /**
   * get protocol manager instance
   * 
   * @return ProtocolManager instance
   */
  public synchronized static ProtocolManager getProtocolManagerInstance() {

    if (protocolManager == null) {
      protocolManager = new ProtocolManager();
    }
    return protocolManager;
  }

  /**
   * initialize configuration such as callback
   * 
   * @param configure edge configure to set
   */
  public void configure(EdgeConfigure configure) {
    registerRecvCallback(configure.getRecvCallback());
    registerStatusCallback(configure.getStatusCallback());
    registerDiscoveryCallback(configure.getDiscoveryCallback());
  }

  private void registerRecvCallback(ReceivedMessageCallback callback) {
    recvCallback = callback;
  }

  private void registerStatusCallback(StatusCallback callback) {
    statusCallback = callback;
  }

  private void registerDiscoveryCallback(DiscoveryCallback callback) {
    discoveryCallback = callback;
  }

  /**
   * get message dispatcher instance
   * 
   * @return MessageDispatcher instance
   */
  public MessageDispatcher getRecvDispatcher() {

    synchronized (recvLock) {
      if (null == recvMessageDispatcher) {
        recvMessageDispatcher = new MessageDispatcher();
        recvMessageDispatcher.start();
      }
      return recvMessageDispatcher;
    }
  }

  /**
   * add request data into send queue
   * 
   * @param msg message
   * @return result
   */
  public EdgeResult send(EdgeMessage msg) throws Exception {
    EdgeResult ret = new EdgeResult.Builder(EdgeStatusCode.STATUS_OK).build();
    try {
      ret = checkParameterValid(msg);
    } catch (Exception e) {
      ret = new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
      e.printStackTrace();
    }
    if (ret.getStatusCode() != EdgeStatusCode.STATUS_OK) {
      return ret;
    }

    return new EdgeResult.Builder(
        ProtocolManager.getProtocolManagerInstance().getSendDispatcher().putQ(msg)
            ? EdgeStatusCode.STATUS_OK : EdgeStatusCode.STATUS_ENQUEUE_ERROR).build();
  }

  /**
   * add request data into send queue
   * 
   * @param msg message
   * @return result
   */
  private EdgeResult checkParameterValid(EdgeMessage msg) throws Exception {
    if (msg == null || msg.getCommand() == null || msg.getMessageType() == null) {
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    }

    if (msg.getRequest() != null && msg.getRequest().getEdgeNodeInfo() == null) {
      logger.info("endpoint is invalid");
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    } else if (msg.getRequests() != null) {
      for (EdgeRequest req : msg.getRequests()) {
        if (req.getEdgeNodeInfo() == null) {
          logger.info("endpoint is invalid");
          return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
        }
      }
    } else if ((msg.getMessageType() == EdgeMessageType.SEND_REQUEST && msg.getRequest() == null)
        || (msg.getMessageType() == EdgeMessageType.SEND_REQUESTS && msg.getRequests() == null)) {
      logger.info("request parameter is invalid");
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    } else if ((msg.getCommand() == EdgeCommandType.CMD_READ
        || msg.getCommand() == EdgeCommandType.CMD_WRITE
        || msg.getCommand() == EdgeCommandType.CMD_BROWSE
        || msg.getCommand() == EdgeCommandType.CMD_METHOD
        || msg.getCommand() == EdgeCommandType.CMD_SUB) && msg.getRequest() != null
        && msg.getRequest().getEdgeNodeInfo().getValueAlias() == null) {
      logger.info("{} command should use valueAlias", msg.getCommand());
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    } else if ((msg.getCommand() == EdgeCommandType.CMD_METHOD
        || msg.getCommand() == EdgeCommandType.CMD_SUB) && msg.getRequest() == null) {
      logger.info("{} command should use single request", msg.getCommand());
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    } else if ((msg.getCommand() == EdgeCommandType.CMD_METHOD
        || msg.getCommand() == EdgeCommandType.CMD_SUB) && msg.getRequests() != null) {
      logger.info("{} command can not use multiple request", msg.getCommand());
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    } else if (msg.getCommand() == EdgeCommandType.CMD_METHOD
        && msg.getRequest().getMessage() == null) {
      logger.info("{} command is needed to parameter", msg.getCommand());
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    } else if (msg.getCommand() == EdgeCommandType.CMD_SUB
        && (msg.getRequest().getSubRequest() == null || (msg.getRequest().getSubRequest() != null
            && msg.getRequest().getSubRequest().getSubType() == null))) {
      logger.info("{} command should set both subRequest and subType", msg.getCommand());
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    } else if (msg.getCommand() == EdgeCommandType.CMD_BROWSE && msg.getBrowseParameter() == null) {
      logger.info("{} command should set browse-parameter", msg.getCommand());
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    }
    return new EdgeResult.Builder(EdgeStatusCode.STATUS_OK).build();
  }

  private MessageDispatcher getSendDispatcher() {
    synchronized (sendLock) {
      if (null == sendMessageDispatcher) {
        sendMessageDispatcher = new MessageDispatcher();
        sendMessageDispatcher.start();
      }
      return sendMessageDispatcher;
    }
  }

  /**
   * terminate send queue dispatcher and receive queue dispatcher
   */
  public void close() throws Exception {

    synchronized (recvLock) {
      if (recvMessageDispatcher != null) {
        recvMessageDispatcher.terminate();
        recvMessageDispatcher.interrupt();
        recvMessageDispatcher = null;
      }
    }

    synchronized (sendLock) {
      if (sendMessageDispatcher != null) {
        sendMessageDispatcher.terminate();
        sendMessageDispatcher.interrupt();
        sendMessageDispatcher = null;
      }
    }

    EdgeSessionManager.getInstance().close();
    protocolManager = null;
    Stack.releaseSharedResources();
  }

  /**
   * callback related response message. The callback called onResponseMessages in
   * ReceivedMessageCallback will be called inside.
   * 
   * @param msg response message as EdgeMessage
   */
  @Override
  public void onResponseMessage(EdgeMessage msg) throws Exception {
    for (EdgeResponse res : msg.getResponses()) {
      if (msg.getMessageType() == EdgeMessageType.BROWSE_RESPONSE) {
        logger.info("onResponse requestId={}, msg type={}", res.getRequestId(),
            msg.getMessageType());
        recvCallback.onBrowseMessage(res.getEdgeNodeInfo(), msg.getBrowseResults(),
            res.getRequestId());
      } else {
        logger.info("onResponse valueName={}, requestId={}, msg type={}",
            res.getEdgeNodeInfo().getValueAlias(), res.getRequestId(), msg.getMessageType());
      }
    }
    if (recvCallback != null) {
      if (msg.getMessageType() != EdgeMessageType.BROWSE_RESPONSE) {
        recvCallback.onResponseMessages(msg);
      }
    }
  }

  /**
   * callback related monitoring message. The callback called onMonitoredMessage in
   * ReceivedMessageCallback will be called inside.
   * 
   * @param msg monitoring message as EdgeMessage
   */
  @Override
  public void onMonitoredMessage(EdgeMessage msg) throws Exception {
    for (EdgeResponse res : msg.getResponses()) {
      logger.debug("value={}, valueName={}, requestId={}, msg type={}", res.getMessage().getValue(),
          res.getEdgeNodeInfo().getValueAlias(), res.getRequestId(), msg.getMessageType());
      if (recvCallback != null) {
        recvCallback.onMonitoredMessage(msg);
      }
    }
  }

  /**
   * callback related error message. The callback called onErrorMessage in ReceivedMessageCallback
   * will be called inside.
   * 
   * @param msg error message as EdgeMessage
   */
  @Override
  public void onErrorCallback(EdgeMessage msg) throws Exception {
    if (msg == null) {
      logger.info("message is invalid");
      return;
    }
    if (msg.getResponses() != null) {
      for (EdgeResponse res : msg.getResponses()) {
        Timestamp stamp = new Timestamp(System.currentTimeMillis());
        logger.info("onError time={}, result={}", stamp, msg.getResult().getStatusCode());
        if (res != null && res.getMessage() != null) {
          logger.info("onError value={}", res.getMessage().getValue());
        } else if (msg.getRequest() != null && msg.getRequest().getEdgeNodeInfo() != null) {
          logger.info("onError provider={}", msg.getRequest().getEdgeNodeInfo().getValueAlias());
        }
        if (recvCallback != null) {
          recvCallback.onErrorMessage(msg);
        }
      }
    }
  }

  /**
   * process send request message from send queue
   * 
   * @param msg send message as EdgeMessage
   */
  @Override
  public void onSendMessage(EdgeMessage msg) {
    if (msg.getCommand() == EdgeCommandType.CMD_READ) {
      Read read = new Read();
      try {
        new CommandExecutor(read).run(msg);
        logger.info("read message has called");
      } catch (Exception e) {
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_READ_SYNC) {
      Read read = new Read();
      try {
        new CommandExecutor(read).run(msg);
        logger.info("read message has called");
      } catch (Exception e) {
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_WRITE) {
      Write write = new Write();
      try {
        new CommandExecutor(write).run(msg);
      } catch (Exception e) {
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_SUB) {
      Subscription sub = new Subscription();
      try {
        new CommandExecutor(sub).run(msg);
      } catch (Exception e) {
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_START_CLIENT) {
      try {
        EdgeSessionManager.getInstance().configure(msg.getEdgeEndpointInfo());
        EdgeOpcUaClient client =
            EdgeSessionManager.getInstance().getSession(msg.getEdgeEndpointInfo().getEndpointUri());
        if (client != null) {
          client.connect(msg.getEdgeEndpointInfo().getEndpointUri(),
              msg.getEdgeEndpointInfo().getFuture());
        }
      } catch (Exception e) {
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_START_SERVER) {
      try {
        EdgeOpcUaServer server = EdgeOpcUaServer.getInstance();
        if (server != null) {
          server.start(msg.getEdgeEndpointInfo());
        }
      } catch (Exception e) {
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_STOP_CLIENT) {
      try {
        EdgeOpcUaClient client =
            EdgeSessionManager.getInstance().getSession(msg.getEdgeEndpointInfo().getEndpointUri());
        if (client != null) {
          client.disconnect();
          client.terminate();
          client = null;

          EdgeEndpointInfo ep =
              new EdgeEndpointInfo.Builder(msg.getEdgeEndpointInfo().getEndpointUri())
                  .setFuture(msg.getEdgeEndpointInfo().getFuture())
                  .setConfig(msg.getEdgeEndpointInfo().getConfig()).build();
          ProtocolManager.getProtocolManagerInstance().onStatusCallback(ep,
              EdgeStatusCode.STATUS_STOP_CLIENT);
        }
      } catch (Exception e) {
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_STOP_SERVER) {
      try {
        EdgeOpcUaServer server = EdgeOpcUaServer.getInstance();
        if (server != null) {
          server.stop();
          server.terminate();
          server.close();
          server = null;

          EdgeEndpointInfo ep =
              new EdgeEndpointInfo.Builder(msg.getEdgeEndpointInfo().getEndpointUri())
                  .setConfig(msg.getEdgeEndpointInfo().getConfig()).build();
          ProtocolManager.getProtocolManagerInstance().onStatusCallback(ep,
              EdgeStatusCode.STATUS_STOP_SERVER);
        }
      } catch (Exception e) {
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_GET_ENDPOINTS) {
      try {
        EdgeDevice device = parseEndpointUri(msg);
        if (discoveryCallback != null && device != null) {
          discoveryCallback.onFoundEndpoint(device);
        }
      } catch (Exception e) {
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_BROWSE) {
      Browse browse = new Browse();
      try {
        new CommandExecutor(browse).run(msg);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    } else if (msg.getCommand() == EdgeCommandType.CMD_METHOD) {
      Method method = new Method();
      try {
        new CommandExecutor(method).run(msg);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
        ErrorHandler.getInstance().addErrorMessage(msg.getRequest().getEdgeNodeInfo(),
            new EdgeResult.Builder(EdgeStatusCode.STATUS_INTERNAL_ERROR).build(),
            msg.getRequest().getRequestId());
      }
    }
  }

  /**
   * create Namespace depend on OPC-UA on server side
   * 
   * @param name namespace alias to use
   * @param rootNodeId path name in root node
   * @param rootBrowseName browse name in root node
   * @param rootDisplayName display name in root node
   * @return result
   */
  public EdgeResult createNamespace(String name, String rootNodeId, String rootBrowseName,
      String rootDisplayName) throws Exception {
    EdgeOpcUaServer server = EdgeOpcUaServer.getInstance();
    if (server != null) {
      logger.debug("namespace = {}", name);
      try {
        server.createNamespace(name, rootNodeId, rootBrowseName, rootDisplayName);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
        return new EdgeResult.Builder(EdgeStatusCode.STATUS_ERROR).build();
      }
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_OK).build();
    }
    return new EdgeResult.Builder(EdgeStatusCode.STATUS_NOT_START_SERVER).build();
  }

  /**
   * get nodes from node manager
   * 
   * @return list of nodeId
   */
  public List<EdgeNodeId> getNodes() {
    return EdgeOpcUaServer.getInstance().getNodes();
  }

  /**
   * get nodes from node manager
   * 
   * @param browseName browse name
   * @return list of nodeId
   */
  public List<EdgeNodeId> getNodes(String BrowseName) {
    return EdgeOpcUaServer.getInstance().getNodes(BrowseName);
  }

  /**
   * create node depend on OPC-UA on server side (createNamespace should be called before)
   * 
   * @param namespaceUri namespace URI to create
   * @param item node item
   * @param type node type (method node or data access node or variable node)
   * @return result
   */
  public EdgeResult createNode(String namespaceUri, EdgeNodeItem item, EdgeNodeType type)
      throws Exception {
    if (type == EdgeNodeType.Edge_Node_Classical_DataAccess_Type) {
      try {
        return EdgeNamespaceManager.getInstance().getNamespace(namespaceUri)
            .createDataAccessNode(item);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
        return new EdgeResult.Builder(EdgeStatusCode.STATUS_ERROR).build();
      }
    } else if (type == EdgeNodeType.Edge_Node_Method_Type) {
      logger.error("method class instance is invalied");
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_PARAM_INVALID).build();
    } else {
      try {
        return EdgeNamespaceManager.getInstance().getNamespace(namespaceUri).createNodes(item);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
        return new EdgeResult.Builder(EdgeStatusCode.STATUS_ERROR).build();
      }
    }
  }

  /**
   * create node depend on OPC-UA on server side (createNamespace should be called before)
   * 
   * @param namespace namespace alias to create
   * @param item node item
   * @param type node type (method node or data access node or variable node)
   * @param methodObj method class
   * @param type the argument type of the method
   * @return result
   */
  public EdgeResult createNode(String namespace, EdgeNodeItem item, EdgeNodeType type,
      Object methodObj, EdgeArgumentType argType) throws Exception {
    if (type == EdgeNodeType.Edge_Node_Method_Type) {
      try {
        EdgeNamespaceManager.getInstance().getNamespace(namespace).createMethodNode(item, methodObj,
            argType);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
        return new EdgeResult.Builder(EdgeStatusCode.STATUS_ERROR).build();
      }
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_OK).build();
    } else {
      return createNode(namespace, item, type);
    }
  }

  /**
   * add reference with node
   * 
   * @param reference
   * @return result
   */
  public EdgeResult addReference(EdgeReference reference) {
    try {
      EdgeNamespaceManager.getInstance().getNamespace(reference.getSourceNamespace())
          .addReferences(reference);
    } catch (Exception e) {
      e.printStackTrace();
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_ERROR).build();
    }
    return new EdgeResult.Builder(EdgeStatusCode.STATUS_OK).build();
  }


  /**
   * modify value of target variable node on server side
   * 
   * @param npName namespace alias
   * @param nodeUri node uri related variable node
   * @param value modified value
   * @return result
   */
  public EdgeResult modifyVariableNodeValue(String npName, String nodeUri, EdgeVersatility value)
      throws Exception {
    EdgeNamespace namespace = EdgeNamespaceManager.getInstance().getNamespace(npName);
    try {
      namespace.modifyNode(nodeUri, value);
    } catch (Exception e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_ERROR).build();
    }
    return new EdgeResult.Builder(EdgeStatusCode.STATUS_OK).build();
  }

  /**
   * modify value of target data access node on server side
   * 
   * @param npName namespace alias
   * @param id data access node id
   * @param value modified value
   * @return result
   */
  public EdgeResult modifyDataAccessNodeValue(String npName, EdgeNodeIdentifier id,
      EdgeVersatility value) throws Exception {
    EdgeNamespace namespace = EdgeNamespaceManager.getInstance().getNamespace(npName);
    try {
      namespace.modifyNode(id, value);
    } catch (Exception e) {
      e.printStackTrace();
      return new EdgeResult.Builder(EdgeStatusCode.STATUS_ERROR).build();
    }
    return new EdgeResult.Builder(EdgeStatusCode.STATUS_OK).build();
  }

  private EdgeDevice parseEndpointUri(EdgeMessage msg) throws Exception {
    String endpointUri = msg.getEdgeEndpointInfo().getEndpointUri();
    if (discoveryCallback != null) {
      logger.info("onFoundEndpoint = {}", endpointUri);

      int index = 0;
      String[] deviceSet = new String[LIMIT_SIZE];
      String[] dataArr = endpointUri.split("//", 2);
      for (String str : dataArr) {
        if (str.equalsIgnoreCase("opc.tcp:"))
          continue;
        String[] pathSet = str.split("/", 2);
        for (String path : pathSet) {
          if (index == LIMIT_SIZE) {
            deviceSet[index] = path;
          }
          logger.info("str = {}", path);
          String[] addressSet = path.split(":");
          for (String address : addressSet) {
            if (index == LIMIT_SIZE - 1) {
              index++;
              break;
            }
            deviceSet[index] = address;
            index++;
          }
        }
      }
      return new EdgeDevice.Builder(deviceSet[0], Integer.parseInt(deviceSet[1].toString()))
          .setServerName(deviceSet[2])
          .setEndpoints(EdgeSessionManager.getInstance().getEndpoints(msg)).build();
    }
    return null;
  }

  /**
   * callback related status. The callback called onInit, onDeinit, onNetworkStatus in
   * ReceivedMessageCallback.
   * 
   * @param ep endpoint
   * @param status status
   */
  public void onStatusCallback(EdgeEndpointInfo ep, EdgeStatusCode status) {
    logger.info("onStatusCallback...");
    if (statusCallback == null) {
      logger.info("status callback is not available");
      return;
    } else {
      logger.info("status callback is alive");
    }

    if (EdgeStatusCode.STATUS_CLIENT_STARTED == status
        || EdgeStatusCode.STATUS_SERVER_STARTED == status) {
      statusCallback.onStart(ep, status, EdgeServices.getAttributeProviderKeyList(),
          EdgeServices.getMethodProviderKeyList(), EdgeServices.getViewProviderKeyList());
    } else if (EdgeStatusCode.STATUS_STOP_SERVER == status
        || EdgeStatusCode.STATUS_STOP_CLIENT == status) {
      statusCallback.onStop(ep, status);
    } else if (EdgeStatusCode.STATUS_CONNECTED == status
        || EdgeStatusCode.STATUS_DISCONNECTED == status) {
      statusCallback.onNetworkStatus(ep, status);
    } else {
      logger.info("there is no available status code");
    }
  }

  public interface ReceivedMessageCallback {
    public void onResponseMessages(EdgeMessage data);

    public void onMonitoredMessage(EdgeMessage data);

    public void onErrorMessage(EdgeMessage data);

    public void onBrowseMessage(EdgeNodeInfo endpoint, List<EdgeBrowseResult> responses,
        int requestId);
  }

  public interface StatusCallback {
    public void onStart(EdgeEndpointInfo ep, EdgeStatusCode status,
        List<String> attiributeAliasList, List<String> methodAliasList, List<String> viewAliasList);

    public void onStop(EdgeEndpointInfo ep, EdgeStatusCode status);

    public void onNetworkStatus(EdgeEndpointInfo ep, EdgeStatusCode status);
  }

  public interface DiscoveryCallback {
    public void onFoundEndpoint(EdgeDevice device);

    public void onFoundDevice(EdgeDevice device);
  }
}
