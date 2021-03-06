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
 */

var util                    = require('util'),
    AbstractProtocolMessage = require('../AbstractProtocolMessage').AbstractProtocolMessage,
    VerbId                  = require('./VerbId').VerbId;

//Class Definition
/**
 *
 * @param addedEntities
 * @param updatedEntities
 * @param removedEntities
 * @constructor
 */
function CommitTransactionRequest(addedEntities, updatedEntities, removedEntities, attrDescSet) {
	CommitTransactionRequest.super_.call(this);
    this._addedEntities     = addedEntities;
    this._updatedEntities   = updatedEntities;
    this._removedEntities   = removedEntities;
    this._attrDescSet       = attrDescSet;

}

util.inherits(CommitTransactionRequest, AbstractProtocolMessage);

CommitTransactionRequest.prototype.getVerbId = function() {
    return VerbId.COMMIT_TRANS_REQUEST;
};

/**
 * Is this message updateable.
 */
CommitTransactionRequest.prototype.isUpdateable = function() {
    return false;
};

CommitTransactionRequest.prototype.writePayload = function(outputStream) {
	console.log("**** Entering commit transaction request writePayload at output buffer position at : %d", startPos);
	var startPos = outputStream.getPosition();
    outputStream.writeInt(0);
    outputStream.writeInt(0);

	if (this._attrDescSet.length>0) {
		outputStream.writeShort(0x1010); // for attribute descriptor
		outputStream.writeInt(this._attrDescSet.length);
		for(var index in this._attrDescSet) {
			try {
				this._attrDescSet[index].writeExternal(outputStream);
			} catch (err) {
				console.log("Error writing attribute description ! " + err.message);
			}
		}
	}
	console.log("**** after attrDescSet at output buffer position at : %d", outputStream.getPosition());
  
	var mapLenth = Object.keys(this._addedEntities).length;
    if (mapLenth > 0) {
        outputStream.writeShort(0x1011);
        outputStream.writeInt(mapLenth);
        for(var key in this._addedEntities) {
        	this._addedEntities[key].writeExternal(outputStream);
        };
    }
	console.log("**** after addedList at output buffer position at : %d", outputStream.getPosition());
    
	mapLenth = Object.keys(this._updatedEntities).length;
    if (mapLenth > 0) {
        outputStream.writeShort(0x1012);
        outputStream.writeInt(mapLenth);
        for(var key in this._updatedEntities) {
        	this._updatedEntities[key].writeExternal(outputStream);
        };
    }
	console.log("**** after updatedList at output buffer position at : %d", outputStream.getPosition());
    
	mapLenth = Object.keys(this._removedEntities).length;
    if (mapLenth > 0) {
        outputStream.writeShort(0x1013);
        outputStream.writeInt(mapLenth);
        for(var key in this._removedEntities) {
        	outputStream.writeLong(key);
        };
    }
	console.log("**** after RemovedList at output buffer position at : %d", outputStream.getPosition());
	var currPos = outputStream.getPosition();
	var length = currPos - startPos;
	outputStream.writeIntAt(startPos, length);
	console.log("**** Leaving commit transaction request writePayload at output buffer position at : %d", currPos);
};

CommitTransactionRequest.prototype.readPayload = function() {

};

exports.CommitTransactionRequest = CommitTransactionRequest;
