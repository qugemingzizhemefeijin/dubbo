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
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.timer.Timeout;
import org.apache.dubbo.common.timer.Timer;
import org.apache.dubbo.common.timer.TimerTask;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_FAILBACK_TIMES;
import static org.apache.dubbo.common.constants.CommonConstants.RETRIES_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_FAILBACK_TASKS;
import static org.apache.dubbo.rpc.cluster.Constants.FAIL_BACK_TASKS_KEY;

/**
 * When fails, record failure requests and schedule for retry on a regular interval.
 * Especially useful for services of notification.
 *
 * <p>请求失败后，会自动记录在失败队列中，并由一个定时线程池定时重试。
 *
 * <a href="http://en.wikipedia.org/wiki/Failback">Failback</a>
 */
public class FailbackClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailbackClusterInvoker.class);

    private static final long RETRY_FAILED_PERIOD = 5;

    /**
     * 失败的任务最多执行几次，默认3次
     */
    private final int retries;

    /**
     * 失败重试定时器中最多运行多少个任务数，默认100
     */
    private final int failbackTasks;

    /**
     * 失败任务定时器实例
     */
    private volatile Timer failTimer;

    public FailbackClusterInvoker(Directory<T> directory) {
        super(directory);

        // 重试次数默认3次，retries属性配置
        int retriesConfig = getUrl().getParameter(RETRIES_KEY, DEFAULT_FAILBACK_TIMES);
        if (retriesConfig <= 0) {
            retriesConfig = DEFAULT_FAILBACK_TIMES;
        }
        // 最大的失败任务数，默认100，failbacktasks属性配置
        int failbackTasksConfig = getUrl().getParameter(FAIL_BACK_TASKS_KEY, DEFAULT_FAILBACK_TASKS);
        if (failbackTasksConfig <= 0) {
            failbackTasksConfig = DEFAULT_FAILBACK_TASKS;
        }
        retries = retriesConfig;
        failbackTasks = failbackTasksConfig;
    }

    // 在调用 doInvoke() 方法失败以后，提交一个定时任务在 5s 后执行重试，如果还是失败，之后每 5s 重试一次，最多重试 3 次，如果重试 3 次都失败，记录错误日志，不再重试。
    private void addFailed(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, Invoker<T> lastInvoker) {
        // 1、初始化failTimer定时器
        if (failTimer == null) {
            synchronized (this) {
                if (failTimer == null) {
                    failTimer = new HashedWheelTimer(
                            new NamedThreadFactory("failback-cluster-timer", true),
                            1,
                            TimeUnit.SECONDS, 32, failbackTasks);
                }
            }
        }
        // 2、创建重试任务
        RetryTimerTask retryTimerTask = new RetryTimerTask(loadbalance, invocation, invokers, lastInvoker, retries, RETRY_FAILED_PERIOD);
        try {
            // 3、提交到定时器中
            failTimer.newTimeout(retryTimerTask, RETRY_FAILED_PERIOD, TimeUnit.SECONDS);
        } catch (Throwable e) {
            logger.error("Failback background works error,invocation->" + invocation + ", exception: " + e.getMessage());
        }
    }

    @Override
    protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        Invoker<T> invoker = null;
        try {
            // 1、校验从AbstractClusterInvoker传入的Invoker列表是否为空
            checkInvokers(invokers, invocation);
            // 2、调用负载均衡，选择出某一个invokers
            invoker = select(loadbalance, invocation, invokers, null);
            // 3、使用选出的invoker，执行invoke方法
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            logger.error("Failback to invoke method " + invocation.getMethodName() + ", wait for retry in background. Ignored exception: "
                    + e.getMessage() + ", ", e);

            // 4、记录到失败队列并会在其他重试
            addFailed(loadbalance, invocation, invokers, invoker);

            // 5、这里异常也不会返回给调用端
            return AsyncRpcResult.newDefaultAsyncResult(null, null, invocation); // ignore
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        // 销毁的时候将定时器停止
        if (failTimer != null) {
            failTimer.stop();
        }
    }

    /**
     * 重试定时器任务
     */
    private class RetryTimerTask implements TimerTask {
        private final Invocation invocation;
        private final LoadBalance loadbalance;
        private final List<Invoker<T>> invokers;

        /**
         * 最大重试次数
         */
        private final int retries;

        /**
         * 每隔几秒执行一次
         */
        private final long tick;
        private Invoker<T> lastInvoker;
        private int retryTimes = 0;

        RetryTimerTask(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, Invoker<T> lastInvoker, int retries, long tick) {
            this.loadbalance = loadbalance;
            this.invocation = invocation;
            this.invokers = invokers;
            this.retries = retries;
            this.tick = tick;
            this.lastInvoker=lastInvoker;
        }

        @Override
        public void run(Timeout timeout) {
            try {
                // 1、调用负载均衡，选择出某一个invokers，这里会将上一次调用的Invoker排除掉，具体逻辑在select方法中
                Invoker<T> retryInvoker = select(loadbalance, invocation, invokers, Collections.singletonList(lastInvoker));
                lastInvoker = retryInvoker;
                // 2、使用选出的invoker，执行invoke方法
                retryInvoker.invoke(invocation);
            } catch (Throwable e) {
                logger.error("Failed retry to invoke method " + invocation.getMethodName() + ", waiting again.", e);
                // 3、如果超过了重试次数，则抛出错误信息，并终止重试
                if ((++retryTimes) >= retries) {
                    logger.error("Failed retry times exceed threshold (" + retries + "), We have to abandon, invocation->" + invocation);
                } else {
                    // 4、重新加入到定时器队列中
                    rePut(timeout);
                }
            }
        }

        private void rePut(Timeout timeout) {
            if (timeout == null) {
                return;
            }

            Timer timer = timeout.timer();
            // 如果定时器停止或者被取消了则直接返回
            if (timer.isStop() || timeout.isCancelled()) {
                return;
            }

            // 放入到待执行队列中
            timer.newTimeout(timeout.task(), tick, TimeUnit.SECONDS);
        }
    }
}
