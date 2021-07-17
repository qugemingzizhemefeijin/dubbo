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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;
import org.apache.dubbo.registry.client.migration.InvokersChangedListener;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.RouterChain;
import org.apache.dubbo.rpc.cluster.RouterFactory;
import org.apache.dubbo.rpc.cluster.directory.AbstractDirectory;

import java.util.Collections;
import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.registry.Constants.SIMPLIFIED_KEY;
import static org.apache.dubbo.registry.integration.RegistryProtocol.DEFAULT_REGISTER_CONSUMER_KEYS;
import static org.apache.dubbo.remoting.Constants.CHECK_KEY;


/**
 * RegistryDirectory
 */
public abstract class DynamicDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(DynamicDirectory.class);

    /**
     * 集群策略适配器，这里通过 Dubbo SPI 方式动态创建适配器实例
     */
    protected static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * 路由工厂适配器，也是通过 Dubbo SPI 动态创建的适配器实例。routerFactory 字段和 cluster 字段都是静态字段，多个 RegistryDirectory 对象通用。
     */
    protected static final RouterFactory ROUTER_FACTORY = ExtensionLoader.getExtensionLoader(RouterFactory.class).getAdaptiveExtension();

    /**
     * 服务对应的 ServiceKey，默认是 {interface}:[group]:[version] 三部分构成。
     */
    protected final String serviceKey; // Initialization at construction time, assertion not null

    /**
     * 服务接口类型，例如，org.apache.dubbo.demo.DemoService
     */
    protected final Class<T> serviceType; // Initialization at construction time, assertion not null

    /**
     * 只保留 Consumer 属性的 URL，也就是由 queryMap 集合重新生成的 URL
     */
    protected final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    /**
     * 是否引用多个服务组
     */
    protected final boolean multiGroup;

    /**
     * 使用的 Protocol 实现
     */
    protected Protocol protocol; // Initialization at the time of injection, the assertion is not null

    /**
     * 使用的注册中心实现
     */
    protected Registry registry; // Initialization at the time of injection, the assertion is not null
    protected volatile boolean forbidden = false;
    protected boolean shouldRegister;
    protected boolean shouldSimplified;

    protected volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    protected volatile URL registeredConsumerUrl;

    /**
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     *
     * 动态更新的配置信息，初始值为null，中间可能赋值为null，请使用局部变量引用。
     */
    protected volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    /**
     * 动态更新的 Invoker 集合
     */
    protected volatile List<Invoker<T>> invokers;
    // Set<invokerUrls> cache invokeUrls to invokers mapping.

    protected ServiceInstancesChangedListener serviceListener;

    /**
     * serviceType是DubboReference的服务接口类型
     * @param serviceType Class
     * @param url URL，看下面的注释
     */
    public DynamicDirectory(Class<T> serviceType, URL url) {
        // 传入的url参数是注册中心的URL，例如，zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?...，
        // 其中refer参数包含了Consumer信息，例如，refer=application=dubbo-demo-api-consumer&dubbo=2.0.2&interface=org.apache.dubbo.demo.DemoService&pid=13423&register.ip=192.168.124.3&side=consumer(URLDecode之后的值)
        super(url, true);

        if (serviceType == null) {
            throw new IllegalArgumentException("service type is null.");
        }

        if (url.getServiceKey() == null || url.getServiceKey().length() == 0) {
            throw new IllegalArgumentException("registry serviceKey is null.");
        }

        // 解析refer参数值，得到其中Consumer的属性信息
        this.shouldRegister = !ANY_VALUE.equals(url.getServiceInterface()) && url.getParameter(REGISTER_KEY, true);
        this.shouldSimplified = url.getParameter(SIMPLIFIED_KEY, false);

        // 服务接口类型 如：org.apache.dubbo.demo.DemoService
        this.serviceType = serviceType;
        // 服务类型 org.apache.dubbo.registry.RegistryService
        this.serviceKey = super.getConsumerUrl().getServiceKey();
        // 这里将URL中refer提取出来重新生成地址，如zookeeper://192.168.86.60:2181/org.apache.dubbo.registry.RegistryService?原refer的值
        this.overrideDirectoryUrl = this.directoryUrl = turnRegistryUrlToConsumerUrl(url);
        // 所属group
        String group = directoryUrl.getParameter(GROUP_KEY, "");
        // 是否支持多个group
        this.multiGroup = group != null && (ANY_VALUE.equals(group) || group.contains(","));
    }

    @Override
    public void addServiceListener(ServiceInstancesChangedListener instanceListener) {
        this.serviceListener = instanceListener;
    }

    private URL turnRegistryUrlToConsumerUrl(URL url) {
        return URLBuilder.from(url)
                .setHost(queryMap.get(REGISTER_IP_KEY) == null ? url.getHost() : queryMap.get(REGISTER_IP_KEY))
                .setPort(0)
                .setProtocol(queryMap.get(PROTOCOL_KEY) == null ? DUBBO : queryMap.get(PROTOCOL_KEY))
                .setPath(queryMap.get(INTERFACE_KEY))
                .clearParameters()
                .addParameters(queryMap)
                .removeParameter(MONITOR_KEY)
                .build();
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public Registry getRegistry() {
        return registry;
    }

    public boolean isShouldRegister() {
        return shouldRegister;
    }

    public void subscribe(URL url) {
        setConsumerUrl(url);
        registry.subscribe(url, this);
    }

    public void unSubscribe(URL url) {
        setConsumerUrl(null);
        registry.unsubscribe(url, this);
    }

    @Override
    public List<Invoker<T>> doList(Invocation invocation) {
        // 检测forbidden字段，当该字段在refreshInvoker()过程中设置为true时，表示无Provider可用，直接抛出异常
        if (forbidden) {
            // 1. No service provider 2. Service providers are disabled
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available from registry " +
                    getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +
                    NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() +
                    ", please check status of providers(disabled, not registered or in blacklist).");
        }

        if (multiGroup) {
            //  multiGroup为true时的特殊处理，在refreshInvoker()方法中针对multiGroup为true的场景，
            // 已经使用Router进行了筛选，所以这里直接返回接口
            return this.invokers == null ? Collections.emptyList() : this.invokers;
        }

        List<Invoker<T>> invokers = null;
        try {
            // Get invokers from cache, only runtime routers will be executed.
            // 通过RouterChain.route()方法筛选Invoker集合，最终得到符合路由条件的Invoker集合
            invokers = routerChain.route(getConsumerUrl(), invocation);
        } catch (Throwable t) {
            logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
        }

        return invokers == null ? Collections.emptyList() : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public List<Invoker<T>> getAllInvokers() {
        return invokers;
    }

    @Override
    public URL getConsumerUrl() {
        return this.overrideDirectoryUrl;
    }

    public URL getRegisteredConsumerUrl() {
        return registeredConsumerUrl;
    }

    public void setRegisteredConsumerUrl(URL url) {
        if (!shouldSimplified) {
            this.registeredConsumerUrl = url.addParameters(CATEGORY_KEY, CONSUMERS_CATEGORY, CHECK_KEY,
                    String.valueOf(false));
        } else {
            this.registeredConsumerUrl = URL.valueOf(url, DEFAULT_REGISTER_CONSUMER_KEYS, null).addParameters(
                    CATEGORY_KEY, CONSUMERS_CATEGORY, CHECK_KEY, String.valueOf(false));
        }
    }

    public void buildRouterChain(URL url) {
        this.setRouterChain(RouterChain.buildChain(url));
    }

    public List<Invoker<T>> getInvokers() {
        return invokers;
    }

    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }

        // unregister.
        try {
            if (getRegisteredConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unregister(getRegisteredConsumerUrl());
            }
        } catch (Throwable t) {
            logger.warn("unexpected error when unregister service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        // unsubscribe.
        try {
            if (getSubscribeConsumerurl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getSubscribeConsumerurl(), this);
            }
        } catch (Throwable t) {
            logger.warn("unexpected error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // must be executed after unsubscribing
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }

        invokersChangedListener = null;
    }

    @Override
    public void discordAddresses() {
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    private volatile InvokersChangedListener invokersChangedListener;
    private volatile boolean addressChanged;

    public void setInvokersChangedListener(InvokersChangedListener listener) {
        this.invokersChangedListener = listener;
        if (addressChanged) {
            if (invokersChangedListener != null) {
                invokersChangedListener.onChange();
                this.addressChanged = false;
            }
        }
    }

    protected void invokersChanged() {
        if (invokersChangedListener != null) {
            invokersChangedListener.onChange();
            this.addressChanged = false;
        } else {
            this.addressChanged = true;
        }
    }

    protected abstract void destroyAllInvokers();
}
