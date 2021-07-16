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
package org.apache.dubbo.common;

/**
 * Node. (API/SPI, Prototype, ThreadSafe)
 *
 * Node接口是一个顶级接口，像 Registry、Monitor、Invoker 、Directory等均继承了这个接口
 *
 */
public interface Node {

    /**
     * 获得URL对象，
     * 以RegistryDirectory为例，
     * URL对象是由客户端配置信息组成的，IP和端口是注册中心地址，协议表示注册中心类型
     *
     * @return url.
     */
    URL getUrl();

    /**
     * 判断当前对象是否有效，
     * 以RegistryDirectory为例，
     * 如果返回false表示已经调用过destroy方法或者RegistryDirectory保存的所有服务提供者都不可用
     *
     * @return available.
     */
    boolean isAvailable();

    /**
     * 销毁当前对象，以RegistryDirectory为例，
     * 断开与服务提供者的连接，设置是否销毁属性为true，从注册中心上删除注册信息
     */
    void destroy();

}