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
 * File name : NodeImpl.${EXT}
 * Created on: 1/23/15
 * Created by: suresh 
 * <p/>
 * SVN Id: $Id: NodeImpl.java 813 2016-05-09 15:38:00Z vchung $
 */


package com.tibco.tgdb.model.impl;


import com.tibco.tgdb.exception.TGException;
import com.tibco.tgdb.model.*;
import com.tibco.tgdb.pdu.TGInputStream;
import com.tibco.tgdb.pdu.TGOutputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class NodeImpl extends AbstractEntity implements TGNode {

    private List<TGEdge> edges = new ArrayList<TGEdge>();

    NodeImpl(TGGraphMetadata gmd)  {
         super(gmd);
    }

    NodeImpl(TGGraphMetadata gmd, TGNodeType nodeType)
    {
        super(gmd);
        this.entityType = nodeType;
    }

    @Override
    public TGEntityKind getEntityKind() {
        return TGEntityKind.Node;
    }


    public List<TGGraph> getGraphs() {
        return null;
    }

    @Override
    public Collection<TGEdge> getEdges() {
    	return Collections.unmodifiableCollection(edges);
    }

    @Override
    public Collection<TGEdge> getEdges(TGEdge.DirectionType directionType) {
    	//VC:FIXME
        return Collections.EMPTY_LIST;
    }

    @Override
    public TGEdge addEdge(TGNode node, TGEdgeType edgeType, TGEdge.DirectionType directionType) {
    	TGEdge edge = new EdgeImpl(graphMetadata, this, node, directionType);
    	edges.add(edge);
        return edge;
    }

    // Called by GraphObjectFactoryImpl.createEdge method
    void addEdge(TGEdge edge) {
    	edges.add(edge);
    }

    @Override
    public void writeExternal(TGOutputStream os) throws TGException, IOException {
    	int startPos = os.getPosition();
    	os.writeInt(0);
    	//write attributes from the based class
        super.writeExternal(os);
        //write the edges ids
  		os.writeInt((int) edges.stream().filter(e -> e.isNew()).count()); // only include the new edges
        edges.stream().filter(e -> e.isNew()).forEach(e-> {try {os.writeLong(((AbstractEntity) e).getVirtualId());} catch (IOException ex) {}});
        int currPos = os.getPosition();
        int length = currPos - startPos;
        os.writeIntAt(startPos, length);
    }

    @Override
    public void readExternal(TGInputStream is) throws TGException, IOException {
    	//FIXME: Need to validate length
    	int buflen = is.readInt();
        super.readExternal(is);
        int edgeCount = is.readInt();
        for (int i=0; i<edgeCount; i++){
        	EdgeImpl edge = null;
        	long id = is.readLong();
        	Map refMap = is.getReferenceMap();
        	if (refMap != null) {
        		edge = (EdgeImpl) refMap.get(id);
        	}
        	if (edge == null) {
        		edge = new EdgeImpl(graphMetadata);
        		edge.setInitialized(false);
        		edge.setEntityId(id);
        		if (refMap != null) {
        			refMap.put(id, edge);
        		}
        	}
        	edges.add(edge);
        }
    	isInitialized = true;
    }
}
