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
package org.apache.dubbo.remoting.http;

import org.apache.dubbo.common.Resetable;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingServer;

import java.net.InetSocketAddress;

/**
 * 生产者当开启HTTP协议的的时候，服务接口
 */
public interface HttpServer extends Resetable, RemotingServer {

    /**
     * 获得http的处理类
     *
     * @return HttpHandler
     */
    HttpHandler getHttpHandler();

    /**
     * get url.
     *
     * @return url
     */
    URL getUrl();

    /**
     * 获得本地服务器地址
     *
     * @return InetSocketAddress
     */
    InetSocketAddress getLocalAddress();

    /**
     * close the channel.
     */
    void close();

    /**
     * Graceful close the channel.
     */
    void close(int timeout);

    /**
     * is bound.
     *
     * @return bound
     */
    boolean isBound();

    /**
     * is closed.
     *
     * @return closed
     */
    boolean isClosed();

}