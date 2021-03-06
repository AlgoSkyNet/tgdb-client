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
 * File name : AbstractEntity.${EXT}
 * Created on: 1/23/15
 * Created by: suresh 
 * <p/>
 * SVN Id: $Id: AbstractEntity.java 771 2016-05-05 11:40:52Z vchung $
 */


package com.tibco.tgdb.model.impl;

import com.tibco.tgdb.exception.TGException;
import com.tibco.tgdb.log.TGLogManager;
import com.tibco.tgdb.log.TGLogger;
import com.tibco.tgdb.log.TGLogger.TGLevel;
import com.tibco.tgdb.model.*;
import com.tibco.tgdb.pdu.TGInputStream;
import com.tibco.tgdb.pdu.TGOutputStream;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractEntity implements TGEntity {

    Map<String, TGAttribute> attributes = new LinkedHashMap<String, TGAttribute>();
    List<TGAttribute> modifiedAttributes = new ArrayList<TGAttribute>();
    long entityId = -1;

    TGEntityType entityType;
    boolean isNew;
    boolean isDeleted; // Need to set this to true once the entity is confirmed deleted by the server
    int version;
    TGGraphMetadata graphMetadata;
    transient long virtualId; //issued only for creation and not valid later
    boolean isInitialized = true;

    static TGLogger gLogger        = TGLogManager.getInstance().getLogger();
    static AtomicLong gEntitySequencer = new AtomicLong();

    protected AbstractEntity(TGGraphMetadata graphMetadata) {
        this.graphMetadata = graphMetadata;
        this.isNew = true;
        this.isDeleted = false;
        this.version = 0;
        entityId = -1;
        virtualId = gEntitySequencer.decrementAndGet();
    }

    @Override
    public Collection<TGAttribute> getAttributes() {
        return attributes.values();
    }

    @Override
    public TGAttribute getAttribute(String attrName) {
        return attributes.get(attrName);
    }

    @Override
    public boolean isAttributeSet(String attrName) {
        TGAttribute attr = attributes.get(attrName);

        return attr != null;
    }

    @Override
    public void setAttribute(TGAttribute attr) {
        attributes.put(attr.getAttributeType().getName(), attr);
    }

    //FIXME:  Need to add the following method.  
    //One usage is to allow setting a new attribute to null
    public void setAttribute(TGAttributeDescriptor attrDesc, Object value) throws TGException {
    	if (attrDesc == null) {
            throw new TGException(String.format("Attribute descriptor is required"));
    	}
    }

    @Override
    public void setAttribute(String name, Object value) throws TGException {
    	//Need to handle null value
        TGAttribute attr = attributes.get(name);
        //If the attribute has not been set and the value is null
        //Do not do anything. We are not setting anything that
        //we don't know the type
        //FIXME: Need to throw an exception
        if (attr == null && value == null) {
            throw new TGException(String.format("Null value specified for an a null attribute"));
        }
        if (attr == null) {
            TGAttributeDescriptor attrDesc = null;
            attrDesc = graphMetadata.getAttributeDescriptor(name);
            if (attrDesc == null) {
                attrDesc = ((GraphMetadataImpl)graphMetadata).createAttributeDescriptor(name, value.getClass());
            }
            attr = new AttributeImpl(this, attrDesc, value);
        }
        //value can be null here
        //FIXME: Currently setValue does not throw any exception
        if (!attr.isModified()) {
        	modifiedAttributes.add(attr);
        }
        attr.setValue(value);
        this.setAttribute(attr);
    }


    public Long getEntityId() {
        return entityId;
    }

    public void markDeleted() {
    	isDeleted = true;
    }

    @Override
    public boolean isDeleted() {
    	return isDeleted;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public int getVersion() {
        return version;
    }

    //FIXME:  How should we expose this
    public Long getVirtualId() {
        return isNew ? virtualId : entityId;
    }

    public void setIsNew(boolean isNew) {
    	this.isNew = isNew;
    }

    public void setEntityId(long id) {
    	virtualId = 0;
    	entityId = id;
    }

    @Override
    public void writeExternal(TGOutputStream os) throws TGException, IOException {
    	os.writeBoolean(isNew);
        os.writeByte(getEntityKind().kind()); //Write the EntityKind
        //os.writeBoolean(isNew); //no need to write it.
        //virtual id can be local or actual id
        os.writeLong(getVirtualId());
        os.writeInt(version);
        os.writeInt(entityType == null ? 0 : ((EntityTypeImpl)entityType).getId());

        //the attribute id can be temporary which is a negative number
        //The actual attribute id is > 0
  		os.writeInt((int) attributes.values().stream().filter(e -> e.isModified()).count());
        for (TGAttribute attr : attributes.values()) {
        	//If an attribute is not modified, do not include in the stream
        	if(!attr.isModified()) {
        		continue;
        	}
        	/*
        	 * used to do these steps here
       		int aid = attr.getAttributeType().getAttributeId();
        	//null attribute is not allowed during entity creation
        	os.writeInt(aid);
       		os.writeBoolean(attr.isNull());
        	 */
            attr.writeExternal(os);
        }
    }

    @Override
    public void readExternal(TGInputStream is) throws TGException, IOException {

        this.isNew = is.readBoolean();  //Should always be False.
        if (isNew == true) {
        	gLogger.log(TGLevel.Warning, "Deserializing a new entity is not expected");
        	this.isNew = false;
        }
        byte kind = is.readByte();
        if (getEntityKind().kind() != kind) throw new TGException("Invalid object for deserialization. Expecting..."); //SS:TODO
        this.entityId = is.readLong();  //Overwrite the entityId
        this.version = is.readInt();
        int entityTypeId = is.readInt();
        if (entityTypeId != 0) {
        	TGEntityType et = ((GraphMetadataImpl) graphMetadata).getNodeType(entityTypeId);
        	this.entityType = et;
        	if (et == null) {
        		//FIXME: retrieve entity type together with the entity?
        		gLogger.log(TGLevel.Warning, "Cannot lookup entity type %d from graph meta data cache", entityTypeId);
        	}
        }

        int count = is.readInt();
        for (int i=0; i<count; i++) {
            TGAttribute attr = new AttributeImpl(this);
            attr.readExternal(is);
            this.setAttribute(attr);
        }
    }

    TGAttributeDescriptor getAttributeDescriptor(String name, Class klazz) throws TGException
    {
        TGAttributeDescriptor attrDesc;

        if (graphMetadata != null) {

            attrDesc = graphMetadata.getAttributeDescriptor(name);

            if (attrDesc == null) {

                TGAttributeType attrType = TGAttributeType.fromClass(klazz);

                if (attrType == TGAttributeType.Invalid) throw new TGException("Unsupported type :" + klazz.getName());

                attrDesc = graphMetadata.createAttributeDescriptor(name,attrType, klazz.isArray() );
            }

            return attrDesc;
        }
        throw new TGException("Metadata not associated with Entity");

    }
    
    //To be called after transaction to reset to modified attribute flags
    public void resetModifiedAttributes() {
    	for (TGAttribute attr : modifiedAttributes) {
    		((AttributeImpl) attr).resetIsModified();
    	}
    	modifiedAttributes.clear();
    }
    
    void setInitialized(boolean isInit) {
    	isInitialized = isInit;
    }
    
    boolean isInitialized() {
    	return isInitialized;
    }
}

