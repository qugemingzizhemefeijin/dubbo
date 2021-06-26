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
package org.apache.dubbo.common.threadpool;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

import java.util.concurrent.Executor;

import static org.apache.dubbo.common.constants.CommonConstants.THREADPOOL_KEY;

/**
 * Dubbo线程池提供了四种实现：<br>
 * fixed : 默认，固定大小线程池，启动时建立线程，不关闭，一直持有；<br>
 * cached : 缓存线程池，空闲一分钟自动删除，需要时重建；<br>
 * limited : 可伸缩线程池，但池中的线程数只会增长不会收缩。只增长不收缩的目的是为了避免收缩时突然来了大流量引起的性能问题；<br>
 * eager : 当所有核心线程都处于忙碌状态时，优先创建新线程执行任务；<br>
 */
@SPI("fixed")
public interface ThreadPool {

    /**
     * 基于 Dubbo SPI Adaptive 机制，加载对应的线程池实现，使用 URL.threadpool 属性。
     *
     * @param url URL contains thread parameter
     * @return thread pool
     */
    @Adaptive({THREADPOOL_KEY})
    Executor getExecutor(URL url);

}
