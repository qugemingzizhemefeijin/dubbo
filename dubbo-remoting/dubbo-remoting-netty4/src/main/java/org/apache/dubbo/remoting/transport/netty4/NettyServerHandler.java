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
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.transport.netty4.SslHandlerInitializer.HandshakeCompletionEvent;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateEvent;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NettyServerHandler. dubbo服务端的消息处理器
 */
@io.netty.channel.ChannelHandler.Sharable
public class NettyServerHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(NettyServerHandler.class);
    /**
     * the cache for alive worker channel.
     * <ip:port, dubbo channel>
     */
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>();

    private final URL url;

    private final ChannelHandler handler;

    public NettyServerHandler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }

    // 当用户连接进来的事件回调
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 获得通道
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        // 如果通道不为空，则加入集合中
        if (channel != null) {
            channels.put(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()), channel);
        }
        // 处理连接事件
        handler.connected(channel);

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getRemoteAddress() + " -> " + channel.getLocalAddress() + " is established.");
        }
    }

    // 当用户连接断开的事件回调
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            channels.remove(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()));
            handler.disconnected(channel);
        } finally {
            NettyChannel.removeChannel(ctx.channel());
        }

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getRemoteAddress() + " -> " + channel.getLocalAddress() + " is disconnected.");
        }
    }

    // 读取消息的时间回调，在调用decode之后会通过此方法回调进来
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        handler.received(channel, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // 写入消息
        super.write(ctx, msg, promise);
        // 将消息写入其他的处理器
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        handler.sent(channel, msg);
    }

    // 当设置了readerIdleTime以后，服务端server会每隔readerIdleTime时间去检查一次channelRead方法被调用的情况，
    // 如果在readerIdleTime时间内该channel上的channelRead()方法没有被触发，就会调用userEventTriggered方法。
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // server will close channel when server don't receive any heartbeat from client util timeout.
        if (evt instanceof IdleStateEvent) {
            NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
            try {
                logger.info("IdleStateEvent triggered, close channel " + channel);
                channel.close();
            } finally {
                NettyChannel.removeChannelIfDisconnected(ctx.channel());
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    // 当连接发生异常的回调
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            handler.caught(channel, cause);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    // 握手完成？应你该是提供给子类来实现的。。。
    public void handshakeCompleted(HandshakeCompletionEvent evt) {
        // TODO
    }
}
