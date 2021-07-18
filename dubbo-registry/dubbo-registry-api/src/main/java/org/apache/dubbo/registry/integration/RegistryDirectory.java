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
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.AddressListener;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.DISABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.APP_DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTE_PROTOCOL;
import static org.apache.dubbo.registry.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.rpc.cluster.Constants.ROUTER_KEY;


/**
 * RegistryDirectory
 *
 * 一个动态的 Directory 实现，实现了 NotifyListener 接口，当注册中心的服务配置发生变化时，RegistryDirectory 会收到变更通知，
 * 然后RegistryDirectory 会根据注册中心推送的通知，动态增删底层 Invoker 集合。
 *
 */
public class RegistryDirectory<T> extends DynamicDirectory<T> {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    private static final ConsumerConfigurationListener CONSUMER_CONFIGURATION_LISTENER = new ConsumerConfigurationListener();
    private ReferenceConfigurationListener referenceConfigurationListener;

    // Map<url, Invoker> cache service url to invoker mapping.
    // The initial value is null and the midway may be assigned to null, please use the local variable reference

    /**
     * Provider URL 与对应 Invoker 之间的映射，该集合会与 invokers 字段同时动态更新。
     * 初始值为null，中间可能赋值为null，请使用局部变量引用。
     */
    protected volatile Map<URL, Invoker<T>> urlInvokerMap;
    // The initial value is null and the midway may be assigned to null, please use the local variable reference

    /**
     * 当前缓存的所有 Provider 的 URL，该集合会与 invokers 字段同时动态更新。
     * 初始值为null，中间可能赋值为null，请使用局部变量引用
     */
    protected volatile Set<URL> cachedInvokerUrls;

    public RegistryDirectory(Class<T> serviceType, URL url) {
        // 会根据传入的注册中心 URL 初始化一些核心字段。
        // 入口在 org.apache.dubbo.registry.integration.RegistryProtocol.doRefer(Cluster cluster, Registry registry, Class<T> type, URL url)
        super(serviceType, url);
    }

    // 在完成初始化之后，我们来看 subscribe() 方法，该方法会在 Consumer 进行订阅的时候被调用，其中调用 Registry 的 subscribe() 完成订阅操作，
    // 同时还会将当前 RegistryDirectory 对象作为 NotifyListener 监听器添加到 Registry 中
    @Override
    public void subscribe(URL url) {
        // 设置消费者 URL
        setConsumerUrl(url);
        // 将当前RegistryDirectory对象作为ConfigurationListener记录到CONSUMER_CONFIGURATION_LISTENER中
        CONSUMER_CONFIGURATION_LISTENER.addNotifyListener(this);
        referenceConfigurationListener = new ReferenceConfigurationListener(this, url);
        // 向注册中心，发起订阅
        registry.subscribe(url, this);
    }

    @Override
    public void unSubscribe(URL url) {
        setConsumerUrl(null);
        CONSUMER_CONFIGURATION_LISTENER.removeNotifyListener(this);
        referenceConfigurationListener.stop();
        registry.unsubscribe(url, this);
    }

    // 在 RegistryDirectory.notify() 方法中，首先会按照 category 对发生变化的 URL 进行分类，分成 configurators、routers、providers 三类，并分别对不同类型的 URL 进行处理
    // 将 configurators 类型的 URL 转化为 Configurator，保存到 configurators 字段中
    // 将 router 类型的 URL 转化为 Router，并通过 routerChain.addRouters() 方法添加 routerChain 中保存
    // 将 provider 类型的 URL 转化为 Invoker 对象，并记录到 invokers 集合和 urlInvokerMap 集合中
    @Override
    public synchronized void notify(List<URL> urls) {
        // 按照category进行分类，分成configurators、routers、providers三类
        Map<String, List<URL>> categoryUrls = urls.stream()
                .filter(Objects::nonNull)
                .filter(this::isValidCategory)
                .filter(this::isNotCompatibleFor26x)
                .collect(Collectors.groupingBy(this::judgeCategory));

        // 获取configurators类型的URL，并转换成Configurator对象
        List<URL> configuratorURLs = categoryUrls.getOrDefault(CONFIGURATORS_CATEGORY, Collections.emptyList());
        this.configurators = Configurator.toConfigurators(configuratorURLs).orElse(this.configurators);

        // 获取routers类型的URL，并转成Router对象，添加到RouterChain中
        List<URL> routerURLs = categoryUrls.getOrDefault(ROUTERS_CATEGORY, Collections.emptyList());
        toRouters(routerURLs).ifPresent(this::addRouters);

        // providers
        // 获取providers类型的URL，调用refreshOverrideAndInvoker()方法进行处理
        List<URL> providerURLs = categoryUrls.getOrDefault(PROVIDERS_CATEGORY, Collections.emptyList());
        /**
         * 3.x added for extend URL address
         * 在Dubbo3.0中会触发AddressListener监听器
         */
        ExtensionLoader<AddressListener> addressListenerExtensionLoader = ExtensionLoader.getExtensionLoader(AddressListener.class);
        List<AddressListener> supportedListeners = addressListenerExtensionLoader.getActivateExtension(getUrl(), (String[]) null);
        if (supportedListeners != null && !supportedListeners.isEmpty()) {
            for (AddressListener addressListener : supportedListeners) {
                providerURLs = addressListener.notify(providerURLs, getConsumerUrl(),this);
            }
        }
        // 刷新Provider
        refreshOverrideAndInvoker(providerURLs);
    }

    private String judgeCategory(URL url) {
        if (UrlUtils.isConfigurator(url)) {
            return CONFIGURATORS_CATEGORY;
        } else if (UrlUtils.isRoute(url)) {
            return ROUTERS_CATEGORY;
        } else if (UrlUtils.isProvider(url)) {
            return PROVIDERS_CATEGORY;
        }
        return "";
    }

    // RefreshOverrideAndInvoker will be executed by registryCenter and configCenter, so it should be synchronized.
    private synchronized void refreshOverrideAndInvoker(List<URL> urls) {
        // mock zookeeper://xxx?mock=return null
        overrideDirectoryUrl();
        refreshInvoker(urls);
    }

    /**
     * 将 invokerURL 列表转换为 Invoker Map。转换规则如下:
     * <ol>
     * <li> 如果 URL 已经转换为 invoker，则不再重新引用，直接从缓存中获取，并注意 URL 中的任何参数更改都会被重新引用.</li>
     * <li>如果传入的调用者列表不为空，则表示它是最新的调用者列表.</li>
     * <li>如果传入的invokerUrl列表为空，表示该规则只是override规则或route规则，需要重新对比决定是否重新引用.</li>
     * </ol>
     *
     * @param invokerUrls 传入的参数不能为空
     */
    private void refreshInvoker(List<URL> invokerUrls) {
        Assert.notNull(invokerUrls, "invokerUrls should not be null");

        // 如果invokerUrls集合不为空，长度为1，并且协议为empty，
        // 则表示该服务的所有Provider都下线了，会销毁当前所有Provider对应的Invoker。
        if (invokerUrls.size() == 1
                && invokerUrls.get(0) != null
                && EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            // forbidden标记设置为true，后续请求将直接抛出异常
            this.forbidden = true; // Forbid to access
            // 设置空列表
            this.invokers = Collections.emptyList();
            // 清空RouterChain中的Invoker集合
            routerChain.setInvokers(this.invokers);
            // 关闭所有Invoker对象
            destroyAllInvokers(); // Close all invokers
        } else {
            // forbidden标记设置为false，RegistryDirectory可以正常处理后续请求
            this.forbidden = false; // Allow to access
            // 保存本地引用
            Map<URL, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
            if (invokerUrls == Collections.<URL>emptyList()) {
                invokerUrls = new ArrayList<>();
            }
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                // 如果invokerUrls集合为空，并且cachedInvokerUrls不为空，则将使用cachedInvokerUrls缓存的数据，
                // 也就是说注册中心中的providers目录未发生变化，invokerUrls则为空，表示cachedInvokerUrls集合中缓存的URL为最新的值
                invokerUrls.addAll(this.cachedInvokerUrls);
            } else {
                // 如果invokerUrls集合不为空，则用invokerUrls集合更新cachedInvokerUrls集合
                // 也就是说，providers发生变化，invokerUrls集合中会包含此时注册中心所有的服务提供者
                this.cachedInvokerUrls = new HashSet<>();
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
            }
            if (invokerUrls.isEmpty()) {
                // 如果invokerUrls集合为空，即providers目录未发生变更，则无须处理，结束本次更新服务提供者Invoker操作。
                return;
            }
            // 将invokerUrls转换为对应的Invoker映射关系
            Map<URL, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map

            /**
             * 如果计算错误，则不进行处理。
             *
             * 1. 客户端配置的协议与服务器的协议不一致。 例如：consumer protocol = dubbo，provider只有其他协议服务（rest）。
             * 2. 注册中心不健壮，推送非法规格数据。
             *
             */
            if (CollectionUtils.isEmptyMap(newUrlInvokerMap)) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls
                        .toString()));
                return;
            }

            // 更新invokers字段和urlInvokerMap集合
            List<Invoker<T>> newInvokers = Collections.unmodifiableList(new ArrayList<>(newUrlInvokerMap.values()));
            // pre-route and build cache, notice that route cache should build on original Invoker list.
            // toMergeMethodInvokerMap() will wrap some invokers having different groups, those wrapped invokers not should be routed.
            routerChain.setInvokers(newInvokers);
            // 针对multiGroup的特殊处理，合并多个group的Invoker
            this.invokers = multiGroup ? toMergeInvokerList(newInvokers) : newInvokers;
            this.urlInvokerMap = newUrlInvokerMap;

            try {
                // 比较新旧两组Invoker集合，销毁掉已经下线的Invoker
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }

        // notify invokers refreshed
        this.invokersChanged();
    }

    /**
     * 完成 URL 到 Invoker 对象的转换（toInvokers() 方法）之后，其实在 refreshInvoker() 方法的最后，
     * 还会根据 multiGroup 的配置决定是否调用 toMergeInvokerList() 方法将每个 group 中的 Invoker 合并成一个 Invoker。
     * @param invokers Invoker列表
     * @return List<Invoker<T>>
     */
    private List<Invoker<T>> toMergeInvokerList(List<Invoker<T>> invokers) {
        List<Invoker<T>> mergedInvokers = new ArrayList<>();
        Map<String, List<Invoker<T>>> groupMap = new HashMap<>();
        // 按照group将Invoker分组
        for (Invoker<T> invoker : invokers) {
            String group = invoker.getUrl().getParameter(GROUP_KEY, "");
            groupMap.computeIfAbsent(group, k -> new ArrayList<>());
            groupMap.get(group).add(invoker);
        }

        // 如果只有一个group，则直接使用该group分组对应的Invoker集合作为mergedInvokers
        if (groupMap.size() == 1) {
            mergedInvokers.addAll(groupMap.values().iterator().next());
        } else if (groupMap.size() > 1) { // 将每个group对应的Invoker集合合并成一个Invoker
            for (List<Invoker<T>> groupList : groupMap.values()) {
                // 这里使用到StaticDirectory以及Cluster合并每个group中的Invoker
                StaticDirectory<T> staticDirectory = new StaticDirectory<>(groupList);
                staticDirectory.buildRouterChain();
                mergedInvokers.add(CLUSTER.join(staticDirectory));
            }
        } else {
            mergedInvokers = invokers;
        }
        return mergedInvokers;
    }

    /**
     * toRouters 最核心的代码就是 Router router = ROUTER_FACTORY.getRouter(url) 创建路由规则。路由规则类型是由 url.router 决定的。
     *
     * @param urls 路由规则URL集合
     * @return null : no routers ,do nothing else :routers list
     */
    private Optional<List<Router>> toRouters(List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return Optional.empty();
        }

        List<Router> routers = new ArrayList<>();
        for (URL url : urls) {
            // empty不用管
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) {
                continue;
            }
            // 将 url.router 参数设置为 protocol
            String routerType = url.getParameter(ROUTER_KEY);
            if (routerType != null && routerType.length() > 0) {
                url = url.setProtocol(routerType);
            }
            try {
                // 会适配对应的路由工厂类来创建Router对象
                Router router = ROUTER_FACTORY.getRouter(url);
                if (!routers.contains(router)) {
                    routers.add(router);
                }
            } catch (Throwable t) {
                logger.error("convert router url to router error, url: " + url, t);
            }
        }

        return Optional.of(routers);
    }

    /**
     * 将 url 转为调用者，如果 url 已被引用，则不会重新引用。
     * 通过对 refreshInvoker() 方法的介绍，我们可以看出，其最核心的逻辑是 Provider URL 转换成 Invoker 对象，也就是 toInvokers() 方法。
     *
     * 核心逻辑就是调用 Protocol.refer() 方法创建 Invoker 对象，其他的逻辑都是在判断是否调用该方法。
     *
     * 在 toInvokers() 方法内部，我们可以看到调用了 mergeUrl() 方法对 URL 参数进行合并。在 mergeUrl() 方法中，
     * 会将注册中心中 configurators 目录下的 URL（override 协议），以及服务治理控制台动态添加的配置与 Provider URL 进行合并，
     * 即覆盖 Provider URL 原有的一些信息。
     *
     * @param urls Provider URL
     * @return invokers
     */
    private Map<URL, Invoker<T>> toInvokers(List<URL> urls) {
        Map<URL, Invoker<T>> newUrlInvokerMap = new ConcurrentHashMap<>();
        if (CollectionUtils.isEmpty(urls)) {
            // urls集合为空时，直接返回空Map
            return newUrlInvokerMap;
        }
        Set<URL> keys = new HashSet<>();
        // 获取Consumer端支持的协议，即protocol参数指定的协议
        String queryProtocols = this.queryMap.get(PROTOCOL_KEY);
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            // 如果在消费端配置了协议，则只选择匹配的协议
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                String[] acceptProtocols = queryProtocols.split(",");
                // 遍历所有Consumer端支持的协议
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                // 如果当前URL不支持Consumer端的协议，也就无法执行后续转换成Invoker的逻辑
                if (!accept) {
                    continue;
                }
            }
            // 跳过empty协议的URL
            if (EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }
            // 如果Consumer端不支持该URL的协议（这里通过SPI方式检测是否有对应的Protocol扩展实现），也会跳过该URL
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() +
                        " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() +
                        " to consumer " + NetUtils.getLocalHost() + ", supported protocol: " +
                        ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }
            // 合并URL参数
            URL url = mergeUrl(providerUrl);
            // 跳过重复的URL
            if (keys.contains(url)) { // Repeated url
                continue;
            }
            // 记录key
            keys.add(url);
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            // 匹配urlInvokerMap缓存中的Invoker对象，如果命中缓存，直接将Invoker添加到newUrlInvokerMap这个新集合中即可；
            // 如果未命中缓存，则创建新的Invoker对象，然后添加到newUrlInvokerMap这个新集合中
            Map<URL, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(url);
            if (invoker == null) { // Not in the cache, refer again
                try {
                    boolean enabled = true;
                    // 检测URL中的disable和enable参数，决定是否能够创建Invoker对象
                    if (url.hasParameter(DISABLED_KEY)) {
                        enabled = !url.getParameter(DISABLED_KEY, false);
                    } else {
                        enabled = url.getParameter(ENABLED_KEY, true);
                    }
                    if (enabled) {
                        // 这里通过Protocol.refer()方法创建对应的Invoker对象
                        invoker = new InvokerDelegate<>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                if (invoker != null) { // Put new invoker in cache
                    // 将key和Invoker对象之间的映射关系记录到newUrlInvokerMap中
                    newUrlInvokerMap.put(url, invoker);
                }
            } else {
                // 缓存命中，直接将urlInvokerMap中的Invoker转移到newUrlInvokerMap即可
                newUrlInvokerMap.put(url, invoker);
            }
        }
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        // 首先，移除Provider URL中只在Provider端生效的属性，例如，threadname、threadpool、corethreads、threads、queues等参数。
        // 然后，用Consumer端的配置覆盖Provider URL的相应配置，其中，version、group、methods、timestamp等参数以Provider端的配置优先
        // 最后，合并Provider端和Consumer端配置的Filter以及Listener
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters

        // 合并configurators类型的URL，configurators类型的URL又分为三类：
        // 第一类是注册中心Configurators目录下新增的URL(override协议)
        // 第二类是通过ConsumerConfigurationListener监听器(监听应用级别的配置)得到的动态配置
        // 第三类是通过ReferenceConfigurationListener监听器(监听服务级别的配置)得到的动态配置
        // 这里只需要先了解：除了注册中心的configurators目录下有配置信息之外，还有可以在服务治理控制台动态添加配置，
        // ConsumerConfigurationListener、ReferenceConfigurationListener监听器就是用来监听服务治理控制台的动态配置的
        providerUrl = overrideWithConfigurator(providerUrl);

        // 增加check=false，即只有在调用时，才检查Provider是否可用
        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
//        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

        if ((providerUrl.getPath() == null || providerUrl.getPath()
                .length() == 0) && DUBBO_PROTOCOL.equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    private URL overrideWithConfigurator(URL providerUrl) {
        // override url with configurator from "override://" URL for dubbo 2.6 and before
        providerUrl = overrideWithConfigurators(this.configurators, providerUrl);

        // override url with configurator from configurator from "app-name.configurators"
        providerUrl = overrideWithConfigurators(CONSUMER_CONFIGURATION_LISTENER.getConfigurators(), providerUrl);

        // override url with configurator from configurators from "service-name.configurators"
        if (referenceConfigurationListener != null) {
            providerUrl = overrideWithConfigurators(referenceConfigurationListener.getConfigurators(), providerUrl);
        }

        return providerUrl;
    }

    private URL overrideWithConfigurators(List<Configurator> configurators, URL url) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    /**
     * Close all invokers
     */
    @Override
    protected void destroyAllInvokers() {
        Map<URL, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }
        invokers = null;
        cachedInvokerUrls = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     *
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    private void destroyUnusedInvokers(Map<URL, Invoker<T>> oldUrlInvokerMap, Map<URL, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        List<URL> deleted = null;
        if (oldUrlInvokerMap != null) {
            for (Map.Entry<URL, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                if (!newUrlInvokerMap.containsKey(entry.getKey())) {
                    if (deleted == null) {
                        deleted = new ArrayList<>();
                    }
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            for (URL url : deleted) {
                if (url != null) {
                    Invoker<T> invoker = oldUrlInvokerMap.get(url);
                    if (invoker != null) {
                        try {
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] failed. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<URL, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        try {
            if (CollectionUtils.isNotEmptyMap(localUrlInvokerMap)
                    && localUrlInvokerMap.values().stream().anyMatch(Invoker::isAvailable)) {
                return true;
            }
        } catch (Throwable throwable) {
            return true;
        }
        return false;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<URL, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    private boolean isValidCategory(URL url) {
        String category = url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
        if ((ROUTERS_CATEGORY.equals(category) || ROUTE_PROTOCOL.equals(url.getProtocol())) ||
                PROVIDERS_CATEGORY.equals(category) ||
                CONFIGURATORS_CATEGORY.equals(category) || DYNAMIC_CONFIGURATORS_CATEGORY.equals(category) ||
                APP_DYNAMIC_CONFIGURATORS_CATEGORY.equals(category)) {
            return true;
        }
        logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " +
                getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
        return false;
    }

    private boolean isNotCompatibleFor26x(URL url) {
        return StringUtils.isEmpty(url.getParameter(COMPATIBLE_CONFIG_KEY));
    }

    private void overrideDirectoryUrl() {
        // merge override parameters
        this.overrideDirectoryUrl = directoryUrl;
        List<Configurator> localConfigurators = this.configurators; // local reference
        doOverrideUrl(localConfigurators);
        List<Configurator> localAppDynamicConfigurators = CONSUMER_CONFIGURATION_LISTENER.getConfigurators(); // local reference
        doOverrideUrl(localAppDynamicConfigurators);
        if (referenceConfigurationListener != null) {
            List<Configurator> localDynamicConfigurators = referenceConfigurationListener.getConfigurators(); // local reference
            doOverrideUrl(localDynamicConfigurators);
        }
    }

    private void doOverrideUrl(List<Configurator> configurators) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }
    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     *
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }

    private static class ReferenceConfigurationListener extends AbstractConfiguratorListener {
        private RegistryDirectory directory;
        private URL url;

        ReferenceConfigurationListener(RegistryDirectory directory, URL url) {
            this.directory = directory;
            this.url = url;
            this.initWith(DynamicConfiguration.getRuleKey(url) + CONFIGURATORS_SUFFIX);
        }

        void stop() {
            this.stopListen(DynamicConfiguration.getRuleKey(url) + CONFIGURATORS_SUFFIX);
        }

        @Override
        protected void notifyOverrides() {
            // to notify configurator/router changes
            directory.refreshOverrideAndInvoker(Collections.emptyList());
        }
    }

    private static class ConsumerConfigurationListener extends AbstractConfiguratorListener {
        List<RegistryDirectory> listeners = new ArrayList<>();

        ConsumerConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        void addNotifyListener(RegistryDirectory listener) {
            this.listeners.add(listener);
        }

        void removeNotifyListener(RegistryDirectory listener) {
            this.listeners.remove(listener);
        }

        @Override
        protected void notifyOverrides() {
            listeners.forEach(listener -> listener.refreshOverrideAndInvoker(Collections.emptyList()));
        }
    }

}
