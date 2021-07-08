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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.FORKS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_FORKS;

/**
 * NOTICE! This implementation does not work well with async call.
 *
 * 注意！此实现不适用于异步调用。
 *
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *
 * 同时调用多个相同的服务，只要其中一个返回，则立即返回结果。用户可以配置forks=""参数来确定最大并行调用的服务数量。会耗费更多的服务器资源。
 *
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link org.apache.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link org.apache.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     *
     * 这里new了一个newCachedThreadPool的线程池
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            // 1、检查是否可用服务列表未空
            checkInvokers(invokers, invocation);
            final List<Invoker<T>> selected;
            // 2、获取forks值，默认值为2
            final int forks = getUrl().getParameter(FORKS_KEY, DEFAULT_FORKS);
            // 3、获取timeout值，默认为1秒
            final int timeout = getUrl().getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
            // 4、选取可用的服务，如果forks大于服务数量，则直接为服务集合，否则需要通过负载均衡算法来获取可调用服务
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;
            } else {
                selected = new ArrayList<>(forks);
                while (selected.size() < forks) {
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    if (!selected.contains(invoker)) {
                        //Avoid add the same invoker several times.
                        selected.add(invoker);
                    }
                }
            }
            RpcContext.getContext().setInvokers((List) selected);
            final AtomicInteger count = new AtomicInteger();
            // 5、下面的代码没什么说的了，就是通过LinkedBlockingQueue来实现获取一个调用成功，即可完成。
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<>();
            for (final Invoker<T> invoker : selected) {
                executor.execute(() -> {
                    try {
                        Result result = invoker.invoke(invocation);
                        ref.offer(result);
                    } catch (Throwable e) {
                        int value = count.incrementAndGet();
                        // 这里如果所有的都失败了，则将异常信息存入LinkedBlockingQueue中
                        if (value >= selected.size()) {
                            ref.offer(e);
                        }
                    }
                });
            }
            try {
                // 6、该返回返回，该异常异常，没什么好说的。
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                if (ret instanceof Throwable) {
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                return (Result) ret;
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            // clear attachments which is binding to current thread.
            RpcContext.getContext().clearAttachments();
        }
    }
}
