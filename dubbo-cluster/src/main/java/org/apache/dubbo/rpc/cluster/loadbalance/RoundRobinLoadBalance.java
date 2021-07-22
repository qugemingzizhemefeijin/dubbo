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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance. 加权轮询算法
 *
 * <p>
 * 每个 Provider 节点有两个权重：一个权重是配置的 weight，该值在负载均衡的过程中不会变化；另一个权重是 currentWeight，该值会在负载均衡的过程中动态调整，初始值为 0。
 *
 * <p>
 * 在 RoundRobinLoadBalance 中，为每个 Invoker 对象创建了一个对应的 WeightedRoundRobin 对象，
 * 用来记录配置的权重（weight 字段）以及随每次负载均衡算法执行变化的 current 权重（current 字段）。
 *
 * <p>
 * 加权之后，分配给每个 Provider 节点的流量比会接近或等于它们的权重比。例如，Provider 节点 A、B、C 权重比为 5:1:1，那么在 7 次请求中，节点 A 将收到 5 次请求，节点 B 会收到 1 次请求，节点 C 则会收到 1 次请求。
 *
 * <p>举例：<br>
 * | 请求编号 | currentWeight数组 | 选择结果 | 减去权重总和后的currentWeight数组 |<br>
 * |    1   |     [5, 1, 1]    |    A    |          [-2, 1, 1]           |<br>
 * |    2   |     [3, 2, 2]    |    A    |          [-4, 2, 2]           |<br>
 * |    3   |     [1, 3, 3]    |    A    |          [ 1,-4, 3]           |<br>
 * |    4   |     [6,-3, 4]    |    A    |          [-1,-3, 4]           |<br>
 * |    5   |     [4,-2, 5]    |    A    |          [ 4,-2,-2]           |<br>
 * |    6   |     [9,-1,-1]    |    A    |          [ 2,-1,-1]           |<br>
 * |    7   |     [7, 0, 0]    |    A    |          [ 0, 0, 0]           |<br>
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "roundrobin";

    private static final int RECYCLE_PERIOD = 60000;

    protected static class WeightedRoundRobin {

        /**
         * 服务提供者原始权重
         */
        private int weight;

        /**
         * 服务提供者当前权重
         */
        private AtomicLong current = new AtomicLong(0);

        /**
         * 最后一次更新时间，用于清理无效的提供者的数据
         */
        private long lastUpdate;

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }

        public long increaseCurrent() {
            return current.addAndGet(weight);
        }

        public void sel(int total) {
            current.addAndGet(-1 * total);
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    /**
     * key=com.service.DemoService:3.0.0.helloWorld
     * value=ConcurrentMap<String, WeightedRoundRobin>
     *
     * v-key=dubbo://127.0.0.1:20801/com.service.DemoService
     * v-value=记录的权重信息
     */
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();

    /**
     * get invoker addr list cached for specified invocation，获取调用指定接口方法的调用者地址列表，测试专用
     * <p>
     * <b>for unit test only</b>
     *
     * @param invokers   服务提供者provider列表
     * @param invocation 服务rpc调用相关参数信息
     * @return Collection<String> 返回服务提供者的serviceKey列表信息
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // com.service.DemoService:3.0.0.helloWorld
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        // 获取整个Invoker列表对应的WeightedRoundRobin映射表，如果为空，则创建一个新的WeightedRoundRobin映射表
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        int totalWeight = 0;
        long maxCurrent = Long.MIN_VALUE;
        // 获取当前时间
        long now = System.currentTimeMillis();
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;
        for (Invoker<T> invoker : invokers) {
            // dubbo://127.0.0.1:20801/com.service.DemoService
            String identifyString = invoker.getUrl().toIdentityString();
            int weight = getWeight(invoker, invocation);
            // 记录每个调用接口和方法下服务提供者的权重信息到map中，key=服务提供者协议IP等信息,value=WeightedRoundRobin
            WeightedRoundRobin weightedRoundRobin = map.computeIfAbsent(identifyString, k -> {
                WeightedRoundRobin wrr = new WeightedRoundRobin();
                wrr.setWeight(weight);
                return wrr;
            });
            // 检测Invoker权重是否发生了变化，若发生变化，则更新WeightedRoundRobin的weight字段
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            // 对每个服务提供者增加其权重值，让currentWeight加上配置的Weight
            long cur = weightedRoundRobin.increaseCurrent();
            // 设置lastUpdate字段
            weightedRoundRobin.setLastUpdate(now);
            // 寻找具有最大currentWeight的Invoker，以及Invoker对应的WeightedRoundRobin
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            // 计算权重总和
            totalWeight += weight;
        }
        // 如果服务提供者与map中记录的服务提供者数量不一致，将移除超过1小时未参与计算的提供者map，防止内存泄漏。
        if (invokers.size() != map.size()) {
            map.entrySet().removeIf(item -> now - item.getValue().getLastUpdate() > RECYCLE_PERIOD);
        }
        if (selectedInvoker != null) {
            // 用currentWeight减去totalWeight
            selectedWRR.sel(totalWeight);
            // 返回选中的Invoker对象
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
