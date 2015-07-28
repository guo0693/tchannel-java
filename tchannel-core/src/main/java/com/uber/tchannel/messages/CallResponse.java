/*
 * Copyright (c) 2015 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.uber.tchannel.messages;

import io.netty.buffer.ByteBuf;

public class CallResponse extends AbstractCallMessage {

    private static final byte RESPONSE_CODE_OK_MASK = (byte) 0x00;
    private static final byte RESPONSE_CODE_ERROR_MASK = (byte) 0x01;

    private final byte code;

    public CallResponse(long id, byte flags, byte code, byte checksumType, int checksum,
                        ByteBuf arg1, ByteBuf arg2, ByteBuf arg3) {
        super(id, MessageType.CallRequest, flags, checksumType, checksum, arg1, arg2, arg3);
        this.code = code;
    }

    public byte getCode() {
        return code;
    }
}