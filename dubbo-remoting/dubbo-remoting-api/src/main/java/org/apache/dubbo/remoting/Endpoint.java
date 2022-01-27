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
package org.apache.dubbo.remoting;

import org.apache.dubbo.common.URL;

import java.net.InetSocketAddress;

/**
 * Endpoint. (API/SPI, Prototype, ThreadSafe)
 *
 *
 * @see org.apache.dubbo.remoting.Channel
 * @see org.apache.dubbo.remoting.Client
 * @see RemotingServer
 */
public interface Endpoint {

    /**
     * get url.
     *
     * @return url
     */
    URL getUrl();

    /**
     * get channel handler.
     *
     * @return channel handler
     */
    ChannelHandler getChannelHandler();

    /**
     * get local address.
     *
     * @return local address.
     */
    InetSocketAddress getLocalAddress();

    /**
     * send message. 这个是服务对连接发送消息，默认是同步发送，就是需要等待netty将消息成功写入到socket中并且发送出去
     *
     * @param message
     * @throws RemotingException
     */
    void send(Object message) throws RemotingException;

    /**
     * send message. 这个是服务对连接发送消息。如AbstractServer中的send就是对连接其上的所有的客户端发送指定的消息
     *
     * @param message
     * @param sent    already sent to socket? 判断是否需要等待消息发送出去了
     */
    void send(Object message, boolean sent) throws RemotingException;

    /**
     * 关闭服务/链接
     */
    void close();

    /**
     * 优雅地关闭服务/链接
     */
    void close(int timeout);

    /**
     * 通知服务/链接我要开始关闭了（实际应该是调用完之后需要一定的业务处理之后再调用close方法）
     */
    void startClose();

    /**
     * is closed.
     *
     * @return closed
     */
    boolean isClosed();

}