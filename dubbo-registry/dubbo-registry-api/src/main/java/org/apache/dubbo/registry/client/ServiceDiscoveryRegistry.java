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
package org.apache.dubbo.registry.client;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.metadata.MappingChangedEvent;
import org.apache.dubbo.metadata.MappingListener;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.client.event.ServiceInstancesChangedEvent;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;
import org.apache.dubbo.registry.client.metadata.SubscribedURLsSynthesizer;
import org.apache.dubbo.registry.support.AbstractRegistryFactory;
import org.apache.dubbo.registry.support.FailbackRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.of;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_CHAR_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MAPPING_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDED_BY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_CLUSTER_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_TYPE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_TYPE;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBED_SERVICE_NAMES_KEY;
import static org.apache.dubbo.common.function.ThrowableAction.execute;
import static org.apache.dubbo.common.utils.CollectionUtils.isEmpty;
import static org.apache.dubbo.common.utils.StringUtils.isBlank;
import static org.apache.dubbo.registry.client.ServiceDiscoveryFactory.getExtension;
import static org.apache.dubbo.rpc.Constants.ID_KEY;

/**
 * Being different to the traditional registry, {@link ServiceDiscoveryRegistry} that is a new service-oriented
 * {@link Registry} based on {@link ServiceDiscovery}, it will not interact in the external registry directly,
 * but store the {@link URL urls} that Dubbo services exported and referenced into {@link WritableMetadataService}
 * when {@link #register(URL)} and {@link #subscribe(URL, NotifyListener)} methods are executed. After that the exported
 * {@link URL urls} can be get from {@link WritableMetadataService#getExportedURLs()} and its variant methods. In contrast,
 * {@link WritableMetadataService#getSubscribedURLs()} method offers the subscribed {@link URL URLs}.
 * <p>
 * Every {@link ServiceDiscoveryRegistry} object has its own {@link ServiceDiscovery} instance that was initialized
 * under {@link #ServiceDiscoveryRegistry(URL) the construction}. As the primary argument of constructor , the
 * {@link URL} of connection the registry decides what the kind of ServiceDiscovery is. Generally, each
 * protocol associates with a kind of {@link ServiceDiscovery}'s implementation if present, or the
 * {@link FileSystemServiceDiscovery} will be the default one. Obviously, it's also allowed to extend
 * {@link ServiceDiscovery} using {@link SPI the Dubbo SPI}.
 * In contrast, current {@link ServiceInstance service instance} will not be registered to the registry whether any
 * Dubbo service is exported or not.
 * <p>
 *
 * @see ServiceDiscovery
 * @see FailbackRegistry
 * @see WritableMetadataService
 * @since 2.7.5
 */
public class ServiceDiscoveryRegistry implements Registry {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final ServiceDiscovery serviceDiscovery;

    private final Set<String> subscribedServices;

    private final ServiceNameMapping serviceNameMapping;

    private final WritableMetadataService writableMetadataService;

    private final Set<String> registeredListeners = new LinkedHashSet<>();

    /* apps - listener */
    private final Map<String, ServiceInstancesChangedListener> serviceListeners = new HashMap<>();
    private final Map<String, String> serviceToAppsMapping = new HashMap<>();

    private URL registryURL;

    /**
     * A cache for all URLs of services that the subscribed services exported
     * The key is the service name
     * The value is a nested {@link Map} whose key is the revision and value is all URLs of services
     */
    private final Map<String, Map<String, List<URL>>> serviceRevisionExportedURLsCache = new LinkedHashMap<>();

    public ServiceDiscoveryRegistry(URL registryURL) {
        this.registryURL = registryURL;
        // 创建ServiceDiscovery对象
        this.serviceDiscovery = createServiceDiscovery(registryURL);
        // subscribedServices保存提供对应服务的应用名，
        // 可以通过RegistryConfig或者ApplicationConfig的parameters属性设置参数“subscribed-services”值，一般无需设置该参数
        this.subscribedServices = parseServices(registryURL.getParameter(SUBSCRIBED_SERVICE_NAMES_KEY));
        // 服务名匹配，实现类是DynamicConfigurationServiceNameMapping
        // serviceNameMapping访问配置中心获取匹配的服务提供者
        this.serviceNameMapping = ServiceNameMapping.getExtension(registryURL.getParameter(MAPPING_KEY));
        // 获取元数据中心对象。默认是InMemoryWritableMetadataService，
        // 可以通过参数“dubbo.metadata.storage-type”修改，一共两个值：local和remote
        this.writableMetadataService = WritableMetadataService.getDefaultExtension();
    }

    public ServiceDiscovery getServiceDiscovery() {
        return serviceDiscovery;
    }

    /**
     * Create the {@link ServiceDiscovery} from the registry {@link URL} <br><br>
     *
     * ServiceDiscovery类是自省服务发现中非常重要的类，构造方法中通过createServiceDiscovery创建出ServiceDiscovery对象
     *
     * @param registryURL the {@link URL} to connect the registry
     * @return non-null
     */
    protected ServiceDiscovery createServiceDiscovery(URL registryURL) {
        // 根据入参registryURL中协议使用SPI加载ServiceDiscoveryFactory对象，
        // 通过ServiceDiscoveryFactory创建ServiceDiscovery对象
        // ServiceDiscoveryFactory只有一个实现类DefaultServiceDiscoveryFactory，SPI的名字是default
        ServiceDiscovery originalServiceDiscovery = getServiceDiscovery(registryURL);
        // 使用EventPublishingServiceDiscovery装饰ServiceDiscovery，
        // EventPublishingServiceDiscovery可以在调用ServiceDiscovery的某些方法时，发布对应的事件，这些事件用于打印日志
        ServiceDiscovery serviceDiscovery = enhanceEventPublishing(originalServiceDiscovery);
        execute(() -> {
            // 初始化ServiceDiscovery
            serviceDiscovery.initialize(registryURL.addParameter(INTERFACE_KEY, ServiceDiscovery.class.getName())
                    .removeParameter(REGISTRY_TYPE_KEY));
        });
        return serviceDiscovery;
    }

    private List<SubscribedURLsSynthesizer> initSubscribedURLsSynthesizers() {
        ExtensionLoader<SubscribedURLsSynthesizer> loader = ExtensionLoader.getExtensionLoader(SubscribedURLsSynthesizer.class);
        return Collections.unmodifiableList(new ArrayList<>(loader.getSupportedExtensionInstances()));
    }

    /**
     * Get the instance {@link ServiceDiscovery} from the registry {@link URL} using
     * {@link ServiceDiscoveryFactory} SPI
     *
     * @param registryURL the {@link URL} to connect the registry
     * @return
     */
    private ServiceDiscovery getServiceDiscovery(URL registryURL) {
        // ServiceDiscoveryFactory只有一个实现类DefaultServiceDiscoveryFactory
        ServiceDiscoveryFactory factory = getExtension(registryURL);
        // 使用SPI加载ServiceDiscovery实现类，ServiceDiscovery有哪些实现类可以参见文件org.apache.dubbo.registry.client.ServiceDiscovery，文件内容在代码下方。
        // 加载哪个实现类由dubbo.registry.address的协议指定，这里使用的协议是zookeeper
        return factory.getServiceDiscovery(registryURL);
    }

    /**
     * Enhance the original {@link ServiceDiscovery} with event publishing feature
     *
     * @param original the original {@link ServiceDiscovery}
     * @return {@link EventPublishingServiceDiscovery} instance
     */
    private ServiceDiscovery enhanceEventPublishing(ServiceDiscovery original) {
        return new EventPublishingServiceDiscovery(original);
    }

    /**
     * 这里判断是否能被注册，只有provider才可以被注册
     * @param providerURL URL
     * @return boolean
     */
    protected boolean shouldRegister(URL providerURL) {

        String side = providerURL.getParameter(SIDE_KEY);

        boolean should = PROVIDER_SIDE.equals(side); // Only register the Provider.

        if (!should) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("The URL[%s] should not be registered.", providerURL.toString()));
            }
        }

        return should;
    }

    /**
     * 判断是否是消费端，因为只有消费端才能够被订阅
     * @param subscribedURL URL
     * @return boolean
     */
    protected boolean shouldSubscribe(URL subscribedURL) {
        return !shouldRegister(subscribedURL);
    }

    // 服务注册由ServiceDiscoveryRegistry的register方法完成，在以下两个场景调用该方法：
    // 1.服务端暴露服务，将被暴露的服务协议、IP、参数、接口名等信息注册到元数据中心；
    // 2.服务端发布MetadataService服务，该服务发布是在DubboBootstrap的start方法中调用的；
    @Override
    public final void register(URL url) {
        if (!shouldRegister(url)) { // Should Not Register 只有服务端才可以注册
            return;
        }
        doRegister(url);
    }

    // doRegister调用WritableMetadataService的exportURL方法将url注册到元数据中心，
    // WritableMetadataService有三个实现类：InMemoryWritableMetadataService、RemoteWritableMetadataServiceDelegate、RemoteWritableMetadataService。
    public void doRegister(URL url) {
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        // writableMetadataService默认实现是InMemoryWritableMetadataService，
        // 下面的代码exportURL方法将入参url注册到元数据中心
        if (writableMetadataService.exportURL(url)) {
            if (logger.isInfoEnabled()) {
                logger.info(format("The URL[%s] registered successfully.", url.toString()));
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.info(format("The URL[%s] has been registered.", url.toString()));
            }
        }
    }

    @Override
    public final void unregister(URL url) {
        if (!shouldRegister(url)) {
            return;
        }
        doUnregister(url);
    }

    public void doUnregister(URL url) {
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        if (writableMetadataService.unexportURL(url)) {
            if (logger.isInfoEnabled()) {
                logger.info(format("The URL[%s] deregistered successfully.", url.toString()));
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.info(format("The URL[%s] has been deregistered.", url.toString()));
            }
        }
    }

    // 服务订阅调用subscribe方法，服务订阅在以下场景中调用：
    // 1.消费端创建远程服务代理过程中调用subscribe方法建立与远程服务的连接；
    // 2.消费端发布MetadataService服务时调用subscribe方法建立与远程服务的连接。
    @Override
    public final void subscribe(URL url, NotifyListener listener) {
        if (!shouldSubscribe(url)) { // Should Not Subscribe 判断是否是消费端，只有消费端才可以调用subscribe方法
            return;
        }
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        doSubscribe(url, listener);
    }

    public void doSubscribe(URL url, NotifyListener listener) {
        // subscribeURL方法与上面介绍的exportURL方法类似。
        writableMetadataService.subscribeURL(url);
        // 查找提供服务的应用名
        Set<String> serviceNames = getServices(url, listener);

        if (CollectionUtils.isEmpty(serviceNames)) {
            logger.warn("Should has at least one way to know which services this interface belongs to, subscription url: " + url);
            return;
        }

        subscribeURLs(url, listener, serviceNames);
    }

    @Override
    public final void unsubscribe(URL url, NotifyListener listener) {
        if (!shouldSubscribe(url)) { // Should Not Subscribe
            return;
        }
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        doUnsubscribe(url, listener);
    }

    public void doUnsubscribe(URL url, NotifyListener listener) {
        writableMetadataService.unsubscribeURL(url);
        String protocolServiceKey = url.getServiceKey() + GROUP_CHAR_SEPARATOR + url.getParameter(PROTOCOL_KEY, DUBBO);
        String serviceNamesKey = serviceToAppsMapping.remove(protocolServiceKey);
        if (serviceNamesKey == null) {
            return;
        }
        ServiceInstancesChangedListener instancesChangedListener = serviceListeners.get(serviceNamesKey);
        instancesChangedListener.removeListener(protocolServiceKey);
    }

    @Override
    public List<URL> lookup(URL url) {
        throw new UnsupportedOperationException("");
    }

    @Override
    public URL getUrl() {
        return registryURL;
    }

    @Override
    public boolean isAvailable() {
        return !serviceDiscovery.getServices().isEmpty();
    }

    @Override
    public void destroy() {
        AbstractRegistryFactory.removeDestroyedRegistry(this);
        execute(() -> {
            // stop ServiceDiscovery
            serviceDiscovery.destroy();
        });
    }

    protected void subscribeURLs(URL url, NotifyListener listener, Set<String> serviceNames) {
        String serviceNamesKey = serviceNames.toString();
        String protocolServiceKey = url.getServiceKey() + GROUP_CHAR_SEPARATOR + url.getParameter(PROTOCOL_KEY, DUBBO);
        serviceToAppsMapping.put(protocolServiceKey, serviceNamesKey);

        // register ServiceInstancesChangedListener
        ServiceInstancesChangedListener serviceListener = serviceListeners.computeIfAbsent(serviceNamesKey,
                k -> new ServiceInstancesChangedListener(serviceNames, serviceDiscovery));
        serviceListener.setUrl(url);
        listener.addServiceListener(serviceListener);

        serviceListener.addListener(protocolServiceKey, listener);
        registerServiceInstancesChangedListener(url, serviceListener);

        // FIXME: This will cause redundant duplicate notifications
        serviceNames.forEach(serviceName -> {
            // 根据应用名从注册中心找到服务实例ServiceInstance，
            // ServiceInstance的注册过程在后文介绍。ServiceInstance里面记录有ip地址，端口等信息
            List<ServiceInstance> serviceInstances = serviceDiscovery.getInstances(serviceName);
            if (CollectionUtils.isNotEmpty(serviceInstances)) {
                // 设置监听器监听目录/services/应用名，当该目录下数据发生变化时，通过调用subscribeURLs方法
                // 重新建立对远程服务的引用
                serviceListener.onEvent(new ServiceInstancesChangedEvent(serviceName, serviceInstances));
            } else {
                logger.info("getInstances by serviceName=" + serviceName + " is empty, waiting for serviceListener callback. url=" + url);
            }
        });

        listener.notify(serviceListener.getUrls(protocolServiceKey));

    }

    /**
     * Register the {@link ServiceInstancesChangedListener} If absent
     *
     * @param url      {@link URL}
     * @param listener the {@link ServiceInstancesChangedListener}
     */
    private void registerServiceInstancesChangedListener(URL url, ServiceInstancesChangedListener listener) {
        String listenerId = createListenerId(url, listener);
        if (registeredListeners.add(listenerId)) {
            serviceDiscovery.addServiceInstancesChangedListener(listener);
        }
    }

    private String createListenerId(URL url, ServiceInstancesChangedListener listener) {
        return listener.getServiceNames() + ":" + url.toString(VERSION_KEY, GROUP_KEY, PROTOCOL_KEY);
    }

    /**
     * 1.developer explicitly specifies the application name this interface belongs to
     * 2.check Interface-App mapping
     * 3.use the services specified in registry url. <br><br>
     *
     * 一共有三种查找方式：
     * 1、参数“provided-by”指定。
     * 2、从配置中心获取.
     * 3、参数“subscribed-services”指定
     *
     * @param subscribedURL
     * @return
     */
    protected Set<String> getServices(URL subscribedURL, final NotifyListener listener) {
        Set<String> subscribedServices = new TreeSet<>();

        // 可以通过参数“provided-by”指定应用名，如果有多个应用，应用名之间使用“,”分隔
        String serviceNames = subscribedURL.getParameter(PROVIDED_BY);
        if (StringUtils.isNotEmpty(serviceNames)) {
            logger.info(subscribedURL.getServiceInterface() + " mapping to " + serviceNames + " instructed by provided-by set by user.");
            subscribedServices.addAll(parseServices(serviceNames));
        }

        if (isEmpty(subscribedServices)) {
            // 访问配置中心获取应用名，该应用名是由ServiceNameMappingListener发布到配置中心的
            Set<String> mappedServices = findMappedServices(subscribedURL, new DefaultMappingListener(subscribedURL, subscribedServices, listener));
            logger.info(subscribedURL.getServiceInterface() + " mapping to " + serviceNames + " instructed by remote metadata center.");
            subscribedServices.addAll(mappedServices);
            if (isEmpty(subscribedServices)) {
                logger.info(subscribedURL.getServiceInterface() + " mapping to " + serviceNames + " by default.");
                // 如果上述两种方式都找不到合适的应用，
                // 那么使用参数“subscribed-services”指定的应用，如果有多个应用，之间使用“,”分隔
                subscribedServices.addAll(getSubscribedServices());
            }
        }
        return subscribedServices;
    }

    public static Set<String> parseServices(String literalServices) {
        return isBlank(literalServices) ? emptySet() :
                unmodifiableSet(of(literalServices.split(","))
                        .map(String::trim)
                        .filter(StringUtils::isNotEmpty)
                        .collect(toSet()));
    }

    /**
     * Get the subscribed service names
     *
     * @return non-null
     */
    public Set<String> getSubscribedServices() {
        return subscribedServices;
    }

    /**
     * Get the mapped services name by the specified {@link URL}
     *
     * @param subscribedURL
     * @return
     */
    protected Set<String> findMappedServices(URL subscribedURL, MappingListener listener) {
        return serviceNameMapping.getAndListen(subscribedURL, listener);
    }

    /**
     * Create an instance of {@link ServiceDiscoveryRegistry} if supported
     *
     * @param registryURL the {@link URL url} of registry
     * @return <code>null</code> if not supported
     */
    public static ServiceDiscoveryRegistry create(URL registryURL) {
        return supports(registryURL) ? new ServiceDiscoveryRegistry(registryURL) : null;
    }

    /**
     * Supports or not ?
     *
     * @param registryURL the {@link URL url} of registry
     * @return if supported, return <code>true</code>, or <code>false</code>
     */
    public static boolean supports(URL registryURL) {
        return SERVICE_REGISTRY_TYPE.equalsIgnoreCase(registryURL.getParameter(REGISTRY_TYPE_KEY));
    }

    private static List<URL> filterSubscribedURLs(URL subscribedURL, List<URL> exportedURLs) {
        return exportedURLs.stream()
                .filter(url -> isSameServiceInterface(subscribedURL, url))
                .filter(url -> isSameParameter(subscribedURL, url, VERSION_KEY))
                .filter(url -> isSameParameter(subscribedURL, url, GROUP_KEY))
                .filter(url -> isCompatibleProtocol(subscribedURL, url))
                .collect(Collectors.toList());
    }

    private static boolean isSameServiceInterface(URL one, URL another) {
        return Objects.equals(one.getServiceInterface(), another.getServiceInterface());
    }

    private static boolean isSameParameter(URL one, URL another, String key) {
        return Objects.equals(one.getParameter(key), another.getParameter(key));
    }

    private static boolean isCompatibleProtocol(URL one, URL another) {
        String protocol = one.getParameter(PROTOCOL_KEY);
        return isCompatibleProtocol(protocol, another);
    }

    private static boolean isCompatibleProtocol(String protocol, URL targetURL) {
        return protocol == null || Objects.equals(protocol, targetURL.getParameter(PROTOCOL_KEY))
                || Objects.equals(protocol, targetURL.getProtocol());
    }

    private class DefaultMappingListener implements MappingListener {
        private URL url;
        private Set<String> oldApps;
        private NotifyListener listener;

        public DefaultMappingListener(URL subscribedURL, Set<String> serviceNames, NotifyListener listener) {
            this.url = subscribedURL;
            this.oldApps = serviceNames;
            this.listener = listener;
        }

        @Override
        public void onEvent(MappingChangedEvent event) {
            Set<String> newApps = event.getApps();
            Set<String> tempOldApps = oldApps;
            oldApps = newApps;

            if (CollectionUtils.isEmpty(newApps)) {
                return;
            }

            if (CollectionUtils.isEmpty(tempOldApps) && newApps.size() > 0) {
                subscribeURLs(url, listener, newApps);
                return;
            }

            for (String newAppName : newApps) {
                if (!tempOldApps.contains(newAppName)) {
                    subscribeURLs(url, listener, newApps);
                    return;
                }
            }
        }
    }
}
