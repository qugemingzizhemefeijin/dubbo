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
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LeastActiveLoadBalance，使用的是最小活跃数负载均衡算法。它认为当前活跃请求数越小的 Provider 节点，剩余的处理能力越多，
 * 处理请求的效率也就越高，那么该 Provider 在单位时间内就可以处理更多的请求，所以我们应该优先将请求分配给该 Provider 节点。
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 *
 * <p>
 * 过滤具有最少活动调用数的调用者数量，并计算这些调用者的权重和数量。
 * 如果只有一个调用者，直接使用调用者；
 * 如果有多个调用者且权重不相同，则根据总权重随机；
 * 如果有多个调用者且权重相同，则随机调用。
 *
 * <p>
 * 必须配合reference中的actives参数才能走{@link org.apache.dubbo.rpc.filter.ActiveLimitFilter}来统计活跃数据
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        // 初始化Invoker数量
        int length = invokers.size();
        // The least active value of all invokers
        // 记录最小的活跃请求数
        int leastActive = -1;
        // The number of invokers having the same least active value (leastActive)
        // 记录活跃请求数最小的Invoker集合的个数
        int leastCount = 0;
        // The index of invokers having the same least active value (leastActive)
        // 记录活跃请求数最小的Invoker在invokers数组中的下标位置
        int[] leastIndexes = new int[length];
        // the weight of every invokers
        // 记录活跃请求数最小的Invoker集合中，每个Invoker的权重值
        int[] weights = new int[length];
        // The sum of the warmup weights of all the least active invokers
        // 记录活跃请求数最小的Invoker集合中，所有Invoker的权重值之和
        int totalWeight = 0;
        // The weight of the first least active invoker
        // 记录活跃请求数最小的Invoker集合中，第一个Invoker的权重值
        int firstWeight = 0;
        // Every least active invoker has the same weight value?
        // 活跃请求数最小的集合中，所有Invoker的权重值是否相同
        boolean sameWeight = true;


        // Filter out all the least active invokers
        // 遍历所有Invoker，获取活跃请求数最小的Invoker集合
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            // Get the active number of the invoker 获取当前接口和方法的活跃值
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // Get the weight of the invoker's configuration. The default value is 100.
            int afterWarmup = getWeight(invoker, invocation);
            // save for later use 保存当前服务的权重值
            weights[i] = afterWarmup;
            // If it is the first invoker or the active number of the invoker is less than the current least active number
            // 如果是第一个调用者或者调用者的活跃数小于当前最不活跃的数
            if (leastActive == -1 || active < leastActive) {
                // Reset the active number of the current invoker to the least active number
                // 当前的Invoker是第一个活跃请求数最小的Invoker，则记录如下信息
                // 重新记录最小的活跃请求数
                leastActive = active;
                // Reset the number of least active invokers
                // 重新记录活跃请求数最小的Invoker集合个数
                leastCount = 1;
                // Put the first least active invoker first in leastIndexes
                // 重新记录Invoker
                leastIndexes[0] = i;
                // Reset totalWeight 记录到总权重中
                totalWeight = afterWarmup;
                // Record the weight the first least active invoker 记录第一个最不活跃的调用者的权重（用于下面判断所有一样活跃度的服务是否权重一致）
                firstWeight = afterWarmup;
                // Each invoke has the same weight (only one invoker here)
                // 重新记录是否权重值相等
                sameWeight = true;
                // If current invoker's active value equals with leaseActive, then accumulating.
            } else if (active == leastActive) { // 如果当前调用者的活动值等于leaseActive，则累加。
                // Record the index of the least active invoker in leastIndexes order
                // 记录该Invoker的下标
                leastIndexes[leastCount++] = i;
                // Accumulate the total weight of the least active invoker
                // 更新总权重
                totalWeight += afterWarmup;
                // If every invoker has the same weight?
                if (sameWeight && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // Choose an invoker from all the least active invokers
        // 如果只有一个服务提供者，直接使用调用者
        if (leastCount == 1) {
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexes[0]);
        }
        //如果有多个一样活跃的服务提供者，则根据权重加权随机
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on 
            // totalWeight.
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < leastCount; i++) {
                // 获取服务提供者的索引值
                int leastIndex = leastIndexes[i];
                // 一致累减权重值，为负数，则找到本次的服务提供者
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果权重一样，则直接随机
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}
