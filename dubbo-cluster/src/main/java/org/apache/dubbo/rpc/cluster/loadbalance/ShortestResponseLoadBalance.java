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
 * ShortestResponseLoadBalance
 * </p>
 * Filter the number of invokers with the shortest response time of success calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 * <br><br>
 * <p>
 * 过滤成功调用响应时间最短的调用者数量，统计这些调用者的权重和数量。必须配合reference中的actives参数才能走{@link org.apache.dubbo.rpc.filter.ActiveLimitFilter}来统计活跃数据。
 * <ol>
 * <li>如果只有一个调用者，直接使用调用者；</li>
 * <li>如果有多个调用者且权重不相同，则根据总权重随机；</li>
 * <li>如果有多个调用者且权重相同，则随机调用。</li>
 * </ol>
 *
 * <p>
 * Dubbo 2.7 版本之后新增加的一个 LoadBalance 实现类。它实现了最短响应时间的负载均衡算法，也就是从多个 Provider 节点中选出调用成功的且响应时间最短的 Provider 节点，
 * 不过满足该条件的 Provider 节点可能有多个，所以还要再使用随机算法进行一次选择，得到最终要调用的 Provider 节点。
 *
 * <p>
 * 选择 Provider历史请求处理成功的平均耗时 * 当前活跃请求数 的 最小值，如果有多个一样的最小值，则根据权重随机选择。
 */
public class ShortestResponseLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "shortestresponse";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        // 记录Invoker集合的数量
        int length = invokers.size();
        // Estimated shortest response time of all invokers
        // 用于记录所有Invoker集合中最短响应时间
        long shortestResponse = Long.MAX_VALUE;
        // The number of invokers having the same estimated shortest response time
        // 具有相同最短响应时间的Invoker个数
        int shortestCount = 0;
        // The index of invokers having the same estimated shortest response time
        // 存放所有最短响应时间的Invoker的下标
        int[] shortestIndexes = new int[length];
        // the weight of every invokers
        // 存储每个Invoker的权重
        int[] weights = new int[length];
        // The sum of the warmup weights of all the shortest response  invokers
        // 存储权重总和
        int totalWeight = 0;
        // The weight of the first shortest response invokers
        // 记录第一个Invoker对象的权重
        int firstWeight = 0;
        // Every shortest response invoker has the same weight value?
        // 最短响应时间Invoker集合中的Invoker权重是否相同
        boolean sameWeight = true;

        // Filter out all the shortest response invokers
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            RpcStatus rpcStatus = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
            // Calculate the estimated response time from the product of active connections and succeeded average elapsed time.
            // 获取调用成功的平均时间，具体计算方式是：调用成功的请求数总数对应的总耗时 / 调用成功的请求数总数 = 成功调用的平均时间
            long succeededAverageElapsed = rpcStatus.getSucceededAverageElapsed();
            // 获取的是该Provider当前的活跃请求数，也就是当前正在处理的请求数
            int active = rpcStatus.getActive();
            // 计算一个处理新请求的预估值，也就是如果当前请求发给这个Provider，大概耗时多久处理完成
            long estimateResponse = succeededAverageElapsed * active;
            // 计算该Invoker的权重（主要是处理预热）
            int afterWarmup = getWeight(invoker, invocation);
            weights[i] = afterWarmup;
            // Same as LeastActiveLoadBalance
            if (estimateResponse < shortestResponse) {
                // 第一次找到Invoker集合中最短响应耗时的Invoker对象，记录其相关信息
                shortestResponse = estimateResponse;
                shortestCount = 1;
                shortestIndexes[0] = i;
                totalWeight = afterWarmup;
                firstWeight = afterWarmup;
                sameWeight = true;
            } else if (estimateResponse == shortestResponse) {
                // 出现多个耗时最短的Invoker对象
                shortestIndexes[shortestCount++] = i;
                totalWeight += afterWarmup;
                if (sameWeight && i > 0 && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        if (shortestCount == 1) {
            return invokers.get(shortestIndexes[0]);
        }
        // 如果耗时最短的所有Invoker对象的权重不相同，则通过加权随机负载均衡的方式选择一个Invoker返回
        if (!sameWeight && totalWeight > 0) {
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            for (int i = 0; i < shortestCount; i++) {
                int shortestIndex = shortestIndexes[i];
                offsetWeight -= weights[shortestIndex];
                if (offsetWeight < 0) {
                    return invokers.get(shortestIndex);
                }
            }
        }
        // 如果耗时最短的所有Invoker对象的权重相同，则随机返回一个
        return invokers.get(shortestIndexes[ThreadLocalRandom.current().nextInt(shortestCount)]);
    }
}
