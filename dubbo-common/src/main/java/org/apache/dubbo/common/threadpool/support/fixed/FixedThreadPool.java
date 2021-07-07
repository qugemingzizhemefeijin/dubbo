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
package org.apache.dubbo.common.threadpool.support.fixed;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.threadpool.support.AbortPolicyWithReport;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_QUEUES;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREADS;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREAD_NAME;
import static org.apache.dubbo.common.constants.CommonConstants.QUEUES_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREAD_NAME_KEY;

/**
 * 实现 ThreadPool 接口，固定大小线程池，启动时建立线程，不关闭，一直持有。
 *
 * @see java.util.concurrent.Executors#newFixedThreadPool(int)
 */
public class FixedThreadPool implements ThreadPool {

    @Override
    public Executor getExecutor(URL url) {
        // 根据URL中的threadname取名称，如果为空，则默认为Dubbo
        // 实际真正的设置在org.apache.dubbo.remoting.transport.netty.NettyServer中的构造函数中
        // 调用了org.apache.dubbo.common.utils.ExecutorUtil.setThreadName方法，所有默认的名字是 DubboServerHandler-IP:PORT-thread-N
        String name = url.getParameter(THREAD_NAME_KEY, DEFAULT_THREAD_NAME);
        // 根据URL中的threads初始化线程大小，如果为空，则默认为200
        int threads = url.getParameter(THREADS_KEY, DEFAULT_THREADS);
        // 根据URL中的queues初始化任务队列大小
        // 0 代表使用 SynchronousQueue队列（默认）
        // < 0 则使用LinkedBlockingQueue 无界队列
        // > 0 则使用LinkedBlockingQueue 指定队列上限
        int queues = url.getParameter(QUEUES_KEY, DEFAULT_QUEUES);
        // 创建的线程池跟 Executors.newFixedThreadPool 方法差不多，更改了ThreadFactory和拒绝策略，其他都一样
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                queues == 0 ? new SynchronousQueue<Runnable>() :
                        (queues < 0 ? new LinkedBlockingQueue<Runnable>()
                                : new LinkedBlockingQueue<Runnable>(queues)),
                new NamedInternalThreadFactory(name, true), new AbortPolicyWithReport(name, url));
    }

}
