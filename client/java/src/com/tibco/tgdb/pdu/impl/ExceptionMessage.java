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
 * File name : ExceptionMessage.${EXT}
 * Created on: 1/17/15
 * Created by: suresh 
 * <p/>
 * SVN Id: $Id: ExceptionMessage.java 583 2016-03-15 02:02:39Z vchung $
 */


package com.tibco.tgdb.pdu.impl;

import com.tibco.tgdb.exception.TGException;
import com.tibco.tgdb.pdu.TGInputStream;
import com.tibco.tgdb.pdu.TGOutputStream;
import com.tibco.tgdb.pdu.VerbId;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

public class ExceptionMessage extends AbstractProtocolMessage {


    String msg;
    TGException.ExceptionType exceptionType;

    public static ExceptionMessage buildFromException(Exception ex) {
        ExceptionMessage exMsg = new ExceptionMessage();
        if (ex instanceof TGException) {
            exMsg.exceptionType = ((TGException) ex).getExceptionType();
        } else if (ex instanceof IOException) {
            exMsg.exceptionType = TGException.ExceptionType.IOException;
        } else {
            exMsg.exceptionType = TGException.ExceptionType.GeneralException;
        }

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        pw.flush();

        exMsg.msg = String.format("%s.\nRemoteTrace:%s", ex.getMessage(), sw.toString());
        return exMsg;
    }

    @Override
    public VerbId getVerbId() {
        return VerbId.ExceptionMessage;
    }

    @Override
    protected void writePayload(TGOutputStream os) throws TGException, IOException {
        os.writeByte((byte) exceptionType.ordinal());
        os.writeUTF(msg);
    }

    @Override
    protected void readPayload(TGInputStream is) throws TGException, IOException {
        exceptionType = TGException.ExceptionType.values()[is.readByte()];
        msg = is.readUTF();
    }

    @Override
    public boolean isUpdateable() {
        return false;
    }

    public TGException.ExceptionType getExceptionType() {
        return exceptionType;
    }

    public String getMessage() {
        return msg;
    }
}
