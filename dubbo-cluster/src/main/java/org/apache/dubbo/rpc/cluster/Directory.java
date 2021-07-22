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

import org.apache.dubbo.common.Node;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;

/**
 * Directory. (SPI, Prototype, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Directory_service">Directory Service</a>
 *
 * <p>Directory也有多种实现子类，既可以提供静态的Invoker列表，也可以提供动态的Invoker列表。
 * 静态列表是用户自己设置的Invoker列表；动态列表根据注册中心的数据动态变化，动态更新Invoker列表的数据， 整个过程对上层透明。
 *
 * <p>Directory记录了所有可用的远程服务列表，Directory接口是服务目录的最上层接口。所有服务目录的实现类均要实现该接口。
 *
 * <p>AbstractDirectory 是 Directory 接口的抽象实现，其中除了维护 Consumer 端的 URL 信息，还维护了一个 RouterChain 对象，用于记录当前使用的 Router 对象集合，也就是路由规则。
 *
 * <p>主要的两个实现类为 RegistryDirectory和{@link org.apache.dubbo.rpc.cluster.directory.StaticDirectory}
 *
 * @see org.apache.dubbo.rpc.cluster.Cluster#join(Directory)
 */
public interface Directory<T> extends Node {

    /**
     * 服务接口的Class对象，例如：com.service.DemoService
     *
     * @return service type.
     */
    Class<T> getInterface();

    /**
     * 获得所有可用的服务提供者列表，一个Invoker对象代表了一个远程服务提供者
     *
     * @return invokers
     */
    List<Invoker<T>> list(Invocation invocation) throws RpcException;

    /**
     * 与list方法类似，别是list返回的Invoker需要经过Router的过滤，不符合过滤条件的不返回。
     * getAllInvokers不经过任何处理直接返回当前所有可用的服务提供者列表
     * @return List<Invoker<T>>
     */
    List<Invoker<T>> getAllInvokers();

    /**
     * Consumer端的注册中心信息，例如：
     * zookeeper://192.168.1.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-service&check=false&connect.timeout=10000&
     *             dubbo=2.0.2&init=false&interface=com.service.DemoService&metadata-type=remote&methods=helloWorld&pid=14356&qos.enable=false&
     *             register.ip=10.1.1.1&release=2.7.7&revision=1.0.0&side=consumer&sticky=false&timeout=60000&timestamp=1626770103446&version=3.0.0
     *
     * @return URL
     */
    URL getConsumerUrl();

    /**
     * 是否已被销毁
     * @return boolean
     */
    boolean isDestroyed();

    void discordAddresses();

}