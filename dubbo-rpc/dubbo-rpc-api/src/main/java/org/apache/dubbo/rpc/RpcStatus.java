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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * URL statistics. (API, Cached, ThreadSafe)
 *
 * @see org.apache.dubbo.rpc.filter.ActiveLimitFilter
 * @see org.apache.dubbo.rpc.filter.ExecuteLimitFilter
 * @see org.apache.dubbo.rpc.cluster.loadbalance.LeastActiveLoadBalance
 */
public class RpcStatus {

    /**
     * 记录了当前 Consumer 调用每个服务的状态信息，其中 Key 是 URL（dubbo://127.0.0.1:20801/com.service.DemoService），Value 是对应的 RpcStatus 对象；
     */
    private static final ConcurrentMap<String, RpcStatus> SERVICE_STATISTICS = new ConcurrentHashMap<String,
            RpcStatus>();

    /**
     * 当前 Consumer 调用每个服务方法的状态信息，其中第一层 Key 是 URL（dubbo://127.0.0.1:20801/com.service.DemoService） ，
     * 第二层 Key 是方法名称，第三层是对应的 RpcStatus 对象。
     */
    private static final ConcurrentMap<String, ConcurrentMap<String, RpcStatus>> METHOD_STATISTICS =
            new ConcurrentHashMap<String, ConcurrentMap<String, RpcStatus>>();

    private final ConcurrentMap<String, Object> values = new ConcurrentHashMap<String, Object>();

    /**
     * 当前服务或服务方法的并发数（也即还在进行的远程调用），具体看上面的see相关的类。这也是 ActiveLimitFilter 中关注的并发度。
     */
    private final AtomicInteger active = new AtomicInteger();

    /**
     * 调用的总数
     */
    private final AtomicLong total = new AtomicLong();

    /**
     * 失败的调用数
     */
    private final AtomicInteger failed = new AtomicInteger();

    /**
     * 所有调用的总耗时
     */
    private final AtomicLong totalElapsed = new AtomicLong();

    /**
     * 所有失败调用的总耗时
     */
    private final AtomicLong failedElapsed = new AtomicLong();

    /**
     * 所有调用中最长的耗时
     */
    private final AtomicLong maxElapsed = new AtomicLong();

    /**
     * 所有失败调用中最长的耗时
     */
    private final AtomicLong failedMaxElapsed = new AtomicLong();

    /**
     * 所有成功调用中最长的耗时
     */
    private final AtomicLong succeededMaxElapsed = new AtomicLong();

    private RpcStatus() {
    }

    /**
     * @param url 消费者请求的服务URL
     * @return status
     */
    public static RpcStatus getStatus(URL url) {
        // dubbo://127.0.0.1:20801/com.service.DemoService
        String uri = url.toIdentityString();
        return SERVICE_STATISTICS.computeIfAbsent(uri, key -> new RpcStatus());
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url) {
        String uri = url.toIdentityString();
        SERVICE_STATISTICS.remove(uri);
    }

    /**
     * @param url        传入的被调用服务的URL，例如：
     *                   dubbo://127.0.0.1:20801/com.service.DemoService?actives=1&anyhost=true&application=demo-service-consumer&
     *                   check=false&connect.timeout=50000&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&init=false&
     *                   interface=com.service.DemoService&metadata-type=remote&methods=helloWorld,sayHello&pid=2400&qos.enable=false&
     *                   register.ip=192.168.41.68&release=2.7.7&remote.application=demo-service=provider&retries=0&revision=3.0.0&
     *                   helloWorld.timeout=3600000&side=consumer&sticky=false&timeout=600000&timestamp=1626676432457&version=3.0.0
     * @param methodName 调用接口的方法名称，如：helloWorld
     * @return RpcStatus
     */
    public static RpcStatus getStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.computeIfAbsent(uri, k -> new ConcurrentHashMap<>());
        return map.computeIfAbsent(methodName, k -> new RpcStatus());
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map != null) {
            map.remove(methodName);
        }
    }

    /**
     * beginCount() 方法会在远程调用开始之前执行，其中会从 SERVICE_STATISTICS 集合和 METHOD_STATISTICS 集合中获取服务和服务方法对应的 RpcStatus 对象，然后分别将它们的 active 字段加一。
     *
     * @param url        消费方调用的服务，dubbo://127.0.0.1:20801/com.service.DemoService?xxx
     * @param methodName 调用的方法名称
     */
    public static void beginCount(URL url, String methodName) {
        beginCount(url, methodName, Integer.MAX_VALUE);
    }

    /**
     * beginCount() 方法会在远程调用开始之前执行，其中会从 SERVICE_STATISTICS 集合和 METHOD_STATISTICS 集合中获取服务和服务方法对应的 RpcStatus 对象，然后分别将它们的 active 字段加一。
     *
     * @param url        消费方调用的服务，dubbo://127.0.0.1:20801/com.service.DemoService?xxx
     * @param methodName 调用的方法名称
     * @param max        最大并发数，配置的actives参数值
     * @return boolean 并发数是否被成功+1
     */
    public static boolean beginCount(URL url, String methodName, int max) {
        max = (max <= 0) ? Integer.MAX_VALUE : max;
        // 获取服务对应的RpcStatus对象
        RpcStatus appStatus = getStatus(url);
        // 获取服务方法对应的RpcStatus对象
        RpcStatus methodStatus = getStatus(url, methodName);
        // 并发度溢出
        if (methodStatus.active.get() == Integer.MAX_VALUE) {
            return false;
        }
        for (int i; ; ) {
            i = methodStatus.active.get();
            // 并发度超过max上限，直接返回false
            if (i == Integer.MAX_VALUE || i + 1 > max) {
                return false;
            }
            // CAS操作
            if (methodStatus.active.compareAndSet(i, i + 1)) {
                // 更新成功后退出当前循环
                break;
            }
        }

        // 单个服务的并发度加一
        appStatus.active.incrementAndGet();

        return true;
    }

    /**
     * 完成调用监控的统计
     * @param url       消费端的调用服务的URL，具体描述是调用接口的方法，超时，协议等等信息
     * @param elapsed   调用耗时，毫秒
     * @param succeeded 是否调用成功
     */
    public static void endCount(URL url, String methodName, long elapsed, boolean succeeded) {
        endCount(getStatus(url), elapsed, succeeded);
        endCount(getStatus(url, methodName), elapsed, succeeded);
    }

    /**
     * 完成调用监控的统计
     * @param status    RpcStatus
     * @param elapsed   调用耗时
     * @param succeeded 是否调用成功
     */
    private static void endCount(RpcStatus status, long elapsed, boolean succeeded) {
        // 请求完成，降低并发度
        status.active.decrementAndGet();
        // 调用总次数增加
        status.total.incrementAndGet();
        // 调用总耗时增加
        status.totalElapsed.addAndGet(elapsed);
        // 更新最大耗时
        if (status.maxElapsed.get() < elapsed) {
            status.maxElapsed.set(elapsed);
        }

        if (succeeded) {
            // 如果此次调用成功，则会更新成功调用的最大耗时
            if (status.succeededMaxElapsed.get() < elapsed) {
                status.succeededMaxElapsed.set(elapsed);
            }

        } else {
            // 如果此次调用失败，则会更新失败调用的最大耗时
            status.failed.incrementAndGet();
            status.failedElapsed.addAndGet(elapsed);
            if (status.failedMaxElapsed.get() < elapsed) {
                status.failedMaxElapsed.set(elapsed);
            }
        }
    }

    /**
     * set value.
     *
     * @param key
     * @param value
     */
    public void set(String key, Object value) {
        values.put(key, value);
    }

    /**
     * get value.
     *
     * @param key
     * @return value
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * get active.
     *
     * @return active
     */
    public int getActive() {
        return active.get();
    }

    /**
     * get total.
     *
     * @return total
     */
    public long getTotal() {
        return total.longValue();
    }

    /**
     * get total elapsed.
     *
     * @return total elapsed
     */
    public long getTotalElapsed() {
        return totalElapsed.get();
    }

    /**
     * get average elapsed.
     *
     * @return average elapsed
     */
    public long getAverageElapsed() {
        long total = getTotal();
        if (total == 0) {
            return 0;
        }
        return getTotalElapsed() / total;
    }

    /**
     * get max elapsed.
     *
     * @return max elapsed
     */
    public long getMaxElapsed() {
        return maxElapsed.get();
    }

    /**
     * get failed.
     *
     * @return failed
     */
    public int getFailed() {
        return failed.get();
    }

    /**
     * get failed elapsed.
     *
     * @return failed elapsed
     */
    public long getFailedElapsed() {
        return failedElapsed.get();
    }

    /**
     * get failed average elapsed.
     *
     * @return failed average elapsed
     */
    public long getFailedAverageElapsed() {
        long failed = getFailed();
        if (failed == 0) {
            return 0;
        }
        return getFailedElapsed() / failed;
    }

    /**
     * get failed max elapsed.
     *
     * @return failed max elapsed
     */
    public long getFailedMaxElapsed() {
        return failedMaxElapsed.get();
    }

    /**
     * get succeeded.
     *
     * @return succeeded
     */
    public long getSucceeded() {
        return getTotal() - getFailed();
    }

    /**
     * get succeeded elapsed.
     *
     * @return succeeded elapsed
     */
    public long getSucceededElapsed() {
        return getTotalElapsed() - getFailedElapsed();
    }

    /**
     * get succeeded average elapsed.
     *
     * @return succeeded average elapsed
     */
    public long getSucceededAverageElapsed() {
        long succeeded = getSucceeded();
        if (succeeded == 0) {
            return 0;
        }
        return getSucceededElapsed() / succeeded;
    }

    /**
     * get succeeded max elapsed.
     *
     * @return succeeded max elapsed.
     */
    public long getSucceededMaxElapsed() {
        return succeededMaxElapsed.get();
    }

    /**
     * Calculate average TPS (Transaction per second).
     *
     * @return tps
     */
    public long getAverageTps() {
        if (getTotalElapsed() >= 1000L) {
            return getTotal() / (getTotalElapsed() / 1000L);
        }
        return getTotal();
    }


}
