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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.loadbalance.RandomLoadBalance;

import java.util.List;

/**
 * LoadBalance. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Load_balancing_(computing)">Load-Balancing</a>
 *
 * <p>Dubbo 提供了 5 种负载均衡实现，分别是：
 * <ol>
 *   <li>基于 Hash 一致性的 {@link org.apache.dubbo.rpc.cluster.loadbalance.ConsistentHashLoadBalance}</li>
 *   <li>基于权重随机算法的 {@link org.apache.dubbo.rpc.cluster.loadbalance.RandomLoadBalance}</li>
 *   <li>基于最少活跃调用数算法的 {@link org.apache.dubbo.rpc.cluster.loadbalance.LeastActiveLoadBalance}</li>
 *   <li>基于加权轮询算法的 {@link org.apache.dubbo.rpc.cluster.loadbalance.RoundRobinLoadBalance}</li>
 *   <li>基于最短响应时间的 {@link org.apache.dubbo.rpc.cluster.loadbalance.ShortestResponseLoadBalance}</li>
 * </ol>
 *
 * <p>LoadBalance 是一个扩展接口，默认使用的扩展实现是 RandomLoadBalance。
 * <p>LoadBalance 接口中 select() 方法的核心功能是根据传入的 URL 和 Invocation，以及自身的负载均衡算法，从 Invoker 集合中选择一个 Invoker 返回。
 * <p>AbstractLoadBalance 抽象类并没有真正实现 select() 方法，只是对 Invoker 集合为空或是只包含一个 Invoker 对象的特殊情况进行了处理
 *
 * @see org.apache.dubbo.rpc.cluster.Cluster#join(Directory)
 */
@SPI(RandomLoadBalance.NAME)
public interface LoadBalance {

    /**
     * select one invoker in list.
     *
     * @param invokers   服务提供者provider列表.
     * @param url        注册中心，消费端的URL，协议表示注册中心类型。如：zookeeper://192.168.1.1:2181/org.apache.dubbo.registry.RegistryService?
     *                   application=demo-service&check=false&connect.timeout=10000&dubbo=2.0.2&init=false&interface=com.service.DemoService&
     *                   metadata-type=remote&methods=helloWorld&pid=14356&qos.enable=false&register.ip=10.1.1.1&
     *                   release=2.7.7&revision=1.0.0&side=consumer&sticky=false&timeout=60000&timestamp=1626770103446&version=3.0.0
     * @param invocation 服务rpc调用相关参数信息
     * @return selected invoker.
     */
    @Adaptive("loadbalance")
    <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException;

}