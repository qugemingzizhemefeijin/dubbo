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

import org.apache.dubbo.common.Parameters;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Request;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.unmodifiableCollection;
import static org.apache.dubbo.common.constants.CommonConstants.READONLY_EVENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_CHECK_TICK;
import static org.apache.dubbo.remoting.Constants.LEAST_HEARTBEAT_DURATION;
import static org.apache.dubbo.remoting.Constants.TICKS_PER_WHEEL;
import static org.apache.dubbo.remoting.utils.UrlUtils.getHeartbeat;
import static org.apache.dubbo.remoting.utils.UrlUtils.getIdleTimeout;

/**
 * HeaderExchangeServer实现 ExchangerServer接口，该接口提供了两个获取ExchangeChannel的方法，这个channel可以简单理解为一个连接，可以通过channel发送与接受数据，<br><br>
 * ExchangerServer 又继承Server接口，这个Server提供了两个获取Channel的抽象，Server接口继承Endpoint与Resetable 接口，Endpoint 可以理解为一个端，这个端可以发送接受消息。<br><br>
 * Resetable接口提供一个reset重置方法抽象。HeaderExchangeServer主要是干了什么事情呢，除了上面那堆功能 ，还有“心跳”功能，它维护了与客户端的心跳。<br><br>
 */
public class HeaderExchangeServer implements ExchangeServer {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * server对象，默认为NettyServer
     */
    private final RemotingServer server;
    private AtomicBoolean closed = new AtomicBoolean(false);

    // 心跳检测定时器
    private static final HashedWheelTimer IDLE_CHECK_TIMER = new HashedWheelTimer(new NamedThreadFactory("dubbo-server-idleCheck", true), 1,
            TimeUnit.SECONDS, TICKS_PER_WHEEL);

    private CloseTimerTask closeTimerTask;

    public HeaderExchangeServer(RemotingServer server) {
        Assert.notNull(server, "server == null");
        // 服务器
        this.server = server;
        // 启动心跳
        startIdleCheckTask(getUrl());
    }

    public RemotingServer getServer() {
        return server;
    }

    @Override
    public boolean isClosed() {
        return server.isClosed();
    }

    private boolean isRunning() {
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {

            /**
             *  If there are any client connections,
             *  our server should be running.
             */

            if (channel.isConnected()) {
                return true;
            }
        }
        return false;
    }

    // 调用doClose方法，然后再调用server的close方法，关闭server
    @Override
    public void close() {
        doClose();
        server.close();
    }

    @Override
    public void close(final int timeout) {
        // 主要是为了设置服务的关闭状态位
        startClose();
        // 在一定的时间范围内给所有的连接发送只读通知
        if (timeout > 0) {
            final long max = timeout;
            final long start = System.currentTimeMillis();
            // 先是判断channel.readonly.send的属性值，缺省是true，然后调用sendChannelReadOnlyEvent 方法向所有channel发送只读消息，
            // 这个时间过了之后，才会调用doClose方法，与server的close(time)方法。
            if (getUrl().getParameter(Constants.CHANNEL_SEND_READONLYEVENT_KEY, true)) {
                sendChannelReadOnlyEvent();
            }
            while (isRunning() && System.currentTimeMillis() - start < max) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        // 执行关闭业务逻辑
        doClose();
        // 优雅的关闭server
        server.close(timeout);
    }

    @Override
    public void startClose() {
        // 通知server我要开始关闭咯
        server.startClose();
    }

    /**
     * 给所有连接到当前服务的channel发送只读通知
     */
    private void sendChannelReadOnlyEvent() {
        // 封装request请求
        Request request = new Request();
        request.setEvent(READONLY_EVENT);
        // 封装一个只读请求Request，设置setTwoWay 是false，也就是不需要对端回复，最后获取所有的channel，遍历如果没有关闭就调用send方法发送。
        request.setTwoWay(false);
        request.setVersion(Version.getProtocolVersion());

        // 循环发送给所有客户端
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {
            try {
                if (channel.isConnected()) {
                    channel.send(request, getUrl().getParameter(Constants.CHANNEL_READONLYEVENT_SENT_KEY, true));
                }
            } catch (RemotingException e) {
                logger.warn("send cannot write message error.", e);
            }
        }
    }

    /**
     * 关闭服务
     */
    private void doClose() {
        // cas关闭服务
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelCloseTask();
    }

    /**
     * 关闭心跳定时器
     */
    private void cancelCloseTask() {
        if (closeTimerTask != null) {
            closeTimerTask.cancel();
        }
    }

    @Override
    public Collection<ExchangeChannel> getExchangeChannels() {
        // 将server上所有的channel包装为ExchangeChannel
        Collection<ExchangeChannel> exchangeChannels = new ArrayList<ExchangeChannel>();
        Collection<Channel> channels = server.getChannels();
        if (CollectionUtils.isNotEmpty(channels)) {
            for (Channel channel : channels) {
                exchangeChannels.add(HeaderExchangeChannel.getOrAddChannel(channel));
            }
        }
        return exchangeChannels;
    }

    @Override
    public ExchangeChannel getExchangeChannel(InetSocketAddress remoteAddress) {
        // 上同
        Channel channel = server.getChannel(remoteAddress);
        return HeaderExchangeChannel.getOrAddChannel(channel);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Collection<Channel> getChannels() {
        // 返回被包装为ExchangeChannel的集合
        return (Collection) getExchangeChannels();
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        // 上同
        return getExchangeChannel(remoteAddress);
    }

    @Override
    public boolean isBound() {
        return server.isBound();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return server.getLocalAddress();
    }

    @Override
    public URL getUrl() {
        return server.getUrl();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return server.getChannelHandler();
    }

    // 这个reset其实就是重启一下心跳定时器
    @Override
    public void reset(URL url) {
        // 重置心跳的配置，心跳参数发生变化，重启心跳定时器
        server.reset(url);
        try {
            // 获取服务配置的心跳间隔时间
            int currHeartbeat = getHeartbeat(getUrl());
            // 获取服务配置的心跳超时时间
            int currIdleTimeout = getIdleTimeout(getUrl());
            // 获取reset传入的心跳间隔时间
            int heartbeat = getHeartbeat(url);
            // 获取reset传入的心跳超时时间
            int idleTimeout = getIdleTimeout(url);
            // 如果两个时间不相等，需要取消心跳定时器，然后再根据传入的URL重新开启心跳定时器
            if (currHeartbeat != heartbeat || currIdleTimeout != idleTimeout) {
                // 关闭心跳定时器
                cancelCloseTask();
                // 启动最新配置的心跳定时器
                startIdleCheckTask(url);
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    @Deprecated
    public void reset(Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    @Override
    public void send(Object message) throws RemotingException {
        // 判断服务是否关闭
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message
                    + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        // 服务发送消息，应该是对所有的连接发送消息
        server.send(message);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        // 判断服务是否关闭
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message
                    + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        // 服务发送消息，应该是对所有的连接发送消息
        server.send(message, sent);
    }

    /**
     * Each interval cannot be less than 1000ms. 这个是计算最小心跳时间的
     */
    private long calculateLeastDuration(int time) {
        if (time / HEARTBEAT_CHECK_TICK <= 0) {
            return LEAST_HEARTBEAT_DURATION;
        } else {
            return time / HEARTBEAT_CHECK_TICK;
        }
    }

    /**
     * 启动心跳定时器，URL = server url
     * @param url URL
     */
    private void startIdleCheckTask(URL url) {
        if (!server.canHandleIdle()) {
            AbstractTimerTask.ChannelProvider cp = () -> unmodifiableCollection(HeaderExchangeServer.this.getChannels());
            // 获取心跳空闲超时时间
            int idleTimeout = getIdleTimeout(url);
            // 每个心跳间隔的间隔时间，最小1秒
            long idleTimeoutTick = calculateLeastDuration(idleTimeout);
            // 心跳回调
            CloseTimerTask closeTimerTask = new CloseTimerTask(cp, idleTimeoutTick, idleTimeout);
            this.closeTimerTask = closeTimerTask;

            // init task and start timer.
            IDLE_CHECK_TIMER.newTimeout(closeTimerTask, idleTimeoutTick, TimeUnit.MILLISECONDS);
        }
    }
}
