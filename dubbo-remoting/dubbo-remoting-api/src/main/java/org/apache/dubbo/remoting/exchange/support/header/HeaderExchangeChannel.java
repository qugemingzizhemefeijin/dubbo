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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * ExchangeReceiver
 */
final class HeaderExchangeChannel implements ExchangeChannel {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeChannel.class);

    private static final String CHANNEL_KEY = HeaderExchangeChannel.class.getName() + ".CHANNEL";

    /**
     * 封装的连接，默认为NettyChannel
     */
    private final Channel channel;

    private volatile boolean closed = false;

    HeaderExchangeChannel(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel == null");
        }
        this.channel = channel;
    }

    /**
     * 将传入的Channel封装为HeaderExchangeChannel
     * @param ch Channel
     * @return HeaderExchangeChannel
     */
    static HeaderExchangeChannel getOrAddChannel(Channel ch) {
        if (ch == null) {
            return null;
        }
        // 如果传入的Channel已经绑定了HeaderExchangeChannel则直接返回，否则封装。
        HeaderExchangeChannel ret = (HeaderExchangeChannel) ch.getAttribute(CHANNEL_KEY);
        if (ret == null) {
            ret = new HeaderExchangeChannel(ch);
            if (ch.isConnected()) {
                // 对Channel绑定属性HeaderExchangeChannel
                ch.setAttribute(CHANNEL_KEY, ret);
            }
        }
        return ret;
    }

    /**
     * 当连接断开的时候移除绑定的HeaderExchangeChannel
     * @param ch Channel
     */
    static void removeChannelIfDisconnected(Channel ch) {
        if (ch != null && !ch.isConnected()) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    /**
     * 直接移除绑定的HeaderExchangeChannel
     * @param ch Channel
     */
    static void removeChannel(Channel ch) {
        if (ch != null) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    @Override
    public void send(Object message) throws RemotingException {
        // 异步发送消息
        send(message, false);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        // 已经关闭 抛出异常
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null,
                    "Failed to send message " + message + ", cause: The channel " + this + " is closed!");
        }
        // 发送的对象是否是Request类型或者是Respose类型，String类类型（这个是telnet），如果是的话，直接发送，如果不是的话创建Request对象人，然后进行封装发送。
        if (message instanceof Request
                || message instanceof Response
                || message instanceof String) {
            channel.send(message, sent);
        } else {
            Request request = new Request();
            request.setVersion(Version.getProtocolVersion());
            request.setTwoWay(false);
            request.setData(message);
            channel.send(request, sent);
        }
    }

    @Override
    public CompletableFuture<Object> request(Object request) throws RemotingException {
        // 发送消息
        return request(request, null);
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout) throws RemotingException {
        // 发送消息，指定超时时间
        return request(request, timeout, null);
    }

    @Override
    public CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException {
        // 发送消息，并从Channel的URL中获取超时时间，获取不到则默认为1秒。
        return request(request, channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT), executor);
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException {
        // 判断是否关闭
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null,
                    "Failed to send request " + request + ", cause: The channel " + this + " is closed!");
        }
        // create request. 创建请求对象 ，封装Request对象
        Request req = new Request();
        req.setVersion(Version.getProtocolVersion());
        req.setTwoWay(true);
        req.setData(request);
        // 创建Future，并将自己加入到全局的FUTURES中，key为request的ID，value=DefaultFuture
        DefaultFuture future = DefaultFuture.newFuture(channel, req, timeout, executor);
        try {
            // 发送请求 这个channel 默认就是NettyChannel
            channel.send(req);
        } catch (RemotingException e) {
            future.cancel(); // 出现异常 future 取消
            throw e;
        }
        return future;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        // If the channel has been closed, return directly.
        if (closed) {
            return;
        }
        try {
            // graceful close
            DefaultFuture.closeChannel(channel);
            channel.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    // graceful close
    @Override
    public void close(int timeout) {
        // 先判断连接是否关闭了
        if (closed) {
            return;
        }
        closed = true;
        if (timeout > 0) {
            long start = System.currentTimeMillis();
            // 就是判断还有没有任务，然后时间有没有超时。尽量在关闭的允许范围内将等待任务完成。
            while (DefaultFuture.hasFuture(channel)
                    && System.currentTimeMillis() - start < timeout) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        close();
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    @Override
    public URL getUrl() {
        return channel.getUrl();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return (ExchangeHandler) channel.getChannelHandler();
    }

    @Override
    public Object getAttribute(String key) {
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        return channel.hasAttribute(key);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((channel == null) ? 0 : channel.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        HeaderExchangeChannel other = (HeaderExchangeChannel) obj;
        if (channel == null) {
            if (other.channel != null) {
                return false;
            }
        } else if (!channel.equals(other.channel)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return channel.toString();
    }

}
