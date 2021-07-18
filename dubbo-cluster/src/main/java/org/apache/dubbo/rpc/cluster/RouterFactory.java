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

/**
 * RouterFactory. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Routing">Routing</a>
 *
 * <p>
 * Note Router has a different behaviour since 2.7.0, for each type of Router, there will only has one Router instance
 * for each service. See {@link CacheableRouterFactory} and {@link RouterChain} for how to extend a new Router or how
 * the Router instances are loaded.
 *
 * <p>
 * Router 决定了一次 Dubbo 调用的目标服务，Router 接口的每个实现类代表了一个路由规则，当 Consumer 访问 Provider 时，
 * Dubbo 根据路由规则筛选出合适的 Provider 列表，之后通过负载均衡算法再次进行筛选。
 *
 * <pre>
 * file=org.apache.dubbo.rpc.cluster.router.file.FileRouterFactory
 * condition=org.apache.dubbo.rpc.cluster.router.condition.ConditionRouterFactory
 * service=org.apache.dubbo.rpc.cluster.router.condition.config.ServiceRouterFactory
 * app=org.apache.dubbo.rpc.cluster.router.condition.config.AppRouterFactory
 * tag=org.apache.dubbo.rpc.cluster.router.tag.TagRouterFactory
 * mock=org.apache.dubbo.rpc.cluster.router.mock.MockRouterFactory
 * address=org.apache.dubbo.rpc.cluster.router.address.AddressRouterFactory
 * </pre>
 *
 * Adaptive Factory Class
 * <pre>
 * public class RouterFactory$Adaptive implements RouterFactory {
 *     public Router getRouter(URL arg0) {
 *         if (arg0 == null) throw new IllegalArgumentException("url == null");
 *         URL url = arg0;
 *         String extName = url.getProtocol();
 *         if (extName == null)
 *             throw new IllegalStateException("Failed to get extension (RouterFactory) name from url (" + url.toString() + ") use keys([protocol])");
 *         RouterFactory extension = (RouterFactory) ExtensionLoader.getExtensionLoader(RouterFactory.class).getExtension(extName);
 *         return extension.getRouter(arg0);
 *     }
 * }
 * </pre>
 *
 * @see org.apache.dubbo.rpc.cluster.Cluster#join(Directory)
 * @see org.apache.dubbo.rpc.cluster.Directory#list(org.apache.dubbo.rpc.Invocation)
 */
@SPI
public interface RouterFactory {

    /**
     * Create router.
     * Since 2.7.0, most of the time, we will not use @Adaptive feature, so it's kept only for compatibility.
     *
     * 动态生成的适配器会根据protocol参数选择扩展实现
     *
     * @param url consumer消费方URL
     * @return router instance
     */
    @Adaptive("protocol")
    Router getRouter(URL url);
}
