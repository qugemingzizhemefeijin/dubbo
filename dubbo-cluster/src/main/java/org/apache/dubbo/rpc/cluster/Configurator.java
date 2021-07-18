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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.rpc.cluster.Constants.PRIORITY_KEY;

/**
 * <p>Configurator. (SPI, Prototype, ThreadSafe) <br><br>
 *
 * <p>当我们在注册中心的 configurators 目录中添加 override（或 absent）协议的 URL 时，
 * Registry 会收到注册中心的通知，回调注册在其上的 NotifyListener，其中就包括 RegistryDirectory。 <br><br>
 *
 * <p>
 * RegistryDirectory.notify() 处理 providers、configurators 和 routers 目录变更的流程，
 * 其中 configurators 目录下 URL 会被解析成 Configurator 对象。<br><br>
 *
 * <pre>
 * override://0.0.0.0/org.apache.dubbo.demo.DemoService?category=configurators&dynamic=false&enabled=true&application=dubbo-demo-api-consumer&timeout=1000
 *
 * override：表示采用覆盖方式。Dubbo 支持 override 和 absent 两种协议，我们也可以通过 SPI 的方式进行扩展。
 * 0.0.0.0：表示对所有 IP 生效。如果只想覆盖某个特定 IP 的 Provider 配置，可以使用该 Provider 的具体 IP。
 * org.apache.dubbo.demo.DemoService：表示只对指定服务生效。
 * category=configurators：表示该 URL 为动态配置类型。
 * dynamic=false：表示该 URL 为持久数据，即使注册该 URL 的节点退出，该 URL 依旧会保存在注册中心。
 * enabled=true：表示该 URL 的覆盖规则已生效。
 * application=dubbo-demo-api-consumer：表示只对指定应用生效。如果不指定，则默认表示对所有应用都生效。
 * timeout=1000：表示将满足以上条件 Provider URL 中的 timeout 参数值覆盖为 1000。如果想覆盖其他配置，可以直接以参数的形式添加到 override URL 之上。
 * </pre>
 *
 * <p>在 Dubbo 的官网中，还提供了一些简单示例：<br><br>
 *
 * <pre>
 * 禁用某个 Provider，通常用于临时剔除某个 Provider 节点：
 * override://10.20.153.10/com.foo.BarService?category=configurators&dynamic=false&disabled=true
 *
 * 调整某个 Provider 的权重为 200：
 * override://10.20.153.10/com.foo.BarService?category=configurators&dynamic=false&weight=200
 *
 * 调整负载均衡策略为 LeastActiveLoadBalance
 * override://10.20.153.10/com.foo.BarService?category=configurators&dynamic=false&loadbalance=leastactive
 *
 * 服务降级，通常用于临时屏蔽某个出错的非关键服务
 * override://0.0.0.0/com.foo.BarService?category=configurators&dynamic=false&application=foo&mock=force:return+null
 * </pre>
 *
 * <p>
 * 重要的模版类：
 * AbstractConfigurator 中维护了一个 configuratorUrl 字段，记录了完整的配置 URL。AbstractConfigurator 是一个模板类，其核心实现是 configure() 方法。
 *
 * <br><br>
 *
 * <p>比较重要的两个子类对象：
 * <ol>
 * <li>OverrideConfigurator 的 doConfigure() 方法中，会直接用配置 URL 中剩余的全部参数，覆盖原始 URL 中的相应参数。</li>
 * <li>AbsentConfigurator 的 doConfigure() 方法中，会尝试用配置 URL 中的参数添加到原始 URL 中，如果原始 URL 中已经有了该参数是不会被覆盖的。</li>
 * </ol>
 */
public interface Configurator extends Comparable<Configurator> {

    /**
     * Get the configurator url. 获取该Configurator对象对应的配置URL，例如override协议URL
     *
     * @return configurator url.
     */
    URL getUrl();

    /**
     * Configure the provider url.
     *
     * configure()方法接收的参数是原始URL，返回经过Configurator修改后的URL
     *
     * @param url - old provider url.
     * @return new provider url.
     */
    URL configure(URL url);


    /**
     * <p>Convert override urls to map for use when re-refer. Send all rules every time, the urls will be reassembled and
     * calculated
     *
     * <p>可以将多个配置URL对象解析成相应的Configurator对象
     *
     * URL contract:
     * <ol>
     * <li>override://0.0.0.0/...( or override://ip:port...?anyhost=true)&para1=value1... means global rules
     * (all of the providers take effect)</li>
     * <li>override://ip:port...?anyhost=false Special rules (only for a certain provider)</li>
     * <li>override:// rule is not supported... ,needs to be calculated by registry itself</li>
     * <li>override://0.0.0.0/ without parameters means clearing the override</li>
     * </ol>
     *
     * @param urls URL list to convert
     * @return converted configurator list
     */
    static Optional<List<Configurator>> toConfigurators(List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return Optional.empty();
        }

        // 创建ConfiguratorFactory适配器
        ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .getAdaptiveExtension();
        // 记录解析的结果
        List<Configurator> configurators = new ArrayList<>(urls.size());
        for (URL url : urls) {
            // 遇到empty协议，直接清空configurators集合，结束解析，返回空集合
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }
            Map<String, String> override = new HashMap<>(url.getParameters());
            //The anyhost parameter of override may be added automatically, it can't change the judgement of changing url
            override.remove(ANYHOST_KEY);
            // 如果该配置URL没有携带任何参数，则跳过该URL
            if (CollectionUtils.isEmptyMap(override)) {
                continue;
            }
            // 通过ConfiguratorFactory适配器选择合适ConfiguratorFactory扩展，并创建Configurator对象
            configurators.add(configuratorFactory.getConfigurator(url));
        }
        Collections.sort(configurators);
        return Optional.of(configurators);
    }

    /**
     * <p>排序首先按照ip进行排序，所有ip的优先级都高于0.0.0.0，当ip相同时，会按照priority参数值进行排序
     *
     * <p>Sort by host, then by priority
     * 1. the url with a specific host ip should have higher priority than 0.0.0.0
     * 2. if two url has the same host, compare by priority value；
     */
    @Override
    default int compareTo(Configurator o) {
        if (o == null) {
            return -1;
        }

        int ipCompare = getUrl().getHost().compareTo(o.getUrl().getHost());
        // host is the same, sort by priority
        if (ipCompare == 0) {
            int i = getUrl().getParameter(PRIORITY_KEY, 0);
            int j = o.getUrl().getParameter(PRIORITY_KEY, 0);
            return Integer.compare(i, j);
        } else {
            return ipCompare;
        }
    }
}
