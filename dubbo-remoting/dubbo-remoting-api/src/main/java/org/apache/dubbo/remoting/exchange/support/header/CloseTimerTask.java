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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;

/**
 * CloseTimerTask
 */
public class CloseTimerTask extends AbstractTimerTask {

    private static final Logger logger = LoggerFactory.getLogger(CloseTimerTask.class);

    /**
     * 获取心跳空闲超时时间
     */
    private final int idleTimeout;

    public CloseTimerTask(ChannelProvider channelProvider, Long heartbeatTimeoutTick, int idleTimeout) {
        super(channelProvider, heartbeatTimeoutTick);
        this.idleTimeout = idleTimeout;
    }

    // 此方法在父类的run中调用，循环次服务所有的连接，判断是否超时了。
    @Override
    protected void doTask(Channel channel) {
        try {
            // 最后一次读
            Long lastRead = lastRead(channel);
            // 最后一次写
            Long lastWrite = lastWrite(channel);
            Long now = now();
            // check ping & pong at server
            // 最后一次读的时间不是null &&  最后一次读的时间距离现在不超过 心跳间隔时间
            if ((lastRead != null && now - lastRead > idleTimeout)
                    // 或者最后一次写的时间不是null && 最后一次写的时间距离现在不超过 心跳间隔时间
                    || (lastWrite != null && now - lastWrite > idleTimeout)) {
                logger.warn("Close channel " + channel + ", because idleCheck timeout: "
                        + idleTimeout + "ms");
                // 服务端断开连接
                channel.close();
            }
        } catch (Throwable t) {
            logger.warn("Exception when close remote channel " + channel.getRemoteAddress(), t);
        }
    }
}
