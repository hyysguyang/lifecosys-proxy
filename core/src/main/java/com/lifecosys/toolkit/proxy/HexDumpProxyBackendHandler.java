/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.lifecosys.toolkit.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundByteHandlerAdapter;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;

import java.nio.charset.Charset;

public class HexDumpProxyBackendHandler extends ChannelInboundByteHandlerAdapter {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(HexDumpProxyBackendHandler.class);
    private final Channel inboundChannel;

    public HexDumpProxyBackendHandler(Channel inboundChannel) {
        this.inboundChannel = inboundChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void inboundBufferUpdated(ChannelHandlerContext ctx, ByteBuf in) throws Exception {

        logger.info("################################");
        logger.info("Received response from remote server");
        logger.info("################################");
        logger.info("");
        logger.info(in.toString(Charset.defaultCharset()));
        logger.info("");
        logger.info("Forward the response to browser now.");
        ByteBuf out = inboundChannel.outboundByteBuffer();
        out.discardReadBytes();
        out.writeBytes(in);
        in.clear();
        inboundChannel.flush();
    }



    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("The remote server connection is inactive, so we close the browser connection now...");
        HexDumpProxyFrontendHandler.closeOnFlush(inboundChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        HexDumpProxyFrontendHandler.closeOnFlush(ctx.channel());
    }
}
