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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.socket.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class HexDumpProxy {

    private final int localPort;
    private final String remoteHost;
    private final int remotePort;

    ServerBootstrap b;
    public HexDumpProxy(int localPort, String remoteHost, int remotePort) {
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }


    public void shutdown() throws Exception {
          b.shutdown();
    }
    public void run() throws Exception {
        System.err.println(
                "Proxying *:" + localPort + " to " +
                remoteHost + ':' + remotePort + " ...");

        // Configure the bootstrap.
         b= new ServerBootstrap();
        try {
            b.group(new NioEventLoopGroup(), new NioEventLoopGroup())
             .channel(NioServerSocketChannel.class)
             .localAddress(localPort)
             .childHandler(new HexDumpProxyInitializer(remoteHost, remotePort));

            b.bind();
        } finally {
//          ;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                System.out.println("Shutdown proxy server.............");
                b.shutdown();
            }
        }));


        System.out.println("Proxy server started.....");
    }

    public static void main(String[] args) throws Exception {
        // Validate command line options.
//        if (args.length != 3) {
//            System.err.println(
//                    "Usage: " + HexDumpProxy.class.getSimpleName() +
//                    " <local port> <remote host> <remote port>");
//            return;
//        }
//
//        // Parse command line options.
//        int localPort = Integer.parseInt(args[0]);
//        String remoteHost = args[1];
//        int remotePort = Integer.parseInt(args[2]);

        new HexDumpProxy(8080, "www.baidu.com", 80).run();
    }
}
