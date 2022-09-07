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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.constants.RegistryConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ReferenceConfigDestroyedEvent;
import org.apache.dubbo.config.event.ReferenceConfigInitializedEvent;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.cluster.support.registry.ZoneAwareCluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.AsyncMethodInfo;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR_CHAR;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROXY_CLASS_REF;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SEMICOLON_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBED_SERVICE_NAMES_KEY;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.StringUtils.splitToSet;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * Please avoid using this class for any new application,
 * use {@link ReferenceConfigBase} instead.
 *
 * <p>
 * ReferenceConfig 是服务引用的真正入口，其中会创建相关的代理对象
 *
 * <p>
 * 服务引用的核心实现在 ReferenceConfig 之中，一个 ReferenceConfig 对象对应一个服务接口，
 * 每个 ReferenceConfig 对象中都封装了与注册中心的网络连接，以及与 Provider 的网络连接。
 */
public class ReferenceConfig<T> extends ReferenceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ReferenceConfig.class);

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implementation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wraps two
     * layers, and eventually will get a <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     */
    private static final Protocol REF_PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * The {@link Cluster}'s implementation with adaptive functionality, and actually it will get a {@link Cluster}'s
     * specific implementation who is wrapped with <b>MockClusterInvoker</b>
     */
    private static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a reference service's proxy,the JavassistProxyFactory is
     * its default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * The interface proxy reference
     */
    private transient volatile T ref;

    /**
     * The invoker of the reference service
     */
    private transient volatile Invoker<?> invoker;

    /**
     * The flag whether the ReferenceConfig has been initialized
     */
    private transient volatile boolean initialized;

    /**
     * whether this ReferenceConfig has been destroyed
     */
    private transient volatile boolean destroyed;

    private final ServiceRepository repository;

    private DubboBootstrap bootstrap;

    /**
     * The service names that the Dubbo interface subscribed.
     *
     * @since 2.7.8
     */
    private String services;

    public ReferenceConfig() {
        super();
        this.repository = ApplicationModel.getServiceRepository();
    }

    public ReferenceConfig(Reference reference) {
        super(reference);
        this.repository = ApplicationModel.getServiceRepository();
    }

    /**
     * Get a string presenting the service names that the Dubbo interface subscribed.
     * If it is a multiple-values, the content will be a comma-delimited String.
     *
     * @return non-null
     * @see RegistryConstants#SUBSCRIBED_SERVICE_NAMES_KEY
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(key = SUBSCRIBED_SERVICE_NAMES_KEY)
    public String getServices() {
        return services;
    }

    /**
     * It's an alias method for {@link #getServices()}, but the more convenient.
     *
     * @return the String {@link List} presenting the Dubbo interface subscribed
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(excluded = true)
    public Set<String> getSubscribedServices() {
        return splitToSet(getServices(), COMMA_SEPARATOR_CHAR);
    }

    /**
     * Set the service names that the Dubbo interface subscribed.
     *
     * @param services If it is a multiple-values, the content will be a comma-delimited String.
     * @since 2.7.8
     */
    public void setServices(String services) {
        this.services = services;
    }

    /**
     * 获取服务引用的代理对象，如果未初始化过，则调用{@link #init()}}方法初始化
     * @return T
     */
    public synchronized T get() {
        // 检测当前ReferenceConfig状态
        if (destroyed) {
            throw new IllegalStateException("The invoker of ReferenceConfig(" + url + ") has already destroyed!");
        }
        // 检测 ref 是否为空，为空则通过 init 方法创建
        if (ref == null) { // ref指向了服务的代理对象
            // 启动初始化操作 init 方法主要用于处理配置，以及调用 createProxy 生成代理类
            init();
        }
        return ref;
    }

    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected error occurred when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;

        // dispatch a ReferenceConfigDestroyedEvent since 2.7.4
        dispatch(new ReferenceConfigDestroyedEvent(this));
    }

    /**
     * 初始化服务引用的代理对象
     *
     * <p>
     * 首先会对服务引用的配置进行处理，以保证配置的正确性
     *
     * <p>
     * 核心逻辑是调用 createProxy() 方法，调用之前会从配置中获取 createProxy() 方法需要的参数。
     */
    public synchronized void init() {
        // 避免重复加载
        if (initialized) {
            return;
        }

        // 获取Dubbo核心容器
        if (bootstrap == null) {
            bootstrap = DubboBootstrap.getInstance();
            // 进行Dubbo核心配置的加载和检查
            bootstrap.initialize();
        }
        // 在对象创建后在使用其他配置模块配置对象之前检查对象配置并重写默认配置
        checkAndUpdateSubConfigs();
        // 检查并生成sub配置和Local配置是否合法
        checkStubAndLocal(interfaceClass);
        // 判断对象是否有mock并生成mock信息
        ConfigValidationUtils.checkMock(interfaceClass, this);
        // 保存对象属性map信息
        Map<String, String> map = new HashMap<String, String>();
        map.put(SIDE_KEY, CONSUMER_SIDE);
        // 添加版本信息，包含dubbo版本，release版本，timestamp运行时间戳和sid_key等信息
        ReferenceConfigBase.appendRuntimeParameters(map);
        // 添加泛型 revision信息
        if (!ProtocolUtils.isGeneric(generic)) {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);
            }
            // 生成服务的代理对象，跟服务导出是一样，通过代理对象来代理，返回代理方法
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                // 添加需要代理的方法
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), COMMA_SEPARATOR));
            }
        }
        // 添加interface名
        map.put(INTERFACE_KEY, interfaceName);
        // 添加重试信息
        AbstractConfig.appendParameters(map, getMetrics());
        // 检查获取并添加Application信息
        AbstractConfig.appendParameters(map, getApplication());
        // 检查获取并添加Module信息
        AbstractConfig.appendParameters(map, getModule());
        // remove 'default.' prefix for configs from ConsumerConfig
        // appendParameters(map, consumer, Constants.DEFAULT_KEY);
        // 检查获取并添加consumer信息
        AbstractConfig.appendParameters(map, consumer);
        AbstractConfig.appendParameters(map, this);
        MetadataReportConfig metadataReportConfig = getMetadataReportConfig();
        if (metadataReportConfig != null && metadataReportConfig.isValid()) {
            map.putIfAbsent(METADATA_KEY, REMOTE_METADATA_STORAGE_TYPE);
        }
        // 设置方法重试信息并收集方法异步调用信息
        Map<String, AsyncMethodInfo> attributes = null;
        if (CollectionUtils.isNotEmpty(getMethods())) {
            attributes = new HashMap<>();
            for (MethodConfig methodConfig : getMethods()) {
                AbstractConfig.appendParameters(map, methodConfig, methodConfig.getName());
                String retryKey = methodConfig.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
                AsyncMethodInfo asyncMethodInfo = AbstractConfig.convertMethodConfig2AsyncInfo(methodConfig);
                if (asyncMethodInfo != null) {
//                    consumerModel.getMethodModel(methodConfig.getName()).addAttribute(ASYNC_KEY, asyncMethodInfo);
                    attributes.put(methodConfig.getName(), asyncMethodInfo);
                }
            }
        }
        // 获取服务消费者 ip 地址
        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException(
                    "Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        // 添加服务注册信息
        map.put(REGISTER_IP_KEY, hostToRegistry);
        // 将配置保存如服务元信息中
        serviceMetadata.getAttachments().putAll(map);
        // 创建代理
        ref = createProxy(map);

        serviceMetadata.setTarget(ref);
        serviceMetadata.addAttribute(PROXY_CLASS_REF, ref);
        // 根据服务名，ReferenceConfig，代理类构建 ConsumerModel，
        // 并将 ConsumerModel 存入到 ApplicationModel 中
        ConsumerModel consumerModel = repository.lookupReferredService(serviceMetadata.getServiceKey());
        consumerModel.setProxyObject(ref);
        consumerModel.init(attributes);

        initialized = true;
        // 检查引入的服务是否可用
        checkInvokerAvailable();

        // dispatch a ReferenceConfigInitializedEvent since 2.7.4
        dispatch(new ReferenceConfigInitializedEvent(this, invoker));
    }

    /**
     * 创建服务引用代理对象
     *
     * <ol>
     * <li>根据传入的参数集合判断协议是否为 injvm 协议，如果是，直接通过 InjvmProtocol 引用服务。</li>
     * <li>构造 urls 集合。Dubbo 支持直连 Provider和依赖注册中心两种服务引用方式。
     * 如果是直连服务的模式，我们可以通过 url 参数指定一个或者多个 Provider 地址，会被解析并填充到 urls 集合；
     * 如果通过注册中心的方式进行服务引用，则会调用 AbstractInterfaceConfig.loadRegistries() 方法加载所有注册中心。</li>
     * <li>如果 urls 集合中只记录了一个 URL，通过 Protocol 适配器选择合适的 Protocol 扩展实现创建 Invoker 对象。
     * 如果是直连 Provider 的场景，则 URL 为 dubbo 协议，这里就会使用 DubboProtocol 这个实现；
     * 如果依赖注册中心，则使用 RegistryProtocol 这个实现。</li>
     * <li>如果 urls 集合中有多个注册中心，则使用 ZoneAwareCluster 作为 Cluster 的默认实现，生成对应的 Invoker 对象；
     * 如果 urls 集合中记录的是多个直连服务的地址，则使用 Cluster 适配器选择合适的扩展实现生成 Invoker 对象。</li>
     * <li>通过 ProxyFactory 适配器选择合适的 ProxyFactory 扩展实现，将 Invoker 包装成服务接口的代理对象。</li>
     * </ol>
     *
     * 上面的流程我们可以看出createProxy() 方法中有两个核心：
     * <ol>
     * <li>通过 Protocol 适配器选择合适的 Protocol 扩展实现创建 Invoker 对象。</li>
     * <li>通过 ProxyFactory 适配器选择合适的 ProxyFactory 创建代理对象。</li>
     * </ol>
     *
     * @param map 属性，用于构造URL
     * @return T
     */
    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private T createProxy(Map<String, String> map) {
        // jvm本地引入
        // 根据url的协议、scope以及injvm等参数检测是否需要本地引用
        if (shouldJvmRefer(map)) {
            // 创建injvm协议的URL
            URL url = new URL(LOCAL_PROTOCOL, LOCALHOST_VALUE, 0, interfaceClass.getName()).addParameters(map);
            // 本地引用invoker生成
            // 通过Protocol的适配器选择对应的Protocol实现创建Invoker对象
            invoker = REF_PROTOCOL.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {
            urls.clear();
            // 用户配置url信息,表明用户可能想进行点对点调用
            if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
                // 当需要配置多个 url 时，可用分号进行分割，这里会进行切分
                String[] us = SEMICOLON_SPLIT_PATTERN.split(url);
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (StringUtils.isEmpty(url.getPath())) {
                            // 设置接口全限定名为 url 路径
                            url = url.setPath(interfaceName);
                        }
                        // 检测 url 协议是否为 registry，若是，表明用户想使用指定的注册中心
                        if (UrlUtils.isRegistry(url)) {
                            // 将 map 转换为查询字符串，并作为 refer 参数的值添加到 url 中
                            urls.add(url.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        } else {
                            // 合并 url，移除服务提供者的一些配置（这些配置来源于用户配置的 url 属性），
                            // 比如线程池相关配置。并保留服务提供者的部分配置，比如版本，group，时间戳等
                            // 最后将合并后的配置设置为 url 查询字符串中。
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }
            } else { // assemble URL from register center's configuration
                // 从注册中心的配置中组装url信息
                // if protocols not injvm checkRegistry
                // 如果协议不是在jvm本地中
                if (!LOCAL_PROTOCOL.equalsIgnoreCase(getProtocol())) {
                    // 检查注册中心是否存在(如果当前配置不存在则获取服务默认配置),然后将他们转换到RegistryConfig中
                    checkRegistry();
                    // 通过注册中心配置信息组装URL
                    List<URL> us = ConfigValidationUtils.loadRegistries(this, false);
                    if (CollectionUtils.isNotEmpty(us)) {
                        for (URL u : us) {
                            // 添加monitor监控信息
                            URL monitorUrl = ConfigValidationUtils.loadMonitor(this, u);
                            if (monitorUrl != null) {
                                map.put(MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                            }
                            // 将map中的参数整理成refer参数，添加到RegistryURL中
                            urls.add(u.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        }
                    }
                    // 既不是服务直连，也没有配置注册中心，抛出异常
                    if (urls.isEmpty()) {
                        throw new IllegalStateException(
                                "No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() +
                                        " use dubbo version " + Version.getVersion() +
                                        ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                    }
                }
            }
            // 单个注册中心或服务提供者(服务直连，下同)
            if (urls.size() == 1) {
                // 调用 RegistryProtocol 的 refer 构建 Invoker 实例
                // 在单注册中心或是直连单个服务提供方的时候，通过Protocol的适配器选择对应的Protocol实现创建Invoker对象
                // 这一步会注册到目标服务的consumers中
                // 0. org.apache.dubbo.registry.integration.RegistryProtocol.refer
                // 1. org.apache.dubbo.registry.integration.RegistryDirectory.toInvokers
                // 2. org.apache.dubbo.rpc.protocol.ProtocolListenerWrapper.refer
                // 3. org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper.refer
                // 4. org.apache.dubbo.qos.protocol.QosProtocolWrapper.refer
                // 5. org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol.protocolBindingRefer，掉入的地址。
                // 6. org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper.buildInvokerChain
                // 7. org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper.refer
                // 8. org.apache.dubbo.registry.integration.RegistryProtocol.refer
                // 9.org.apache.dubbo.registry.zookeeper.ZookeeperRegistry
                // 10. org.apache.dubbo.registry.support.FailbackRegistry.register
                // REF_PROTOCOL 是个自动生成的自适应类，源码在 org.apache.dubbo.common.extension.ExtensionLoader.createAdaptiveExtensionClass 中有。
                // REF_PROTOCOL = Protocol$Adaptive，内部最终会根据扫描到的包内配置将相同Class类型的包装类封装到cachedWrapperClasses属性中，然后挨个初始化，最后生成自适应类的返回类型。
                // invoker = MockClusterInvoker
                invoker = REF_PROTOCOL.refer(interfaceClass, urls.get(0));
            } else {
                // 多个注册中心或多个服务提供者，或者两者混合
                // 多注册中心或是直连多个服务提供方的时候，会根据每个URL创建Invoker对象
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                // 获取所有的 Invoker
                for (URL url : urls) {
                    Invoker<?> referInvoker = REF_PROTOCOL.refer(interfaceClass, url);
                    if (shouldCheck()) {
                        if (referInvoker.isAvailable()) {
                            invokers.add(referInvoker);
                        } else {
                            referInvoker.destroy();
                        }
                    } else {
                        invokers.add(referInvoker);
                    }

                    if (UrlUtils.isRegistry(url)) { // 确定是多注册中心，还是直连多个Provider
                        // 保存使用注册中心的最新的URL信息
                        registryURL = url; // use last registry url
                    }
                }

                if (shouldCheck() && invokers.size() == 0) {
                    throw new IllegalStateException("Failed to check the status of the service "
                            + interfaceName
                            + ". No provider available for the service "
                            + (group == null ? "" : group + "/")
                            + interfaceName +
                            (version == null ? "" : ":" + version)
                            + " from the multi registry cluster"
                            + " use dubbo version " + Version.getVersion());
                }
                // 注册中心URL存在
                if (registryURL != null) { // registry url is available
                    // for multi-subscription scenario, use 'zone-aware' policy by default
                    // 多注册中心的场景中，会使用ZoneAwareCluster作为Cluster默认实现，多注册中心之间的选择
                    // 对于对区域订阅方案，默认使用"zone-aware"区域
                    String cluster = registryURL.getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME);
                    // The invoker wrap sequence would be: ZoneAwareClusterInvoker(StaticDirectory) -> FailoverClusterInvoker(RegistryDirectory, routing happens here) -> Invoker
                    // invoker 包装顺序: ZoneAwareClusterInvoker(StaticDirectory) -> FailoverClusterInvoker(RegistryDirectory, routing happens here) -> Invoker
                    invoker = Cluster.getCluster(cluster, false).join(new StaticDirectory(registryURL, invokers));
                } else { // not a registry url, must be direct invoke.
                    // 如果不存在注册中心连接，只能使用直连
                    // 如果订阅区域未设置，则设置为默认区域"zone-aware"
                    String cluster = CollectionUtils.isNotEmpty(invokers)
                            ?
                            (invokers.get(0).getUrl() != null ? invokers.get(0).getUrl().getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME) :
                                    Cluster.DEFAULT)
                            : Cluster.DEFAULT;
                    // 创建 StaticDirectory 实例，并由 Cluster 对多个 Invoker 进行合并
                    invoker = Cluster.getCluster(cluster).join(new StaticDirectory(invokers));
                }
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }

        URL consumerURL = new URL(CONSUMER_PROTOCOL, map.remove(REGISTER_IP_KEY), 0, map.get(INTERFACE_KEY), map);
        MetadataUtils.publishServiceDefinition(consumerURL);

        // 通过ProxyFactory适配器选择合适的ProxyFactory扩展实现，创建代理对象
        return (T) PROXY_FACTORY.getProxy(invoker, ProtocolUtils.isGeneric(generic));
    }

    /**
     * 检查当前的服务是否可用
     * @throws IllegalStateException 如果服务不可能，将抛出此异常
     */
    private void checkInvokerAvailable() throws IllegalStateException {
        if (shouldCheck() && !invoker.isAvailable()) {
            // 销毁注册的消费者
            invoker.destroy();
            throw new IllegalStateException("Failed to check the status of the service "
                    + interfaceName
                    + ". No provider available for the service "
                    + (group == null ? "" : group + "/")
                    + interfaceName +
                    (version == null ? "" : ":" + version)
                    + " from the url "
                    + invoker.getUrl()
                    + " to the consumer "
                    + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
    }

    /**
     * This method should be called right after the creation of this class's instance, before any property in other config modules is used.
     * Check each config modules are created properly and override their properties if necessary.
     */
    public void checkAndUpdateSubConfigs() {
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        completeCompoundConfigs(consumer);
        // get consumer's global configuration
        checkDefault();

        // init some null configuration.
        List<ConfigInitializer> configInitializers = ExtensionLoader.getExtensionLoader(ConfigInitializer.class)
                .getActivateExtension(URL.valueOf("configInitializer://"), (String[]) null);
        configInitializers.forEach(e -> e.initReferConfig(this));

        this.refresh();
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        if (ProtocolUtils.isGeneric(generic)) {
            interfaceClass = GenericService.class;
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            checkInterfaceAndMethods(interfaceClass, getMethods());
        }

        initServiceMetadata(consumer);
        serviceMetadata.setServiceType(getActualInterface());
        // TODO, uncomment this line once service key is unified
        serviceMetadata.setServiceKey(URL.buildKey(interfaceName, group, version));

        ServiceRepository repository = ApplicationModel.getServiceRepository();
        ServiceDescriptor serviceDescriptor = repository.registerService(interfaceClass);
        repository.registerConsumer(
                serviceMetadata.getServiceKey(),
                serviceDescriptor,
                this,
                null,
                serviceMetadata);

        resolveFile();
        ConfigValidationUtils.validateReferenceConfig(this);
        postProcessConfig();
    }


    /**
     * Figure out should refer the service in the same JVM from configurations. The default behavior is true
     * 1. if injvm is specified, then use it
     * 2. then if a url is specified, then assume it's a remote call
     * 3. otherwise, check scope parameter
     * 4. if scope is not specified but the target service is provided in the same JVM, then prefer to make the local
     * call, which is the default behavior
     */
    protected boolean shouldJvmRefer(Map<String, String> map) {
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        boolean isJvmRefer;
        if (isInjvm() == null) {
            // if a url is specified, don't do local reference
            if (url != null && url.length() > 0) {
                isJvmRefer = false;
            } else {
                // by default, reference local service if there is
                isJvmRefer = InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl);
            }
        } else {
            isJvmRefer = isInjvm();
        }
        return isJvmRefer;
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.5
     */
    protected void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = ExtensionLoader.getExtensionLoader(ConfigPostProcessor.class)
                .getActivateExtension(URL.valueOf("configPostProcessor://"), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessReferConfig(this));
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }
}
