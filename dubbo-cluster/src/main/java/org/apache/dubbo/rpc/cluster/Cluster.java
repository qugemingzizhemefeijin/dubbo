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

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;

/**
 * Cluster. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Computer_cluster">Cluster</a>
 * <a href="http://en.wikipedia.org/wiki/Fault-tolerant_system">Fault-Tolerant</a>
 *
 * <pre>
 * dubbo一共有九种负载均衡策略：
 *      failover : 当出现失败时，会重试其他服务器。用户可以通过retries=""设置重试次数。这是Dubbo的默认容错机制，会对请求做负载均衡。
 *                 用户可以通过retries属性来设置最大重试次数。可以设置在dubbo: reference标签上，也可以设置在细粒度的方法标签dubbo:method上
 *      failfast : 快速失败，当请求失败后， 快速返回异常结果，不做任何重试。
 *      failsafe : 当出现异常时，直接忽略异常。
 *      failback : 请求失败后，会自动记录在失败队列中，并由一个定时线程池定时重试。
 *      forking : 同时调用多个相同的服务，只要其中一个返回，则立即返回结果。用户可以配置forks=""参数来确定最大并行调用的服务数量。
 *      broadcast : 广播调用所有可用的服务，任意一个节点报错则报错。
 *      mock : 提供调用失败时，返回伪造的响应结果。或直接强制返回伪造的结果，不会发起远程调用
 *      available : 最简单的方式，请求不会做负载均衡，遍历所有服务列表，找到第一个可用的节点，直接请求并返回结果。如果没有可用的节点，则直接抛出异常
 *      mergeable : Mergeable可以自动把多个节点请求得到的结果进行合并。dubbo:reference标签中通过merger="true"开启，合并时可以通过group="*"属性指定需要合并哪些分组的结果。
 * </pre>
 *
 * 这里可以看出默认的负载均衡策略是failover <br>
 *
 * Cluster接口下面有多种不同的实现， 每种实现中都需要实现接口的join方法， 在方法中会“new”一个对应的Clusterinvoker实现。
 *
 */
@SPI(Cluster.DEFAULT)
public interface Cluster {

    String DEFAULT = "failover";

    /**
     * Merge the directory invokers to a virtual invoker.
     *
     * 基于 Directory ，创建 Invoker 对象，实现统一、透明的 Invoker 调用过程 <br><br>
     *
     * 在RegistryProtocol的doRefer(Cluster, Registry, type, url)方法中会调用Cluster#join(directory) 方法，创建 Invoker 对象。
     *
     * @param <T>       泛型
     * @param directory Directory 对象
     * @return cluster invoker
     * @throws RpcException
     */
    @Adaptive
    <T> Invoker<T> join(Directory<T> directory) throws RpcException;

    /**
     * 从ExtensionLoader中根据名称获取Cluster实现
     * @param name 名称
     * @return Cluster
     */
    static Cluster getCluster(String name) {
        return getCluster(name, true);
    }

    /**
     * 从ExtensionLoader中根据名称获取Cluster实现
     * @param name 名称
     * @param wrap 如果未找到，是否需要封装包装类，此参数好像也就是这里用到了
     * @return Cluster
     */
    static Cluster getCluster(String name, boolean wrap) {
        if (StringUtils.isEmpty(name)) {
            name = Cluster.DEFAULT;
        }
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension(name, wrap);
    }
}
