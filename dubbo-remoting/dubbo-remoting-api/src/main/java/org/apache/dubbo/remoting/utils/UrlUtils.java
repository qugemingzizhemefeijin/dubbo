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

package org.apache.dubbo.remoting.utils;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Constants;

public class UrlUtils {

    /**
     * 在URL中获取心跳超时时间，未配置的话，则默认为 3 * 心跳间隔时间，未配置间隔时间，则默认返回180S
     * @param url URL
     * @return int
     */
    public static int getIdleTimeout(URL url) {
        // 获取心跳间隔时间
        int heartBeat = getHeartbeat(url);
        // idleTimeout should be at least more than twice heartBeat because possible retries of client.
        // 获取配置的心跳超时时间，如果未配置，则默认为 3 * heartBeat，默认为3分钟
        int idleTimeout = url.getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, heartBeat * 3);
        // 配置的心跳超时时间不能小于2倍的心跳间隔时间，本来至少要两次间隔才能决定此连接不可用
        if (idleTimeout < heartBeat * 2) {
            throw new IllegalStateException("idleTimeout < heartbeatInterval * 2");
        }
        return idleTimeout;
    }

    /**
     * 在URL中获取heartbeat的配置心跳间隔时间，如果没有的话则默认为60S
     * @param url URL
     * @return int
     */
    public static int getHeartbeat(URL url) {
        return url.getParameter(Constants.HEARTBEAT_KEY, Constants.DEFAULT_HEARTBEAT);
    }
}
