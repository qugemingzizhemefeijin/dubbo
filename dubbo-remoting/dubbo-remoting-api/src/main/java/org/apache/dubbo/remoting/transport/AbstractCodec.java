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
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;

/**
 * AbstractCodec
 */
public abstract class AbstractCodec implements Codec2 {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCodec.class);

    private static final String CLIENT_SIDE = "client";

    private static final String SERVER_SIDE = "server";

    /**
     * 校验数据长度，是否超过指定的上限
     * @param channel Channel
     * @param size    数据长度
     * @throws IOException
     */
    protected static void checkPayload(Channel channel, long size) throws IOException {
        // 获取当前的最大可传输数据
        int payload = getPayload(channel);
        // 判断是否超出最大可传输数据
        boolean overPayload = isOverPayload(payload, size);
        if (overPayload) {
            ExceedPayloadLimitException e = new ExceedPayloadLimitException(
                    "Data length too large: " + size + ", max payload: " + payload + ", channel: " + channel);
            logger.error(e);
            throw e;
        }
    }

    /**
     * 获取最大的数据承载量，默认为8M，可以对某个请求单独设置payload来提高数据承载量
     * @param channel Channel
     * @return int
     */
    protected static int getPayload(Channel channel) {
        // 默认8M
        int payload = Constants.DEFAULT_PAYLOAD;
        if (channel != null && channel.getUrl() != null) {
            payload = channel.getUrl().getParameter(Constants.PAYLOAD_KEY, Constants.DEFAULT_PAYLOAD);
        }
        return payload;
    }

    /**
     * 判断是否超过了最大可传输数据
     * @param payload int
     * @param size    long
     * @return boolean
     */
    protected static boolean isOverPayload(int payload, long size) {
        if (payload > 0 && size > payload) {
            return true;
        }
        return false;
    }

    /**
     * 获取序列化实现类
     * @param channel Channel
     * @param req     Request
     * @return Serialization
     */
    protected Serialization getSerialization(Channel channel, Request req) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    /**
     * 获取序列化实现类
     * @param channel Channel
     * @param res     Response
     * @return Serialization
     */
    protected Serialization getSerialization(Channel channel, Response res) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    /**
     * 获取序列化实现类
     * @param channel Channel
     * @return Serialization
     */
    protected Serialization getSerialization(Channel channel) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    /**
     * 是否客户端侧
     * @param channel Channel
     * @return boolean
     */
    protected boolean isClientSide(Channel channel) {
        // 判断当前的通道是否是客户端
        String side = (String) channel.getAttribute(SIDE_KEY);
        if (CLIENT_SIDE.equals(side)) {
            return true;
        } else if (SERVER_SIDE.equals(side)) {
            return false;
        } else {
            InetSocketAddress address = channel.getRemoteAddress();
            URL url = channel.getUrl();
            // 如果url中与address中维护的远端端口号一致，并且与远端IP地址一致，则认为是客户端
            boolean isClient = url.getPort() == address.getPort()
                && NetUtils.filterLocalHost(url.getIp()).equals(
                NetUtils.filterLocalHost(address.getAddress()
                    .getHostAddress()));
            channel.setAttribute(SIDE_KEY, isClient ? CLIENT_SIDE
                : SERVER_SIDE);
            return isClient;
        }
    }

    /**
     * 是否是服务端侧
     * @param channel Channel
     * @return boolean
     */
    protected boolean isServerSide(Channel channel) {
        return !isClientSide(channel);
    }

}
