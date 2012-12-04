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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;

import java.nio.charset.Charset;

public class HexDumpProxyFrontendHandler extends ChannelInboundByteHandlerAdapter {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(HexDumpProxyFrontendHandler.class);

    private final String remoteHost;
    private final int remotePort;

    private volatile Channel outboundChannel;

    public HexDumpProxyFrontendHandler(String remoteHost, int remotePort) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // TODO: Suspend incoming traffic until connected to the remote host.
        //       Currently, we just keep the inbound traffic in the client channel's outbound buffer.
        final Channel inboundChannel = ctx.channel();
        logger.info("Start connect to target server: "+remoteHost +":" +remotePort);

        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
         .channel(NioSocketChannel.class)
         .remoteAddress(remoteHost, remotePort)
         .handler(new HexDumpProxyBackendHandler(inboundChannel));

        ChannelFuture f = b.connect();
        outboundChannel = f.channel();
        f.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    // Connection attempt succeeded:
                    // TODO: Begin to accept incoming traffic.
                } else {
                    // Close the connection if the connection attempt has failed.
                    logger.info("Close browser connection...");
                    inboundChannel.close();
                }
            }
        });
    }

    @Override
    public void inboundBufferUpdated(ChannelHandlerContext ctx, ByteBuf in) throws Exception {

        logger.info("################################");
        logger.info("Received request from browser");
        logger.info("################################");
        logger.info("");
        logger.info(in.toString(Charset.defaultCharset()));
        logger.info("");
        logger.info("Forward the request to remove server now.");
        ByteBuf out = outboundChannel.outboundByteBuffer();
        out.discardReadBytes();
        out.writeBytes(in);
        in.clear();
        if (outboundChannel.isActive()) {
            outboundChannel.flush();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Browser connection is inactive, so we close the connection to the remote server now...");
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        closeOnFlush(ctx.channel());
    }



    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    static void closeOnFlush(Channel ch) {
        if (ch.isActive()) {
            ch.flush().addListener(ChannelFutureListener.CLOSE);
        }
    }
}

