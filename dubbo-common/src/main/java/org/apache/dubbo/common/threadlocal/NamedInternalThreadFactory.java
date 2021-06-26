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

package org.apache.dubbo.common.threadlocal;

import org.apache.dubbo.common.utils.NamedThreadFactory;

/**
 *
 * Dubbo 的线程创建工厂
 *
 * NamedInternalThreadFactory
 * This is a threadFactory which produce {@link InternalThread}
 */
public class NamedInternalThreadFactory extends NamedThreadFactory {

    /**
     * 默认构造函数，创建的线程名称为 pool-n
     */
    public NamedInternalThreadFactory() {
        super();
    }

    /**
     * 构造函数，传入线程的名称前缀，创建非守护线程
     * @param prefix 线程名称前缀，默认是Dubbo，最后组装的名称实际是 Dubbo-thread-n
     */
    public NamedInternalThreadFactory(String prefix) {
        super(prefix, false);
    }

    /**
     * 构造函数，传入线程的名称前缀以及都是创建的是守护线程，默认情况下，Dubbo创建的都是守护线程
     * @param prefix 线程名称前缀，默认是Dubbo，最后组装的名称实际是 Dubbo-thread-n
     * @param daemon 是否是守护线程
     */
    public NamedInternalThreadFactory(String prefix, boolean daemon) {
        super(prefix, daemon);
    }

    @Override
    public Thread newThread(Runnable runnable) {
        // 拼接线程的名称， Dubbo-thread-n
        String name = mPrefix + mThreadNum.getAndIncrement();
        // 创建线程（此处包装了Runnable主要是在线程执行完之后，执行一些清理工作）
        InternalThread ret = new InternalThread(mGroup, InternalRunnable.Wrap(runnable), name, 0);
        // 设置是否是守护线程
        ret.setDaemon(mDaemon);
        return ret;
    }
}
