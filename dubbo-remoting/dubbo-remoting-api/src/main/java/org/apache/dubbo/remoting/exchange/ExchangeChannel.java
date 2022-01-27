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
package org.apache.dubbo.remoting.exchange;

import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * ExchangeChannel. (API/SPI, Prototype, ThreadSafe) <br><br>
 *
 * HeaderExchangeClient中就将client包装成了ExchangeChannel，在HeaderExchangeServer中将channel包装成了ExchangeChannel，Channel字面意思通道，可以将它理解为客户端与服务端的连接。<br><br>
 *
 * 信息交换层封装了请求响应模式，发送请求便是request。
 */
public interface ExchangeChannel extends Channel {

    /**
     * send request. 发送请求
     *
     * @param request
     * @return response future
     * @throws RemotingException
     */
    @Deprecated
    CompletableFuture<Object> request(Object request) throws RemotingException;

    /**
     * send request. 发送请求带有超时功能
     *
     * @param request
     * @param timeout
     * @return response future
     * @throws RemotingException
     */
    @Deprecated
    CompletableFuture<Object> request(Object request, int timeout) throws RemotingException;

    /**
     * send request. 发送请求并制定线程池
     *
     * @param request
     * @return response future
     * @throws RemotingException
     */
    CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException;

    /**
     * send request. 发送请求带有超时功能并指定线程池
     *
     * @param request
     * @param timeout
     * @return response future
     * @throws RemotingException
     */
    CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException;

    /**
     * get message handler. 获取消息handler
     *
     * @return message handler
     */
    ExchangeHandler getExchangeHandler();

    /**
     * graceful close. 优雅关闭
     *
     * @param timeout 超时时间
     */
    @Override
    void close(int timeout);
}
