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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.support.header.HeaderExchanger;

/**
 * Exchanger. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Message_Exchange_Pattern">Message Exchange Pattern</a>
 * <a href="http://en.wikipedia.org/wiki/Request-response">Request-Response</a>
 *
 * <p>
 * Exchange信息交换层介于Protocol层与Transport层之间，起着承上启下的作用，在Exchange层，封装请求响应模式，将Invocation与Result封装成Request与Response，将请求同步转成异步。
 * 以 Request, Response 为中心，扩展接口为 Exchanger, ExchangeChannel, ExchangeClient, ExchangeServer。
 *
 * <p>
 * dubbo spi 扩展点，默认实现类是HeaderExchanger ，它提供了bind与connect 方法抽象，在服务暴露的时候会调用bind方法来绑定一个服务器，
 * 返回对应的ExchangeServer，在服务引用的时候通过connect方法创建客户端连接服务端，返回对应的ExchangeClient。
 *
 */
@SPI(HeaderExchanger.NAME) // 默认就是header，HeaderExchanger
public interface Exchanger {

    /**
     * bind.
     *
     * @param url
     * @param handler
     * @return message server
     */
    @Adaptive({Constants.EXCHANGER_KEY})
    ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException;

    /**
     * connect.
     *
     * @param url
     * @param handler
     * @return message channel
     */
    @Adaptive({Constants.EXCHANGER_KEY})
    ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException;

}
