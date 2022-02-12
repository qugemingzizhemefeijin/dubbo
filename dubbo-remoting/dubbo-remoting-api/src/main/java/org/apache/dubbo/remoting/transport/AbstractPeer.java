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
package org.apache.dubbo.remoting.transport;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.Endpoint;
import org.apache.dubbo.remoting.RemotingException;

/**
 * AbstractPeer
 */
public abstract class AbstractPeer implements Endpoint, ChannelHandler {

    /**
     * 事件处理Handler
     */
    private final ChannelHandler handler;

    /**
     * 该协议的第一个服务提供者的URL，Server只需要用到URL中的参数，与具体某一个服务没什么关系
     */
    private volatile URL url;

    // closing closed means the process is being closed and close is finished
    private volatile boolean closing;

    private volatile boolean closed;

    public AbstractPeer(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    @Override
    public void send(Object message) throws RemotingException {
        // 发送消息，默认sent如果未配置，则为false，如果是NettyChannel的话，则是同步发送（就是保证netty真正的将消息发送出去）
        send(message, url.getParameter(Constants.SENT_KEY, false));
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void close(int timeout) {
        close();
    }

    @Override
    public void startClose() {
        if (isClosed()) {
            return;
        }
        closing = true;
    }

    /**
     * 服务端的URL内容：<br>
     * dubbo://127.0.0.1:20801/com.xxx.yy?
     * anyhost=true&application=aaaa&bind.ip=172.18.5.1&bind.port=20801&channel.readonly.sent=true&codec=dubbo&deprecated=false
     * &dubbo=2.0.2&dynamic=true&generic=false&heartbeat=60000&interface=com.xxx.yy&metadata-type=remote&methods=helloWorldpid=2748
     * &qos.enable=false&release=2.7.7&retries=0&side=provider&telnet=help&timeout=60000&timestamp=1644631573915&version=3.0.0
     *
     * <br><br>
     * 客户端的URL内容：<br>
     * dubbo://192.168.1.1:20881/com.xx.yy?application=xxxx&check=false&codec=dubbo&connect.timeout=10000&deprecated=false&dubbo=2.0.2
     * &heartbeat=60000&init=false&interface=com.xx.yy&metadata-type=remote&pid=12904&qos.enable=false&register.ip=172.18.1.1&release=2.7.7
     * &remote.application=sayHello-service&revision=1.0.0&side=consumer&sticky=false&timeout=60000&timestamp=1644401387420&version=3.0.0
     * <br><br>
     *
     * @return URL
     */
    @Override
    public URL getUrl() {
        return url;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        this.url = url;
    }

    @Override
    public ChannelHandler getChannelHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }

    /**
     * @return ChannelHandler
     */
    @Deprecated
    public ChannelHandler getHandler() {
        return getDelegateHandler();
    }

    /**
     * Return the final handler (which may have been wrapped). This method should be distinguished with getChannelHandler() method
     *
     * @return ChannelHandler
     */
    public ChannelHandler getDelegateHandler() {
        return handler;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public boolean isClosing() {
        return closing && !closed;
    }

    @Override
    public void connected(Channel ch) throws RemotingException {
        if (closed) {
            return;
        }
        handler.connected(ch);
    }

    @Override
    public void disconnected(Channel ch) throws RemotingException {
        handler.disconnected(ch);
    }

    @Override
    public void sent(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        handler.sent(ch, msg);
    }

    @Override
    public void received(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        // 如果子类为 NettyServer 则 handler 默认为 MultiMessageHandler，这个是在 NettyServer构造函数中创建的，ChannelHandlers.wrap(handler, url) 方法。
        handler.received(ch, msg);
    }

    @Override
    public void caught(Channel ch, Throwable ex) throws RemotingException {
        handler.caught(ch, ex);
    }
}
