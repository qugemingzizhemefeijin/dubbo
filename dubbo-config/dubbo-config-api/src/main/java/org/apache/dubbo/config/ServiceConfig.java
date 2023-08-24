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
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ServiceConfigExportedEvent;
import org.apache.dubbo.config.event.ServiceConfigUnexportedEvent;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.MAPPING_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_TYPE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_TYPE;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_BIND;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.MULTICAST;
import static org.apache.dubbo.config.Constants.SCOPE_NONE;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;

public class ServiceConfig<T> extends ServiceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ServiceConfig.class);

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService DELAY_EXPORT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    private String serviceName;

    private static final Protocol PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * Whether the provider has been exported
     */
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    private transient volatile boolean unexported;

    private DubboBootstrap bootstrap;

    /**
     * The exported services
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    private static final String CONFIG_INITIALIZER_PROTOCOL = "configInitializer://";

    private static final String TRUE_VALUE = "true";

    private static final String FALSE_VALUE = "false";

    private static final String STUB_SUFFIX = "Stub";

    private static final String LOCAL_SUFFIX = "Local";

    private static final String CONFIG_POST_PROCESSOR_PROTOCOL = "configPostProcessor://";

    private static final String RETRY_SUFFIX = ".retry";

    private static final String RETRIES_SUFFIX = ".retries";

    private static final String ZERO_VALUE = "0";

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        super(service);
    }

    @Parameter(excluded = true)
    @Override
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    @Override
    public boolean isUnexported() {
        return unexported;
    }

    @Override
    public void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occurred when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;

        // dispatch a ServiceConfigUnExportedEvent since 2.7.4
        dispatch(new ServiceConfigUnexportedEvent(this));
    }

    /**
     * 服务发布的第一步是检查参数，
     * 第二步会根据当前配置决定是延迟发布还是立即调用 doExport() 方法进行发布，
     * 第三步会通过 exported() 方法回调相关监听器
     */
    @Override
    public synchronized void export() {
        if (bootstrap == null) {
            bootstrap = DubboBootstrap.getInstance();
            bootstrap.initialize();
        }
        // 检查并更新各项配置
        checkAndUpdateSubConfigs();

        // 初始化元数据相关服务
        initServiceMetadata(provider);
        serviceMetadata.setServiceType(getInterfaceClass());
        serviceMetadata.setTarget(getRef());
        serviceMetadata.generateServiceKey();

        if (!shouldExport()) {
            return;
        }

        // 延迟发布
        if (shouldDelay()) {
            DELAY_EXPORT_EXECUTOR.schedule(this::doExport, getDelay(), TimeUnit.MILLISECONDS);
        } else {
            // 立即发布
            doExport();
        }
        // 回调监听器
        exported();
    }

    public void exported() {
        List<URL> exportedURLs = this.getExportedUrls();
        exportedURLs.forEach(url -> {
            // dubbo2.7.x does not register serviceNameMapping information with the registry by default.
            // Only when the user manually turns on the service introspection, can he register with the registration center.
            boolean isServiceDiscovery = UrlUtils.isServiceDiscoveryRegistryType(url);
            if (isServiceDiscovery) {
                Map<String, String> parameters = getApplication().getParameters();
                ServiceNameMapping.getExtension(parameters != null ? parameters.get(MAPPING_KEY) : null).map(url);
            }
        });
        // dispatch a ServiceConfigExportedEvent since 2.7.4
        dispatch(new ServiceConfigExportedEvent(this));
    }

    /**
     * 检查各项配置是否合理，并补齐一些缺省的配置信息
     */
    private void checkAndUpdateSubConfigs() {
        // Use default configs defined explicitly with global scope
        completeCompoundConfigs();
        checkDefault();
        checkProtocol();
        // init some null configuration.
        List<ConfigInitializer> configInitializers = ExtensionLoader.getExtensionLoader(ConfigInitializer.class)
                .getActivateExtension(URL.valueOf(CONFIG_INITIALIZER_PROTOCOL), (String[]) null);
        configInitializers.forEach(e -> e.initServiceConfig(this));

        // if protocol is not injvm checkRegistry
        if (!isOnlyInJvm()) {
            checkRegistry();
        }
        this.refresh();

        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = TRUE_VALUE;
            }
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            checkInterfaceAndMethods(interfaceClass, getMethods());
            checkRef();
            generic = FALSE_VALUE;
        }
        if (local != null) {
            if (TRUE_VALUE.equals(local)) {
                local = interfaceName + LOCAL_SUFFIX;
            }
            Class<?> localClass;
            try {
                localClass = ClassUtils.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException(
                        "The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        if (stub != null) {
            if (TRUE_VALUE.equals(stub)) {
                stub = interfaceName + STUB_SUFFIX;
            }
            Class<?> stubClass;
            try {
                stubClass = ClassUtils.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException(
                        "The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        checkStubAndLocal(interfaceClass);
        ConfigValidationUtils.checkMock(interfaceClass, this);
        ConfigValidationUtils.validateServiceConfig(this);
        postProcessConfig();
    }

    /**
     * 发布服务
     */
    protected synchronized void doExport() {
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        if (exported) {
            return;
        }
        exported = true;

        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        doExportUrls();
        bootstrap.setReady(true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        ServiceRepository repository = ApplicationModel.getServiceRepository();
        ServiceDescriptor serviceDescriptor = repository.registerService(getInterfaceClass());
        repository.registerProvider(
                getUniqueServiceName(),
                ref,
                serviceDescriptor,
                this,
                serviceMetadata
        );

        // 将注册中心的 RegistryConfig 配置解析成 registryUrl
        List<URL> registryURLs = ConfigValidationUtils.loadRegistries(this, true);

        // 遍历支持的所有协议，依次在每个注册中心发布服务
        int protocolConfigNum = protocols.size();
        for (ProtocolConfig protocolConfig : protocols) {
            String pathKey = URL.buildKey(getContextPath(protocolConfig)
                    .map(p -> p + "/" + path)
                    .orElse(path), group, version);
            // In case user specified path, register service one more time to map it to path.
            repository.registerService(pathKey, interfaceClass);
            // 在每个注册中心发布服务
            doExportUrlsFor1Protocol(protocolConfig, registryURLs, protocolConfigNum);
        }
    }

    /**
     * 将支持的协议依次发布在registryURLs中的注册中心
     * <p>代码很长，其中分成两部分：一部分是组装服务的 URL，另一部分就是后面紧接着介绍的服务发布。
     * <p>组装服务的 URL核心步骤有如下 7 步：
     * <ol>
     * <li>获取此次发布使用的协议，默认使用 dubbo 协议。</li>
     * <li>设置服务 URL 中的参数，这里会从 MetricsConfig、ApplicationConfig、ModuleConfig、ProviderConfig、ProtocolConfig
     * 中获取配置信息，并作为参数添加到 URL 中。这里调用的 appendParameters() 方法会将 AbstractConfig 中的配置信息存储到 Map 集合中，
     * 后续在构造 URL 的时候，会将该集合中的 KV 作为 URL 的参数。</li>
     * <li>解析指定方法的 MethodConfig 配置以及方法参数的 ArgumentConfig 配置，得到的配置信息也是记录到 Map 集合中，后续作为 URL 参数。</li>
     * <li>根据此次调用是泛化调用还是普通调用，向 Map 集合中添加不同的键值对。</li>
     * <li>获取 token 配置，并添加到 Map 集合中，默认随机生成 UUID。</li>
     * <li>获取 host、port 值，并开始组装服务的 URL。</li>
     * <li>根据 Configurator 覆盖或新增 URL 参数。</li>
     * </ol>
     *
     * <p>完成了服务 URL 的组装之后，doExportUrlsFor1Protocol() 方法开始执行服务发布。
     * 服务发布可以分为远程发布和本地发布，具体发布方式与服务 URL 中的 scope 参数有关。
     *
     * <p>scope 参数有三个可选值，分别是 none、remote 和 local，分别代表不发布、发布到本地和发布到远端注册中心。
     * <ol>
     * <li>发布到本地的条件是 scope != remote；</li>
     * <li>发布到注册中心的条件是 scope != local</li>
     * <li>scope 参数的默认值为 null，也就是说，默认会同时在本地和注册中心发布该服务</li>
     * </ol>
     *
     * @param protocolConfig    协议配置对象
     * @param registryURLs      注册中心列表
     * @param protocolConfigNum 协议配置总数
     */
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs, int protocolConfigNum) {
        //首先是将一些信息，比如版本、时间戳、方法名以及各种配置对象的字段信息放入到 map 中
        //map 中的内容将作为 URL 的查询字符串。构建好 map 后，紧接着是获取上下文路径、主机名以及端口号等信息。
        //最后将 map 和主机名等数据传给 URL 构造方法创建 URL 对象
        String name = protocolConfig.getName();
        // 如果协议名为空，或空串，则将协议名变量设置为 dubbo
        if (StringUtils.isEmpty(name)) {
            // <dubbo:protocol name=""/>默认为dubbo
            name = DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>();
        // 添加 side、版本、时间戳以及进程号等信息到 map 中
        map.put(SIDE_KEY, PROVIDER_SIDE);

        ServiceConfig.appendRuntimeParameters(map);
        // 通过反射将对象的字段信息添加到 map 中
        AbstractConfig.appendParameters(map, getMetrics());
        AbstractConfig.appendParameters(map, getApplication());
        AbstractConfig.appendParameters(map, getModule());
        // remove 'default.' prefix for configs from ProviderConfig
        // appendParameters(map, provider, Constants.DEFAULT_KEY);
        AbstractConfig.appendParameters(map, provider);
        AbstractConfig.appendParameters(map, protocolConfig);
        AbstractConfig.appendParameters(map, this);
        MetadataReportConfig metadataReportConfig = getMetadataReportConfig();
        if (metadataReportConfig != null && metadataReportConfig.isValid()) {
            map.putIfAbsent(METADATA_KEY, REMOTE_METADATA_STORAGE_TYPE);
        }
        // methods 为 MethodConfig 集合，MethodConfig 中存储了 <dubbo:method> 标签的配置信息
        if (CollectionUtils.isNotEmpty(getMethods())) {
            // 检测 <dubbo:method> 标签中的配置信息，并将相关配置添加到 map 中
            for (MethodConfig method : getMethods()) {
                AbstractConfig.appendParameters(map, method, method.getName());
                String retryKey = method.getName() + RETRY_SUFFIX;
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if (FALSE_VALUE.equals(retryValue)) {
                        map.put(method.getName() + RETRIES_SUFFIX, ZERO_VALUE);
                    }
                }
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            if (methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        if (argument.getIndex() != -1) {
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                AbstractConfig
                                                        .appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException(
                                                        "Argument config error : the index attribute and type attribute not match :index :" +
                                                                argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    AbstractConfig.appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException(
                                                                "Argument config error : the index attribute and type attribute not match :index :" +
                                                                        argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            AbstractConfig.appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException(
                                    "Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        // genericService设置
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(GENERIC_KEY, generic);
            map.put(METHODS_KEY, ANY_VALUE);
        } else {
            // 接口版本号，跟随jar包 例如：1.0-SNAPSHOT
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);
            }
            // 接口的方法名数组
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                // 空方法标志map.put("methods","*")
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                // 方法数组字符串map.put("methods","insert,update")
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }

        /**
         * Here the token value configured by the provider is used to assign the value to ServiceConfig#token
         * Service的token验证
         */
        if (ConfigUtils.isEmpty(token) && provider != null) {
            token = provider.getToken();
        }
        // 是否有token配置 将token配置到map
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put(TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(TOKEN_KEY, token);
            }
        }
        //init serviceMetadata attachments
        // map放入到serviceMetadata中
        serviceMetadata.getAttachments().putAll(map);

        // export service
        // 获取协议host，默认获取本机ip <dubbo:protocol host="" />
        String host = findConfigedHosts(protocolConfig, registryURLs, map);
        // 获取协议port，dubbo默认20880 <dubbo:protocol port="" />
        Integer port = findConfigedPorts(protocolConfig, name, map, protocolConfigNum);
        // 生成url格式如下
        // dubbo://127.0.0.1:20880/org.service.DemoService?anyhost=true&application=dubbo-demo&bind.ip=192.168.56.1&
        // bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.service.DemoService&
        // methods=getUserById,update&pid=13252&release=2.7.5&revision=1.0-SNAPSHOT&side=provider&timestamp=1584192937036
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);

        // You can customize Configurator to append extra parameters
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }
        // 开始服务发布
        // scope 参数有三个可选值，分别是 none、remote 和 local，分别代表不发布、发布到本地和发布到远端注册中心
        // 发布到本地的条件是 scope != remote
        // 发布到注册中心的条件是 scope != local
        // scope 参数的默认值为 null，也就是说，默认会同时在本地和注册中心发布该服务。

        // 从URL中获取scope参数，其中可选值有none、remote、local三个，
        // 分别代表不发布、发布到本地以及发布到远端，具体含义在下面一一介绍
        // 获取作用域scope属性
        String scope = url.getParameter(SCOPE_KEY);
        // don't export when none is configured
        // scope == "none",不注册，一般为null
        // scope不为none，才进行发布
        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {

            // export to local if the config is not remote (export to remote only when config is remote)
            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                // scope != "remote" ,注册到本地，一般为null会注册到本地
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            // 发布到远端的注册中心
            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                // scope != "local" ,注册到注册表，一般为null会注册到注册表
                if (CollectionUtils.isNotEmpty(registryURLs)) { // 当前配置了至少一个注册中心
                    for (URL registryURL : registryURLs) { // 向每个注册中心发布服务
                        if (SERVICE_REGISTRY_PROTOCOL.equals(registryURL.getProtocol())) {
                            url = url.addParameterIfAbsent(REGISTRY_TYPE_KEY, SERVICE_REGISTRY_TYPE);
                        }

                        //if protocol is only injvm ,not register
                        if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                            // injvm协议只在exportLocal()中有用，不会将服务发布到注册中心
                            // injvm,内部调用不注册
                            continue;
                        }
                        // url存在dynamic保留，不存在赋值为registryURL的dynamic属性值
                        url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));
                        // 创建monitorUrl，并作为monitor参数添加到服务URL中
                        URL monitorUrl = ConfigValidationUtils.loadMonitor(this, registryURL);
                        if (monitorUrl != null) {
                            url = url.addParameterAndEncoded(MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            if (url.getParameter(REGISTER_KEY, true)) {
                                logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " +
                                        registryURL);
                            } else {
                                logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                            }
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        // 设置服务URL的proxy参数，即生成动态代理方式(jdk或是javassist)，作为参数添加到RegistryURL中
                        String proxy = url.getParameter(PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(PROXY_KEY, proxy);
                        }
                        // 为服务实现类的对象创建相应的Invoker，getInvoker()方法的第三个参数中，会将服务URL作为export参数添加到RegistryURL中
                        // 这里的PROXY_FACTORY是ProxyFactory接口的适配器
                        // 创建一个AbstractProxyInvoker的子类实例new AbstractProxyInvoker(ref, interfaceClass, url)
                        Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass,
                                registryURL.addParameterAndEncoded(EXPORT_KEY, url.toFullString()));

                        // DelegateProviderMetaDataInvoker是个装饰类，
                        // 将当前ServiceConfig和Invoker关联起来而已，invoke()方法透传给底层Invoker对象
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        // 默认DubboProtocol.export注册到远程注册表zookeeper中
                        // 调用Protocol实现，进行发布
                        // 这里的PROTOCOL是Protocol接口的适配器
                        Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                        exporters.add(exporter);
                    }
                } else { // 不存在注册中心，仅导出服务
                    // 不存在注册中心，仅发布服务，不会将服务信息发布到注册中心。
                    // Consumer没法在注册中心找到该服务的信息，但是可以直连
                    // 具体的发布过程与上面的过程类似，只不过不会发布到注册中心
                    if (logger.isInfoEnabled()) {
                        logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                    }
                    Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                    exporters.add(exporter);
                }

                MetadataUtils.publishServiceDefinition(url);
            }
        }
        this.urls.add(url);
    }

    /**
     * always export injvm，将 Protocol 替换成 injvm 协议，将 host 设置成 127.0.0.1，将 port 设置为 0，得到新的 LocalURL
     *
     * <pre>
     * injvm://127.0.0.1/org.apache.dubbo.demo.DemoService?anyhost=true
     * &application=dubbo-demo-api-provider
     * &bind.ip=172.17.108.185
     * &bind.port=20880
     * &default=true
     * &deprecated=false
     * &dubbo=2.0.2
     * &dynamic=true
     * &generic=false
     * &interface=org.apache.dubbo.demo.DemoService
     * &methods=sayHello,sayHelloAsync
     * &pid=4249
     * &release=
     * &side=provider
     * &timestamp=1600440074214
     * </pre>
     *
     * 之后，会通过 ProxyFactory 接口适配器找到对应的 ProxyFactory 实现（默认使用 JavassistProxyFactory），
     * 并调用 getInvoker() 方法创建 Invoker 对象；最后，通过 Protocol 接口的适配器查找到 InjvmProtocol 实现，并调用 export() 方法进行发布。
     *
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        // 进行本地URL的构建
        URL local = URLBuilder.from(url)
                .setProtocol(LOCAL_PROTOCOL)
                .setHost(LOCALHOST_VALUE)
                .setPort(0)
                .build();
        // 根据本地的URL来实现对应的Invoker
        Exporter<?> exporter = PROTOCOL.export(
                PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, local));
        exporters.add(exporter);
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry url : " + local);
    }

    /**
     * Determine if it is injvm
     *
     * @return
     */
    private boolean isOnlyInJvm() {
        return getProtocols().size() == 1
                && LOCAL_PROTOCOL.equalsIgnoreCase(getProtocols().get(0).getName());
    }


    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * <p>
     * ServiceConfig类中的findConfigedHosts方法获取ip信息
     * <ul>
     *     <li>DUBBO_DUBBO_IP_TO_BIND 环境变量</li>
     *     <li>配置文件中：dubbo.protocol.host</li>
     *     <li>配置文件中：dubbo.provider.host</li>
     *     <li>InetAddress.getLocalHost().getHostAddress()</li>
     *     <li>通过socket连接注册到注册中心的URL，来得到IP</li>
     *     <li>通过遍历本机各个网卡，得到合适的网卡IP</li>
     *     <li></li>
     * </ul>
     * <p>
     * hostToRegistry(注册到注册中心的ip)的获取
     * <ul>
     *     <li>DUBBO_DUBBO_IP_TO_REGISTRY 环境变量</li>
     *     <li>hostToRegistry = hostToBind</li>
     * </ul>
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig,
                                     List<URL> registryURLs,
                                     Map<String, String> map) {
        boolean anyhost = false;

        // 如果协议是dubbo的话，获取环境中的DUBBO_DUBBO_IP_TO_BIND
        String hostToBind = getValueFromConfig(protocolConfig, DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            // 获取配置文件中：dubbo.protocol.host
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                // 获取配置文件中：dubbo.provider.host
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    logger.info("No valid ip found from environment, try to find valid host from DNS.");
                    // 利用Api来获取InetAddress.getLocalHost().getHostAddress()
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {
                    if (CollectionUtils.isNotEmpty(registryURLs)) {
                        for (URL registryURL : registryURLs) {
                            if (MULTICAST.equalsIgnoreCase(registryURL.getParameter(REGISTRY_KEY))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            try (Socket socket = new Socket()) {
                                // 通过socket连接注册到注册中心的URL，来得到IP
                                SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                socket.connect(addr, 1000);
                                hostToBind = socket.getLocalAddress().getHostAddress();
                                break;
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    if (isInvalidLocalHost(hostToBind)) {
                        // 通过遍历本机各个网卡，得到合适的网卡IP
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        map.put(BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        // 获取DUBBO_DUBBO_IP_TO_REGISTRY 环境变量
        String hostToRegistry = getValueFromConfig(protocolConfig, DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException(
                    "Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            // 如果DUBBO_DUBBO_IP_TO_REGISTRY 环境变量为空得话，那么就把hostToRegistry设为hostToBind
            hostToRegistry = hostToBind;
        }

        map.put(ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }


    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @param protocolConfigNum
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig,
                                      String name,
                                      Map<String, String> map, int protocolConfigNum) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }

        // registry port, not used as bind port by default
        String key = DUBBO_PORT_TO_REGISTRY;
        if (protocolConfigNum > 1) {
            key = getProtocolConfigId(protocolConfig).toUpperCase() + "_" + key;
        }
        String portToRegistryStr = getValueFromConfig(protocolConfig, key);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        // save bind port, used as url's key later
        map.put(BIND_PORT_KEY, String.valueOf(portToRegistry));

        return portToRegistry;
    }


    private String getProtocolConfigId(ProtocolConfig config) {
        return Optional.ofNullable(config.getId()).orElse(DUBBO);
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String value = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(value)) {
            value = ConfigUtils.getSystemProperty(key);
        }
        return value;
    }

    private Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = ExtensionLoader.getExtensionLoader(ConfigPostProcessor.class)
                .getActivateExtension(URL.valueOf(CONFIG_POST_PROCESSOR_PROTOCOL), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessServiceConfig(this));
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.5
     */
    private void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public String getServiceName() {

        if (!StringUtils.isBlank(serviceName)) {
            return serviceName;
        }
        String generateVersion = version;
        String generateGroup = group;

        if (StringUtils.isBlank(version) && provider != null) {
            generateVersion = provider.getVersion();
        }

        if (StringUtils.isBlank(group) && provider != null) {
            generateGroup = provider.getGroup();
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("ServiceBean:");

        if (!StringUtils.isBlank(generateGroup)) {
            stringBuilder.append(generateGroup);
        }

        stringBuilder.append("/").append(interfaceName);

        if (!StringUtils.isBlank(generateVersion)) {
            stringBuilder.append(":").append(generateVersion);
        }

        serviceName = stringBuilder.toString();
        return serviceName;
    }
}
