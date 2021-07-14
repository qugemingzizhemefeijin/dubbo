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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * BroadcastCluster2Invoker
 * <p>
 * sed for collecting all service provider results when in broadcast2 mode
 */
public class BroadcastCluster2Invoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(BroadcastCluster2Invoker.class);

    private static final String BROADCAST_RESULTS_KEY = "broadcast.results";

    // 创建会重用空闲并且可用的线程，如果无可用的线程，将会创建新的线程。线程池活跃1分钟
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("broadcast_cluster2", true));

    public BroadcastCluster2Invoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        // 1、检查是否有可用的服务
        checkInvokers(invokers, invocation);
        // 2、设置当前上下文中使用的Invokers列表
        RpcContext.getContext().setInvokers((List) invokers);
        // 3、调用服务
        InvokeResult res = invoke(invokers, invocation);
        // 4、如果有异常，则返回异常信息
        if (hasException(res.exception)) {
            return createResult(invocation, res.exception, res.resultList);
        }
        // 5、否则返回第一个返回的结果信息
        Object value = res.resultList.stream().map(it->it.getData()).findFirst().orElse(null);
        return createResult(invocation, value, res.resultList);
    }

    /**
     * 这里使用了线程池来异步调用所有的服务
     * @param invokers   待调用的服务列表
     * @param invocation 调用方法的描述对象
     * @return InvokeResult
     */
    private InvokeResult invoke(List<Invoker<T>> invokers, final Invocation invocation) {
        List<BroadcastResult> resultList = new ArrayList<>(invokers.size());
        // 将所有的服务封装成Callable列表，并通过线程池调用
        List<Callable<BroadcastResult>> tasks = getCallables(invokers, invocation);

        try {
            List<Future<BroadcastResult>> futures = executor.invokeAll(tasks);
            resultList = futures.stream().map(it -> {
                try {
                    return it.get();
                } catch (Throwable e) {
                    BroadcastResult br = new BroadcastResult();
                    br.setException(getRpcException(e));
                    br.setExceptionMsg(br.getException().getMessage());
                    return br;
                }
            }).collect(Collectors.toList());
        } catch (InterruptedException e) {
            BroadcastResult br = new BroadcastResult();
            br.setException(getRpcException(e));
            br.setExceptionMsg(br.getException().getMessage());
            resultList.add(br);
        }

        // 这里将获取到所有的调用信息回传，并回传其中出现错误的一个Exception
        return new InvokeResult(resultList.stream().map(BroadcastResult::getException)
                .filter(it -> null != it).findFirst().orElse(null), resultList);

    }

    /**
     * 这里主要是包装成Callable对象，方便线程池调用
     * @param invokers   待请求的服务列表
     * @param invocation 请求的方法描述对象
     * @return List<Callable<BroadcastResult>>
     */
    private List<Callable<BroadcastResult>> getCallables(List<Invoker<T>> invokers, Invocation invocation) {
        List<Callable<BroadcastResult>> tasks = invokers.stream().map(it -> (Callable<BroadcastResult>) () -> {
            // 这里实际调用的是Callable的call
            BroadcastResult br = new BroadcastResult(it.getUrl().getIp(), it.getUrl().getPort());
            Result result = null;
            try {
                result = it.invoke(invocation);
                if (null != result && result.hasException()) {
                    Throwable resultException = result.getException();
                    if (null != resultException) {
                        RpcException exception = getRpcException(result.getException());
                        br.setExceptionMsg(exception.getMessage());
                        br.setException(exception);
                        logger.warn(exception.getMessage(), exception);
                    }
                } else if (null != result) {
                    br.setData(result.getValue());
                    br.setResult(result);
                }
            } catch (Throwable ex) {
                RpcException exception = getRpcException(result.getException());
                br.setExceptionMsg(exception.getMessage());
                br.setException(exception);
                logger.warn(exception.getMessage(), exception);
            }
            return br;
        }).collect(Collectors.toList());
        return tasks;
    }


    private class InvokeResult {
        public RpcException exception;
        public List<BroadcastResult> resultList;

        public InvokeResult(RpcException ex, List<BroadcastResult> resultList) {
            this.exception = ex;
            this.resultList = resultList;
        }
    }


    /**
     * 判断是否是发生了错误
     * @param exception RpcException
     * @return boolean
     */
    private boolean hasException(RpcException exception) {
        return null != exception;
    }

    private Result createResult(Invocation invocation, RpcException exception, List<BroadcastResult> resultList) {
        AppResponse result = new AppResponse(invocation) {
            @Override
            public Result whenCompleteWithContext(BiConsumer<Result, Throwable> fn) {
                RpcContext.getServerContext().setAttachment(BROADCAST_RESULTS_KEY, new Gson().toJson(resultList));
                return new AppResponse();
            }
        };
        result.setException(exception);
        return result;
    }

    private Result createResult(Invocation invocation, Object value, List<BroadcastResult> resultList) {
        return new AppResponse(invocation) {
            @Override
            public Result whenCompleteWithContext(BiConsumer<Result, Throwable> fn) {
                RpcContext.getServerContext().setAttachment(BROADCAST_RESULTS_KEY, new Gson().toJson(resultList));
                AppResponse res = new AppResponse();
                res.setValue(value);
                return res;
            }
        };
    }

    private RpcException getRpcException(Throwable throwable) {
        RpcException rpcException = null;
        if (throwable instanceof RpcException) {
            rpcException = (RpcException) throwable;
        } else {
            rpcException = new RpcException(throwable.getMessage(), throwable);
        }
        return rpcException;
    }
}
