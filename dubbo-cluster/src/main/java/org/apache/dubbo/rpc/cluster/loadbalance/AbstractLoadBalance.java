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
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_SERVICE_REFERENCE_PATH;
import static org.apache.dubbo.rpc.cluster.Constants.*;

/**
 * AbstractLoadBalance
 */
public abstract class AbstractLoadBalance implements LoadBalance {

    /**
     * <p>Calculate the weight according to the uptime proportion of warmup time
     * the new weight will be within 1(inclusive) to weight(inclusive)
     *
     * <p>目的是对还在预热状态的 Provider 节点进行降权，避免 Provider 一启动就有大量请求涌进来。
     * 服务预热是一个优化手段，这是由 JVM 本身的一些特性决定的，
     * 例如，JIT 等方面的优化，我们一般会在服务启动之后，让其在小流量状态下运行一段时间，然后再逐步放大流量。
     *
     * @param uptime the uptime in milliseconds
     * @param warmup the warmup time in milliseconds
     * @param weight the weight of an invoker
     * @return weight which takes warmup into account
     */
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        // 计算权重，随着服务运行时间uptime增大，权重ww的值会慢慢接近配置值weight
        int ww = (int) ( uptime / ((float) warmup / weight));
        return ww < 1 ? 1 : (Math.min(ww, weight));
    }

    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            // Invoker集合为空，直接返回null
            return null;
        }
        if (invokers.size() == 1) {
            // Invoker集合只包含一个Invoker，则直接返回该Invoker对象
            return invokers.get(0);
        }
        // Invoker集合包含多个Invoker对象时，交给doSelect()方法处理，这是个抽象方法，留给子类具体实现
        return doSelect(invokers, url, invocation);
    }

    /**
     * 留给具体子类实现的负载均衡策略（随机、一致性hash、加权轮询、最少活跃、最短响应时间等）
     * @param invokers   服务提供者provider列表.
     * @param url        注册中心，消费端的URL，协议表示注册中心类型。如：zookeeper://192.168.1.1:2181/org.apache.dubbo.registry.RegistryService?
     *                   application=demo-service&check=false&connect.timeout=10000&dubbo=2.0.2&init=false&interface=com.service.DemoService&
     *                   metadata-type=remote&methods=helloWorld&pid=14356&qos.enable=false&register.ip=10.1.1.1&
     *                   release=2.7.7&revision=1.0.0&side=consumer&sticky=false&timeout=60000&timestamp=1626770103446&version=3.0.0
     * @param invocation 服务rpc调用相关参数信息
     * @return Invoker<T>
     */
    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);


    /**
     * Get the weight of the invoker's invocation which takes warmup time into account
     * if the uptime is within the warmup time, the weight will be reduce proportionally
     *
     * @param invoker    the invoker
     * @param invocation the invocation of this invoker
     * @return weight
     */
    int getWeight(Invoker<?> invoker, Invocation invocation) {
        int weight;
        URL url = invoker.getUrl();
        // Multiple registry scenario, load balance among multiple registries.
        if (REGISTRY_SERVICE_REFERENCE_PATH.equals(url.getServiceInterface())) {
            // 如果是RegistryService接口的话，直接获取权重即可
            weight = url.getParameter(REGISTRY_KEY + "." + WEIGHT_KEY, DEFAULT_WEIGHT);
        } else {
            weight = url.getMethodParameter(invocation.getMethodName(), WEIGHT_KEY, DEFAULT_WEIGHT);
            if (weight > 0) {
                // 获取服务提供者的启动时间戳
                long timestamp = invoker.getUrl().getParameter(TIMESTAMP_KEY, 0L);
                if (timestamp > 0L) {
                    // 计算Provider运行时长
                    long uptime = System.currentTimeMillis() - timestamp;
                    if (uptime < 0) {
                        return 1;
                    }
                    // 计算Provider预热时长
                    int warmup = invoker.getUrl().getParameter(WARMUP_KEY, DEFAULT_WARMUP);
                    // 如果Provider运行时间小于预热时间，则该Provider节点可能还在预热阶段，
                    // 需要重新计算服务权重(降低其权重)
                    if (uptime > 0 && uptime < warmup) {
                        weight = calculateWarmupWeight((int)uptime, warmup, weight);
                    }
                }
            }
        }
        return Math.max(weight, 0);
    }
}
