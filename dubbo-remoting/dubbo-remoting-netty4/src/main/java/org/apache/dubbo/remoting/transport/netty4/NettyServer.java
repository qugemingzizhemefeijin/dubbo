/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.transport.AbstractServer;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelHandlers;
import org.apache.dubbo.remoting.utils.UrlUtils;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.dubbo.common.constants.CommonConstants.IO_THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.KEEP_ALIVE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SSL_ENABLED_KEY;


/**
 * NettyServer.
 */
public class NettyServer extends AbstractServer implements RemotingServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);
    /**
     * the cache for alive worker channel.
     * <ip:port, dubbo channel>
     */
    private Map<String, Channel> channels;
    /**
     * netty server bootstrap.
     */
    private ServerBootstrap bootstrap;
    /**
     * the boss channel that receive connections and dispatch these to worker channel.
     */
	private io.netty.channel.Channel channel;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        // you can customize name and type of client thread pool by THREAD_NAME_KEY and THREADPOOL_KEY in CommonConstants.
        // the handler will be wrapped: MultiMessageHandler->HeartbeatHandler->handler
        super(ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME), ChannelHandlers.wrap(handler, url));
    }

    /**
     * Init and start netty server
     *
     * @throws Throwable
     */
    @Override
    protected void doOpen() throws Throwable {
        // ServerBootstrap是服务端启动辅助类，通过他可以方便的创建一个Netty服务端
        bootstrap = new ServerBootstrap();

        // 创建处理客户端连接的线程池，线程只有一个，而且都是后台线程
        bossGroup = NettyEventLoopFactory.eventLoopGroup(1, "NettyServerBoss");
        // 创建处理客户端IO操作的线程池，线程数默认是处理器个数+1，可以通过参数iothreads配置
        workerGroup = NettyEventLoopFactory.eventLoopGroup(
                getUrl().getPositiveParameter(IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS),
                "NettyServerWorker");

        // 创建服务端处理器
        final NettyServerHandler nettyServerHandler = new NettyServerHandler(getUrl(), this);
        // channels代表了所有客户端的连接
        channels = nettyServerHandler.getChannels();

        // 支持keepalive，此keepalive与http的keepalive不是一个概念
        boolean keepalive = getUrl().getParameter(KEEP_ALIVE_KEY, Boolean.FALSE);

        // 配置ServerBootstrap
        bootstrap.group(bossGroup, workerGroup)
                .channel(NettyEventLoopFactory.serverSocketChannelClass())
                // SO_REUSEADDR=true表示允许重复使用本地地址和端口
                .option(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                // 低延迟发送数据
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                // 是否开启tcp的链接测试，当设置该选项以后，如果在两小时内没有数据的通信时，TCP会自动发送一个活动探测数据报文
                .childOption(ChannelOption.SO_KEEPALIVE, keepalive)
                // ALLOCATOR设置内存分配器，Channel收到的数据保存在该分配器分配的内存中，默认使用的是池化直接内存
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        // 初始化连接channel
                        // 当客户端连接进来后，便使用下面的方法对其Channel初始化
                        // FIXME: should we use getTimeout()?
                        // 获得心跳超时时间，可以通过heartbeat.timeout配置，默认超时时间是3分钟
                        int idleTimeout = UrlUtils.getIdleTimeout(getUrl());
                        NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                        // 是否开启SSL
                        if (getUrl().getParameter(SSL_ENABLED_KEY, false)) {
                            ch.pipeline().addLast("negotiation",
                                    SslHandlerInitializer.sslServerHandler(getUrl(), nettyServerHandler));
                        }
                        ch.pipeline()
                                // 设置解码器
                                .addLast("decoder", adapter.getDecoder())
                                // 设置编码器
                                .addLast("encoder", adapter.getEncoder())
                                // 设置心跳检测处理器
                                // readerIdleTime：读空闲超时检测定时任务会在每readerIdleTime时间内启动一次，检测在readerIdleTime内是否发生过读事件，如果没有发生过，则触发读超时事件READER_IDLE_STATE_EVENT，并将超时事件交给NettyClientHandler处理。如果为0，则不创建定时任务。
                                // writerIdleTime：与readerIdleTime作用类似，只不过该参数定义的是写事件。
                                // allIdleTime：同时检测读事件和写事件，如果在allIdleTime时间内即没有发生过读事件，也没有发生过写事件，则触发超时事件ALL_IDLE_STATE_EVENT
                                // unit：表示前面三个参数的单位，就上面代码来说，表示的是毫秒

                                // 服务端创建的IdleStateHandler设置了allIdleTime，所以服务端的定时任务需要检测读事件和写事件。
                                // 定时任务的启动时间间隔是参数“heartbeat”设置值的3倍，heartbeat默认是1分钟，
                                // 也可以通过参数“heartbeat.timeout”设置定时任务的启动时间间隔。单位都是毫秒。

                                // dubbo对超时的检测是借助Netty的IdleStateHandler完成的，一旦发生超时，则创建超时事件并交给NettyClientHandler或者NettyServerHandler处理，服务端是关闭连接，客户端是发送心跳报文继续维持连接。
                                .addLast("server-idle-handler", new IdleStateHandler(0, 0, idleTimeout, MILLISECONDS))
                                // 设置自定义处理器，该处理器最后通过异步线程调用到真正提供服务的对象上
                                .addLast("handler", nettyServerHandler);
                    }
                });
        // bind
        // 绑定端口
        ChannelFuture channelFuture = bootstrap.bind(getBindAddress());
        // 执行下面的代码后，表示服务端可以接收外部请求了
        channelFuture.syncUninterruptibly();
        // 获得代表服务端的channel对象
        channel = channelFuture.channel();
    }

    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            Collection<Channel> channels = getChannels();
            if (channels != null && channels.size() > 0) {
                for (Channel channel : channels) {
                    try {
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (bootstrap != null) {
                bossGroup.shutdownGracefully().syncUninterruptibly();
                workerGroup.shutdownGracefully().syncUninterruptibly();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (channels != null) {
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new ArrayList<>(this.channels.size());
        chs.addAll(this.channels.values());
        // check of connection status is unnecessary since we are using channels in NettyServerHandler
//        for (Channel channel : this.channels.values()) {
//            if (channel.isConnected()) {
//                chs.add(channel);
//            } else {
//                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
//            }
//        }
        return chs;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public boolean canHandleIdle() {
        return true;
    }

    @Override
    public boolean isBound() {
        return channel.isActive();
    }

}
