/**
 * Copyright 2016 TIBCO Software Inc. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not use this file except 
 * in compliance with the License.
 * A copy of the License is included in the distribution package with this file.
 * You also may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * <p/>
 * File name : ConnectionImpl.${EXT}
 * Created on: 1/10/15
 * Created by: suresh 
 * <p/>
 * SVN Id: $Id: ConnectionImpl.java 823 2016-05-12 12:47:02Z vchung $
 */


package com.tibco.tgdb.connection.impl;

import com.tibco.tgdb.channel.TGChannel;
import com.tibco.tgdb.channel.TGChannelResponse;
import com.tibco.tgdb.channel.impl.AbstractChannel;
import com.tibco.tgdb.channel.impl.BlockingChannelResponse;
import com.tibco.tgdb.connection.TGConnection;
import com.tibco.tgdb.connection.TGConnectionExceptionListener;
import com.tibco.tgdb.exception.TGException;
import com.tibco.tgdb.log.TGLogManager;
import com.tibco.tgdb.log.TGLogger;
import com.tibco.tgdb.log.TGLogger.TGLevel;
import com.tibco.tgdb.model.*;
import com.tibco.tgdb.model.impl.*;
import com.tibco.tgdb.pdu.TGInputStream;
import com.tibco.tgdb.pdu.TGMessageFactory;
import com.tibco.tgdb.pdu.VerbId;
import com.tibco.tgdb.pdu.impl.CommitTransactionRequest;
import com.tibco.tgdb.pdu.impl.CommitTransactionResponse;
import com.tibco.tgdb.pdu.impl.GetEntityRequest;
import com.tibco.tgdb.pdu.impl.GetEntityResponse;
import com.tibco.tgdb.pdu.impl.QueryRequest;
import com.tibco.tgdb.pdu.impl.QueryResponse;
import com.tibco.tgdb.pdu.impl.MetadataRequest;
import com.tibco.tgdb.pdu.impl.MetadataResponse;
import com.tibco.tgdb.query.TGQuery;
import com.tibco.tgdb.query.TGQueryOption;
import com.tibco.tgdb.query.TGResultSet;
import com.tibco.tgdb.query.TGTraversalDescriptor;
import com.tibco.tgdb.query.impl.QueryImpl;
import com.tibco.tgdb.query.impl.ResultSetImpl;
import com.tibco.tgdb.utils.ConfigName;
import com.tibco.tgdb.utils.TGProperties;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ConnectionImpl implements TGConnection, TGChangeListener {

    static TGLogger gLogger        = TGLogManager.getInstance().getLogger();

    AbstractChannel channel;
    TGProperties<String, String> properties;
    //first argument is meta data.
    GraphObjectFactoryImpl gof;

    int connId;

    //FIXME: Need to change the enum name
    enum command {
        CREATE(1), 
        EXECUTE(2), 
        EXECUTEID(3), 
        CLOSE(4);

        private final int value;
        private command(int value) {
            this.value = value;
        }
        public int getValue() {
            return value;
        }
    }

    ConnectionPoolImpl connPool;
    LinkedHashMap<Long, TGEntity> changedList;
    LinkedHashMap<Long, TGEntity> addedList;
    LinkedHashMap<Long, TGEntity> removedList;
    LinkedHashMap<Integer, List<TGAttribute>> attrByTypeList;

    static AtomicInteger connectionIds = new AtomicInteger();
    static AtomicLong requestIds = new AtomicLong(0);

    public ConnectionImpl(ConnectionPoolImpl connPool, TGChannel channel, TGProperties<String, String> properties) {
        this.connPool = connPool;
        this.channel = (AbstractChannel) channel;
        this.properties = properties;
        this.connId = connectionIds.getAndIncrement();
        // We cannot get meta data before we connect to the server
        this.gof = new GraphObjectFactoryImpl(null, this);

        changedList  = new LinkedHashMap<Long, TGEntity>();
        addedList  = new LinkedHashMap<Long, TGEntity>();
        removedList  = new LinkedHashMap<Long, TGEntity>();
        attrByTypeList = new LinkedHashMap<Integer, List<TGAttribute>>();
    }

    @Override
    public void connect() throws TGException {
        this.channel.connect();
        this.channel.start();
    }

    @Override
    public void disconnect() {
        this.channel.disconnect();
        this.channel.stop();
    }

    @Override
    public void setExceptionListener(TGConnectionExceptionListener lsnr) {
        this.connPool.lsnr = lsnr;  //delegate it to the Pool.
    }

    @Override
    public TGResultSet commit() throws TGException {
        connPool.adminlock();

        TGChannelResponse channelResponse;
        long timeout = Long.parseLong(properties.getProperty(ConfigName.ConnectionOperationTimeout, "-1"));

        long requestId  = requestIds.getAndIncrement();
        channelResponse = new BlockingChannelResponse(requestId, timeout);
        
        //Include existing nodes to the changed list if it's part of a new edge
        for (TGEntity entity : addedList.values()) {
        	if (entity.getEntityKind() == TGEntity.TGEntityKind.Edge) {
        		TGNode[] nodes = ((TGEdge) entity).getVertices();
        		for (TGNode node : nodes) {
        			if (!node.isNew()) {
        				changedList.put(((AbstractEntity) node).getVirtualId(), node);
        	            if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
        				    gLogger.log(TGLogger.TGLevel.Debug, "New edge added to node : %d", ((AbstractEntity) node).getVirtualId());
                        }
        			}
        		}
        	}
        }
        //For deleted edge and node, we don't immediately change the effected nodes or edges.

        try {
        	if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
        		addedList.values().forEach(e -> {Optional<TGAttribute> attr = e.getAttributes().stream().findFirst();
        			gLogger.log(TGLogger.TGLevel.Debug, "New Entity : " + (attr.isPresent() ? attr.get().getValue() : "no attribute found"));});
        	
        		changedList.values().forEach(e -> {Optional<TGAttribute> attr = e.getAttributes().stream().findFirst();
        			gLogger.log(TGLogger.TGLevel.Debug, "Updated Entity : " + (attr.isPresent() ? attr.get().getValue() : "no attribute found"));});
        	
        		removedList.values().forEach(e -> {Optional<TGAttribute> attr = e.getAttributes().stream().findFirst();
        			gLogger.log(TGLogger.TGLevel.Debug, "Deleted Entity : " + (attr.isPresent() ? attr.get().getValue() : "no attribute found"));});
        	}
        	
//    		addedList.values().forEach(e -> {Optional<TGAttribute> attr = e.getAttributes().stream().filter(a -> a.getAttributeType().getAttributeId() < 0).forEach();  (attr.   forEach(attr.get().getAttributeType() < 0 ?:w);
        	/*
    		addedList.values().forEach(e -> {e.getAttributes().stream().filter(a -> a.getAttributeType().getAttributeId() < 0).
    			forEach(v -> {Optional<List> val = attrByTypeList.get(a.getAttributeType().getAttributeId());
    			val.isPresent() ? val.add(a) : {List l = new ArrayList(); l.add(a); attrByTypeList.put(a.getAttributeType().getAttributeId(), l);}
    			})
    			*/
        	//FIXME: The following iterator block is not needed
    		if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
    			for (TGEntity entity : addedList.values()) {
    				for (TGAttribute attr : entity.getAttributes()) {
    					int attrId = attr.getAttributeType().getAttributeId();
    					if (attrId < 0) {
    						List<TGAttribute> attrList = attrByTypeList.get(attrId);
    						if (attrList == null) {
    							attrList = new ArrayList<TGAttribute>();
    							attrByTypeList.put(attrId, attrList);
    						}
    						attrList.add(attr);
    					}
    				}
    			}
    			for (TGEntity entity : changedList.values()) {
    				for (TGAttribute attr : entity.getAttributes()) {
    					int attrId = attr.getAttributeType().getAttributeId();
    					if (attrId < 0) {
    						List<TGAttribute> attrList = attrByTypeList.get(attrId);
    						if (attrList == null) {
    							attrList = new ArrayList<TGAttribute>();
    							attrByTypeList.put(attrId, attrList);
    						}
    						attrList.add(attr);
    					}
    				}
    			}
    			attrByTypeList.keySet().forEach(e -> {gLogger.log(TGLogger.TGLevel.Debug, "Attribute Type Id : %d", e);
    				attrByTypeList.get(e).forEach(a -> {gLogger.log(TGLevel.Debug, "  Attribute value : %s", a.getValue());});
    			});
    		}
    		
            CommitTransactionRequest request = (CommitTransactionRequest) TGMessageFactory.getInstance().
                createMessage(VerbId.CommitTransactionRequest, channel.getAuthToken(), channel.getSessionId());
            //CommitTransactionResponse response = (CommitTransactionResponse) TGMessageFactory.getInstance().createMessage(VerbId.CommitTransactionResponse);
            
//            request.setConnectionId(connId);
            Set<TGAttributeDescriptor> attrDescSet = ((GraphMetadataImpl)gof.getGraphMetaData()).getNewAttributeDescriptors();
            request.addCommitLists(addedList, changedList, removedList, attrDescSet);
            CommitTransactionResponse response = (CommitTransactionResponse) this.channel.sendRequest(request, channelResponse);
            fixUpAttrDescIds(response, attrDescSet);
            fixUpEntityIds(response);
			/* Used only with debug operation
            try {
            	//printEntities(response);
            } catch (IOException e) {
            	gLogger.log(TGLogger.TGLevel.Warning, "Failed to print debug entities from commit response");
            }
            */
            for (TGEntity entity : removedList.values()) {
            	((AbstractEntity) entity).markDeleted();
            }
        	if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
                gLogger.log(TGLogger.TGLevel.Debug, "Send commit request completed");
            }

            //fix up id 
            //set local object isNew to false 
            //mark attribute modified flag to false
            //addedIdList.forEach(e -> {e.
            for (TGEntity entity : changedList.values()) {
            	((AbstractEntity) entity).resetModifiedAttributes();
            }
            for (TGEntity entity : addedList.values()) {
            	((AbstractEntity) entity).resetModifiedAttributes();
            }
            changedList.clear();
            addedList.clear();
            removedList.clear();
            attrByTypeList.clear();

            return null;
        }
        finally {
            connPool.adminUnlock();
        }
    }

    @Override
    public void rollback() {
        connPool.adminlock();
        try {
            changedList.clear();
            addedList.clear();
            removedList.clear();
            attrByTypeList.clear();
        }
        finally {
            connPool.adminUnlock();
        }

    }

    @Override
    public TGEntity getEntity(TGKey key, TGProperties<String, String> props) throws TGException {
        connPool.adminlock();

        TGChannelResponse channelResponse;
        long timeout = Long.parseLong(properties.getProperty(ConfigName.ConnectionOperationTimeout, "-1"));

        long requestId  = requestIds.getAndIncrement();
        channelResponse = new BlockingChannelResponse(requestId, timeout);
        
        try {
        	GetEntityRequest request = (GetEntityRequest) TGMessageFactory.getInstance().
        			createMessage(VerbId.GetEntityRequest, channel.getAuthToken(), channel.getSessionId());
        	configureGetRequest(request, props);
        	request.setCommand((short)0);
        	request.setKey(key);
        	GetEntityResponse response = (GetEntityResponse) this.channel.sendRequest(request, channelResponse);
        	//Need to check status
        	if (!response.hasResult()) {
        		return null;
        	}
    		TGInputStream entityStream = response.getEntityStream();
    		HashMap<Long, TGEntity> fetchedEntities = null;
    		TGEntity entityFound = null;
        	int count = entityStream.readInt();
        	if (count > 0) {
         		fetchedEntities = new HashMap<Long, TGEntity>();
                entityStream.setReferenceMap(fetchedEntities);
        	}
        	for (int i=0; i<count; i++) {
        		TGEntity.TGEntityKind kind = TGEntity.TGEntityKind.fromValue(entityStream.readByte());
          		if (kind != TGEntity.TGEntityKind.InvalidKind) {
           			long id = entityStream.readLong();
           			TGEntity entity = fetchedEntities.get(id);
           			if (kind == TGEntity.TGEntityKind.Node) {
           				//Need to put shell object into hashmap to be deserialized later
           				TGNode  node = (TGNode) entity;
           				if (node == null) {
           					node = gof.createNode();
           					entity = node;
           					fetchedEntities.put(id, node);
           					if (entityFound == null) {
           						entityFound = node;
           					}
           				}
           				node.readExternal(entityStream);
           			} else if (kind == TGEntity.TGEntityKind.Edge) {
           				TGEdge edge = (TGEdge) entity;
           				if (edge == null) {
           					edge = gof.createEdge(null, null, TGEdge.DirectionType.BiDirectional);
           					entity = edge;
           					fetchedEntities.put(id, edge);
           					if (entityFound == null) {
           						entityFound = edge;
           					}
           				}
           				edge.readExternal(entityStream);
           			}
        	        if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
                        gLogger.log(TGLevel.Debug, "Kind : %d, Id : %d, hc : %d", entity.getEntityKind().kind(), ((AbstractEntity) entity).getEntityId(), entity.hashCode());
                        for (TGAttribute attrib : entity.getAttributes()) {
                            gLogger.log(TGLevel.Debug, "Attr : %s", attrib.getValue());
                        }
                        if (entity.getEntityKind() == TGEntity.TGEntityKind.Node) {
                            for (TGEdge edge : ((TGNode) entity).getEdges()) {
                                gLogger.log(TGLevel.Debug, "    Edge : %d, hc : %d", ((AbstractEntity) edge).getEntityId(), edge.hashCode());
                            }
                        } if (entity.getEntityKind() == TGEntity.TGEntityKind.Edge) {
                            TGNode[] nodes = ((TGEdge) entity).getVertices();
                            for (int j=0; j<nodes.length; j++) {
                                gLogger.log(TGLevel.Debug, "    Node : %d, hc : %d", ((AbstractEntity) nodes[j]).getEntityId(), nodes[j].hashCode());
                            }
                        }
                    }
           		} else {
           			gLogger.log(TGLevel.Warning, "Received invalid entity kind %d", kind);
           		}
        	}
            return entityFound;
        } catch (IOException ioe) {
        	throw new TGException(ioe.getMessage());
        }
        finally {
            connPool.adminUnlock();
        }
    }

    @Override
    public TGResultSet getEntities(TGKey key, TGProperties<String, String> props) throws TGException
    {
        connPool.adminlock();

        TGChannelResponse channelResponse;
        long timeout = Long.parseLong(properties.getProperty(ConfigName.ConnectionOperationTimeout, "-1"));

        long requestId  = requestIds.getAndIncrement();
        channelResponse = new BlockingChannelResponse(requestId, timeout);
        
        try {
        	GetEntityRequest request = (GetEntityRequest) TGMessageFactory.getInstance().
        			createMessage(VerbId.GetEntityRequest, channel.getAuthToken(), channel.getSessionId());
        	configureGetRequest(request, props);
        	request.setCommand((short)2);
        	request.setKey(key);
        	GetEntityResponse response = (GetEntityResponse) this.channel.sendRequest(request, channelResponse);
        	//Need to check status
        	if (!response.hasResult()) {
        		return null;
        	}
    		TGInputStream entityStream = response.getEntityStream();
    		HashMap<Long, TGEntity> fetchedEntities = null;
        	int totalCount = entityStream.readInt();
        	if (totalCount > 0) {
         		fetchedEntities = new HashMap<Long, TGEntity>();
                entityStream.setReferenceMap(fetchedEntities);
        	}
            ResultSetImpl rs = new ResultSetImpl(this, response.getResultId());
            //Number of entities matches the search.  Exclude the related entities
        	int resultCount = entityStream.readInt();
            int currentResultCount = 0;
        	for (int i=0; i<totalCount; i++) {
                boolean isResult = entityStream.readBoolean();
        		TGEntity.TGEntityKind kind = TGEntity.TGEntityKind.fromValue(entityStream.readByte());
          		if (kind != TGEntity.TGEntityKind.InvalidKind) {
           			long id = entityStream.readLong();
           			TGEntity entity = fetchedEntities.get(id);
           			if (kind == TGEntity.TGEntityKind.Node) {
           				//Need to put shell object into hashmap to be deserialized later
           				TGNode  node = (TGNode) entity;
           				if (node == null) {
           					node = gof.createNode();
           					entity = node;
           					fetchedEntities.put(((AbstractEntity)node).getEntityId(), node);
           				}
           				node.readExternal(entityStream);
                        if (isResult) {
                            rs.addEntityToResultSet(entity);
                            currentResultCount++;
                        }
           			} else if (kind == TGEntity.TGEntityKind.Edge) {
           				TGEdge edge = (TGEdge) entity;
           				if (edge == null) {
           					edge = gof.createEdge(null, null, TGEdge.DirectionType.BiDirectional);
           					entity = edge;
           					fetchedEntities.put(((AbstractEntity)edge).getEntityId(), edge);
           				}
           				edge.readExternal(entityStream);
                        if (isResult) {
                            rs.addEntityToResultSet(entity);
                            currentResultCount++;
                        }
           			}
        	        if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
                        gLogger.log(TGLevel.Debug, "Kind : %d, Id : %d", entity.getEntityKind().kind(), ((AbstractEntity) entity).getEntityId());
                        for (TGAttribute attrib : entity.getAttributes()) {
                            gLogger.log(TGLevel.Debug, "Attr : %s", attrib.getValue());
                        }
                        if (entity.getEntityKind() == TGEntity.TGEntityKind.Node) {
                            for (TGEdge edge : ((TGNode) entity).getEdges()) {
                                gLogger.log(TGLevel.Debug, "    Edge : %d", ((AbstractEntity) edge).getEntityId());
                            }
                        } if (entity.getEntityKind() == TGEntity.TGEntityKind.Edge) {
                            TGNode[] nodes = ((TGEdge) entity).getVertices();
                            for (int j=0; j<nodes.length; j++) {
                                gLogger.log(TGLevel.Debug, "    Node : %d", ((AbstractEntity) nodes[j]).getEntityId());
                            }
                        }
                    }
           		} else {
                    //FIXME: Throw exception and cleanup
           			gLogger.log(TGLevel.Warning, "Received invalid entity kind %d", kind);
           		}
        	}
            return rs;
        } catch (IOException ioe) {
        	throw new TGException(ioe.getMessage());
        }
        finally {
            connPool.adminUnlock();
        }
    }

    @Override
    public void insertEntity(TGEntity entity) throws TGException {
		if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
			Optional<TGAttribute> attr = entity.getAttributes().stream().findFirst();
			gLogger.log(TGLogger.TGLevel.Debug, "Entity is inserted - " + (attr.isPresent() ? attr.get().getValue() : "no attribute found"));
		}
        addedList.put(((AbstractEntity) entity).getVirtualId(), entity);
    }

    @Override
    public void deleteEntity(TGEntity entity) throws TGException {
		if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
			Optional<TGAttribute> attr = entity.getAttributes().stream().findFirst();
			gLogger.log(TGLogger.TGLevel.Debug, "Entity is deleted - " + (attr.isPresent() ? attr.get().getValue() : "no attribute found"));
		}
        removedList.put(((AbstractEntity) entity).getVirtualId(), entity);
    }

    @Override
    public void updateEntity(TGEntity entity) throws TGException {
		if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
			Optional<TGAttribute> attr = entity.getAttributes().stream().findFirst();
			gLogger.log(TGLogger.TGLevel.Debug, "Entity is updated - " + (attr.isPresent() ? attr.get().getValue() : "no attribute found"));
		}
        changedList.put(((AbstractEntity) entity).getVirtualId(), entity);
    }

    @Override
    // Need to implement TGQuery and change this type to TGQuery.
    public TGQuery createQuery(String expr) throws TGException {
        connPool.adminlock();

        TGChannelResponse channelResponse;
        int result;
        long queryHashId;
        TGQuery queryobj;
        long timeout = Long.parseLong(properties.getProperty(ConfigName.ConnectionOperationTimeout, "-1"));

        long requestId  = requestIds.getAndIncrement();
        channelResponse = new BlockingChannelResponse(requestId, timeout);
        try {
            QueryRequest request = (QueryRequest) TGMessageFactory.getInstance().createMessage(VerbId.QueryRequest);
            request.setCommand(command.CREATE.getValue());
//            request.setConnectionId(connId);
            request.setQuery(expr);
            QueryResponse response = (QueryResponse) this.channel.sendRequest(request, channelResponse);
            gLogger.log(TGLogger.TGLevel.Debug, "Send create query completed");
            result = response.getResult();
            queryHashId = response.getQueryHashId();
            // TGSuccess is 0.
            if(result == 0 && queryHashId > 0) {
                queryobj = new QueryImpl(this, queryHashId);
                return queryobj;
            }
            else {
                return null;
            }
        }
        finally {
            connPool.adminUnlock();
        }
    }

    @Override
    public TGResultSet executeQuery(String query,TGQueryOption queryOption) throws TGException {
        connPool.adminlock();

        TGChannelResponse channelResponse;
        long timeout = Long.parseLong(properties.getProperty(ConfigName.ConnectionOperationTimeout, "-1"));

        long requestId  = requestIds.getAndIncrement();
        channelResponse = new BlockingChannelResponse(requestId, timeout);
        try {
            QueryRequest request = (QueryRequest) TGMessageFactory.getInstance().createMessage(VerbId.QueryRequest);
            request.setCommand(command.EXECUTE.getValue());
//            request.setConnectionId(connId);
            request.setQuery(query);
            QueryResponse response = (QueryResponse) this.channel.sendRequest(request, channelResponse);
            gLogger.log(TGLogger.TGLevel.Debug, "Send execute query completed");
            return null;
        }
        finally {
            connPool.adminUnlock();
        }
    }

    public TGResultSet executeQueryWithId(long queryHashId,TGQueryOption queryOption) throws TGException {
        connPool.adminlock();

        TGChannelResponse channelResponse;
        long timeout = Long.parseLong(properties.getProperty(ConfigName.ConnectionOperationTimeout, "-1"));

        long requestId  = requestIds.getAndIncrement();
        channelResponse = new BlockingChannelResponse(requestId, timeout);
        try {
            QueryRequest request = (QueryRequest) TGMessageFactory.getInstance().createMessage(VerbId.QueryRequest);
            request.setCommand(command.EXECUTEID.getValue());
//            request.setConnectionId(connId);
            request.setQueryHashId(queryHashId);
            QueryResponse response = (QueryResponse) this.channel.sendRequest(request, channelResponse);
            gLogger.log(TGLogger.TGLevel.Debug, "Send execute query completed");
            return null;
        }
        finally {
            connPool.adminUnlock();
        }
    }

    public TGQuery closeQuery(long queryHashId) throws TGException {
        connPool.adminlock();

        TGChannelResponse channelResponse;
        long timeout = Long.parseLong(properties.getProperty(ConfigName.ConnectionOperationTimeout, "-1"));

        long requestId  = requestIds.getAndIncrement();
        channelResponse = new BlockingChannelResponse(requestId, timeout);
        try {
            QueryRequest request = (QueryRequest) TGMessageFactory.getInstance().createMessage(VerbId.QueryRequest);
            request.setCommand(command.CLOSE.getValue());
//            request.setConnectionId(connId);
            request.setQueryHashId(queryHashId);
            QueryResponse response = (QueryResponse) this.channel.sendRequest(request, channelResponse);
            gLogger.log(TGLogger.TGLevel.Debug, "Send close query completed");
            return null;
        }
        finally {
            connPool.adminUnlock();
        }
    }

    public TGTraversalDescriptor createTraversalDescriptor(String name) {
        return null;
    }

    @Override
    public TGGraphMetadata getGraphMetadata(boolean refresh) throws TGException {
    	if (refresh) {
    		connPool.adminlock();

    		TGChannelResponse channelResponse;
    		long timeout = Long.parseLong(properties.getProperty(ConfigName.ConnectionOperationTimeout, "-1"));

    		long requestId  = requestIds.getAndIncrement();
    		channelResponse = new BlockingChannelResponse(requestId, timeout);
    		MetadataResponse response = null;
    		try {
    			MetadataRequest request = (MetadataRequest) TGMessageFactory.getInstance().createMessage(VerbId.MetadataRequest);
    			response = (MetadataResponse) this.channel.sendRequest(request, channelResponse);
    		}
    		finally {
    			connPool.adminUnlock();
    		}
    		List<TGNodeType> nodeTypeList = response.getNodeTypeList();
    		List<TGEdgeType> edgeTypeList = response.getEdgeTypeList();
    		List<TGAttributeDescriptor> attrDescList = response.getAttrDescList();
    		
    		GraphMetadataImpl gmd = (GraphMetadataImpl) gof.getGraphMetaData();
    		gmd.updateMetadata(attrDescList, nodeTypeList, edgeTypeList);
    	}
   		return gof.getGraphMetaData();
    }

    @Override
    public TGGraphObjectFactory getGraphObjectFactory() {
        return gof;
    }

	@Override
	public void entityCreated(TGEntity entity) {
		if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
			Optional<TGAttribute> attr = entity.getAttributes().stream().findFirst();
			gLogger.log(TGLogger.TGLevel.Debug, "Entity is created - " + (attr.isPresent() ? attr.get().getValue() : "no attribute found"));
		}
        // Should be using the virtualId here because it's brand new
        addedList.put(((AbstractEntity) entity).getVirtualId(), entity);
	}

	@Override
	public void nodeAdded(TGGraph graph, TGNode node) {
		if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
			Optional<TGAttribute> attr = node.getAttributes().stream().findFirst();
			gLogger.log(TGLogger.TGLevel.Debug, "Node is added - " + (attr.isPresent() ? attr.get().getValue() : "no attribute found"));
		}
        addedList.put(((AbstractEntity) node).getVirtualId(), node);
	}

	@Override
	public void attributeAdded(TGAttribute attribute, TGEntity owner) {
		// TODO Auto-generated method stub
        gLogger.log(TGLogger.TGLevel.Debug, "Attribute is created");
	}

	@Override
	public void attributeChanged(TGAttribute attribute, Object oldValue,
			Object newValue) {
		// TODO Auto-generated method stub
        gLogger.log(TGLogger.TGLevel.Debug, "Attribute is changed");
	}

	@Override
	public void attributeRemoved(TGAttribute attribute, TGEntity owner) {
		// TODO Auto-generated method stub
        gLogger.log(TGLogger.TGLevel.Debug, "Attribute is removed");
	}

	@Override
	public void nodeRemoved(TGGraph graph, TGNode node) {
		// TODO Auto-generated method stub
        gLogger.log(TGLogger.TGLevel.Debug, "Node is removed");
        removedList.put(((AbstractEntity) node).getVirtualId(), node);
	}

	@Override
	public void entityDeleted(TGEntity entity) {
		// TODO Auto-generated method stub
    	Optional<TGAttribute> attr = entity.getAttributes().stream().findFirst();
        gLogger.log(TGLogger.TGLevel.Debug, "Entity is deleted - " + (attr.isPresent() ? attr.get().getValue() : "no attribute found"));
        removedList.put(((AbstractEntity) entity).getVirtualId(), entity);
	}

    private void fixUpAttrDescIds(CommitTransactionResponse response, Set<TGAttributeDescriptor> attrDescSet) {
        gLogger.log(TGLogger.TGLevel.Debug, "Fixup attribute descriptor ids");
        int attrDescCount = response.getAttrDescCount();
        List<Integer> attrDescIdList = response.getAttrDescIdList();
        for (int i=0; i<attrDescCount; i++) {
        	int tempId = attrDescIdList.get(i*2); 
        	int realId = attrDescIdList.get((i*2) + 1);

        	Iterator<TGAttributeDescriptor> itr = attrDescSet.iterator();
        	while(itr.hasNext()) {
        		TGAttributeDescriptor attrDesc = itr.next();
        		if (attrDesc.getAttributeId() == tempId) {
        	        if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
        			    gLogger.log(TGLogger.TGLevel.Debug, "Replace descriptor id : %d by %d", attrDesc.getAttributeId(), realId);
                    }
        			((AttributeDescriptorImpl) attrDesc).setAttributeId(realId);
        			break;
        		}
        	}
        }
    }

    private void fixUpEntityIds(CommitTransactionResponse response) {
        gLogger.log(TGLogger.TGLevel.Debug, "Fixup entity ids");
        int addedIdCount = response.getAddedEntityCount();
        List<Long> addedIdList = response.getAddedIdList();
        for (int i=0; i<addedIdCount; i++) {
        	long tempId = addedIdList.get(i*2); 
        	long realId = addedIdList.get((i*2) + 1);

        	Iterator<TGEntity> itr = addedList.values().iterator();
        	while(itr.hasNext()) {
        		TGEntity entity = itr.next();
        		if (((AbstractEntity) entity).getVirtualId() == tempId) {
        	        if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
        			    gLogger.log(TGLogger.TGLevel.Debug, "Replace entity id : %08X by %08X", tempId, realId);
                    }
        			((AbstractEntity) entity).setEntityId(realId);
        			((AbstractEntity) entity).setIsNew(false);
        			break;
        		}
        	}
        }
    }

    private void printEntities(CommitTransactionResponse response) throws TGException, IOException {
    	TGInputStream entityStream = response.getEntityStream();
        if (entityStream == null) {
           	gLogger.log(TGLevel.Debug, "No debug entities received");
            return;
        }
    	HashMap<Long, TGEntity> fetchedEntities = null;
        int count = entityStream.readInt();
        if (count > 0) {
         	fetchedEntities = new HashMap<Long, TGEntity>();
        }
        entityStream.setReferenceMap(fetchedEntities);
        for (int i=0; i<count; i++) {
        	TGEntity.TGEntityKind kind = TGEntity.TGEntityKind.fromValue(entityStream.readByte());
          	if (kind != TGEntity.TGEntityKind.InvalidKind) {
           		long id = entityStream.readLong();
           		TGEntity entity = fetchedEntities.get(id);
           		if (kind == TGEntity.TGEntityKind.Node) {
           			//Need to put shell object into hashmap to be deserialized later
           			TGNode  node = (TGNode) entity;
           			if (node == null) {
           				node = gof.createNode();
           				entity = node;
           				fetchedEntities.put(((AbstractEntity)node).getEntityId(), node);
           			}
           			node.readExternal(entityStream);
           		} else if (kind == TGEntity.TGEntityKind.Edge) {
           			TGEdge edge = (TGEdge) entity;
           			if (edge == null) {
           				edge = gof.createEdge(null, null, TGEdge.DirectionType.BiDirectional);
           				entity = edge;
           				fetchedEntities.put(((AbstractEntity)edge).getEntityId(), edge);
           			}
           			edge.readExternal(entityStream);
           		}
        	    if (gLogger.isEnabled(TGLogger.TGLevel.Debug)) {
                    gLogger.log(TGLevel.Debug, "Kind : %d, Id : %d", entity.getEntityKind().kind(), ((AbstractEntity) entity).getEntityId());
                    for (TGAttribute attrib : entity.getAttributes()) {
                        gLogger.log(TGLevel.Debug, "Attr : %s", attrib.getValue());
                    }
                    if (entity.getEntityKind() == TGEntity.TGEntityKind.Node) {
                        for (TGEdge edge : ((TGNode) entity).getEdges()) {
                            gLogger.log(TGLevel.Debug, "    Edge : %d", ((AbstractEntity) edge).getEntityId());
                        }
                    } if (entity.getEntityKind() == TGEntity.TGEntityKind.Edge) {
                        TGNode[] nodes = ((TGEdge) entity).getVertices();
                        for (int j=0; j<nodes.length; j++) {
                            gLogger.log(TGLevel.Debug, "    Node : %d", ((AbstractEntity) nodes[j]).getEntityId());
                        }
                    }
                }
           	} else {
           		gLogger.log(TGLevel.Warning, "Received invalid entity kind %d", kind);
           	}
        }
    }
    
    private void configureGetRequest(GetEntityRequest ger, TGProperties<String, String> properties) {
    	if (ger == null || properties == null) {
    		return;
    	}
    	
    	String val = properties.get("fetchsize");
    	if (val != null) {
    		int fetchSize = Integer.parseInt(val);
    		ger.setFetchSize(fetchSize);
    	}

    	val = properties.get("batchsize");
    	if (val != null) {
    		short batchSize = Short.parseShort(val);
    		ger.setBatchSize(batchSize);
    	}

    	val = properties.get("traversaldepth");
    	if (val != null) {
    		short tdepth = Short.parseShort(val);
    		ger.setTraversalDepth(tdepth);
    	}

    	val = properties.get("edgelimit");
    	if (val != null) {
    		short tdepth = Short.parseShort(val);
    		ger.setEdgeFetchSize(tdepth);
    	}
    }
}
