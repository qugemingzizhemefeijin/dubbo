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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 *
 * 使用的负载均衡算法是加权随机算法。RandomLoadBalance 是一个简单、高效的负载均衡实现，它也是 Dubbo 默认使用的 LoadBalance 实现。
 *
 * This class select one provider from multiple providers randomly.
 * You can define weights for each provider:
 * If the weights are all the same then it will use random.nextInt(number of invokers).
 * If the weights are different then it will use random.nextInt(w1 + w2 + ... + wn)
 * Note that if the performance of the machine is better than others, you can set a larger weight.
 * If the performance is not so good, you can set a smaller weight.
 *
 * <p>此类从多个提供程序中随机选择一个提供程序。
 * <ol>
 * <li>您可以为每个提供者定义权重： 如果权重都相同，那么它将使用 random.nextInt(number of invokers)。</li>
 * <li>如果权重不同，则使用random.nextInt(w1 + w2 + ... + wn)</li>
 * <li>注意，如果机器的性能比其他机器好，可以设置更大的权重。</li>
 * <li>如果性能不是很好，可以设置较小的权重。</li>
 * </ol>
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    /**
     * Select one invoker between a list using a random criteria
     * @param invokers List of possible invokers
     * @param url URL
     * @param invocation Invocation
     * @param <T>
     * @return The selected invoker
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        int length = invokers.size();
        // Every invoker has the same weight?
        // 此值用来判断是否所有的服务都是一样的权重
        boolean sameWeight = true;
        // the maxWeight of every invokers, the minWeight = 0 or the maxWeight of the last invoker
        // 计算每个Invoker对象对应的权重，并填充到weights[]数组中
        int[] weights = new int[length];
        // The sum of weights
        int totalWeight = 0;
        for (int i = 0; i < length; i++) {
            // 计算每个Invoker的权重，以及总权重totalWeight
            int weight = getWeight(invokers.get(i), invocation);
            // Sum
            totalWeight += weight;
            // save for later use
            weights[i] = totalWeight;
            // 检测每个Provider的权重是否相同
            if (sameWeight && totalWeight != weight * (i + 1)) {
                sameWeight = false;
            }
        }

        // 如果权重不一样，则随机累计权重值，然后通过循环来查找其定位到的服务索引。
        // weights数组记录的0-n之间的服务的权重值，一直会累计记录。随机一个值，循环判断小于此索引的第一个服务，则命中
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            // 随机获取一个[0, totalWeight) 区间内的数字
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            // 循环让offset数减去Invoker的权重值，当offset小于0时，返回相应的Invoker
            for (int i = 0; i < length; i++) {
                if (offset < weights[i]) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果所有权重一样，则直接随机服务列表数即可。
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

}
