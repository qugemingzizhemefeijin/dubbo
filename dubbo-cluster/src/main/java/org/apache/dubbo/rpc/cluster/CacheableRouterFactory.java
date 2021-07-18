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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * If you want to provide a router implementation based on design of v2.7.0, please extend from this abstract class.
 * For 2.6.x style router, please implement and use RouterFactory directly.
 *
 * CacheableRouterFactory 抽象类中维护了一个 ConcurrentMap 集合（routerMap 字段）用来缓存 Router，
 * 其中的 Key 是 ServiceKey。在 CacheableRouterFactory 的 getRouter() 方法中，
 * 会优先根据 URL 的 ServiceKey 查询 routerMap 集合，查询失败之后会调用 createRouter() 抽象方法来创建相应的 Router 对象。
 * 在 TagRouterFactory.createRouter() 方法中，创建的自然就是 TagRouter 对象了。
 *
 */
public abstract class CacheableRouterFactory implements RouterFactory {
    private ConcurrentMap<String, Router> routerMap = new ConcurrentHashMap<>();

    @Override
    public Router getRouter(URL url) {
        return routerMap.computeIfAbsent(url.getServiceKey(), k -> createRouter(url));
    }

    protected abstract Router createRouter(URL url);
}
