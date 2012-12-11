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
package securechat;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import static org.jboss.netty.channel.Channels.pipeline;


public class SecureChatClient {

    private final String host;
    private final int port;

    public SecureChatClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void run() throws IOException {
        // Configure the client.
        ClientBootstrap bootstrap = new ClientBootstrap(
                new NioClientSocketChannelFactory(
                        Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));

        // Configure the pipeline factory.
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();

                // Add SSL handler first to encrypt and decrypt everything.
                // In this example, we use a bogus certificate in the server side
                // and accept any invalid certificates in the client side.
                // You will need something more complicated to identify both
                // and server in the real world.

                SSLEngine engine =
                        SecureChatSslContextFactory.getClientContext().createSSLEngine();
                engine.setUseClientMode(true);

                pipeline.addLast("ssl", new SslHandler(engine));

                // On top of the SSL handler, add the text line codec.
//                pipeline.addLast("framer", new DelimiterBasedFrameDecoder(
//                        8192, Delimiters.lineDelimiter()));
//                pipeline.addLast("decoder", new StringDecoder());
//                pipeline.addLast("encoder", new StringEncoder());

//                and then business logic.
//                pipeline.addLast("handler", new SecureChatClientHandler());

                return pipeline;
            }
        });

        // Start the connection attempt.
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));

        // Wait until the connection attempt succeeds or fails.
        Channel channel = future.awaitUninterruptibly().getChannel();
        if (!future.isSuccess()) {
            future.getCause().printStackTrace();
            bootstrap.releaseExternalResources();
            return;
        }
        channel.write(ChannelBuffers.copiedBuffer("Hello".getBytes("UTF-8")));
//        // Read commands from the stdin.
//        ChannelFuture lastWriteFuture = null;
//        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
//        for (;;) {
//            String line = in.readLine();
//            if (line == null) {
//                break;
//            }
//
//            // Sends the received line to the server.
//            lastWriteFuture = channel.write(line + "\r\n");
//
//            // If user typed the 'bye' command, wait until the server closes
//            // the connection.
//            if (line.toLowerCase().equals("bye")) {
//                channel.getCloseFuture().awaitUninterruptibly();
//                break;
//            }
//        }
//
//        // Wait until all messages are flushed before closing the channel.
//        if (lastWriteFuture != null) {
//            lastWriteFuture.awaitUninterruptibly();
//        }
//
//        // Close the connection.  Make sure the close operation ends because
//        // all I/O operations are asynchronous in Netty.
//        channel.close().awaitUninterruptibly();
//
//        // Shut down all thread pools to exit.
//        bootstrap.releaseExternalResources();
    }

    public static void main(String[] args) throws Exception {
        // Print usage if no argument is specified.
//        if (args.length != 2) {
//            System.err.println(
//                    "Usage: " + SecureChatClient.class.getSimpleName() +
//                    " <host> <port>");
//            return;
//        }

        // Parse options.
        String host = "localhost";
        int port = 8081;

        new SecureChatClient(host, port).run();
    }
}
