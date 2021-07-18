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
package org.apache.dubbo.rpc.cluster.configurator;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACES;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CONFIG_VERSION_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.OVERRIDE_PROVIDERS_KEY;

/**
 * AbstractOverrideConfigurator
 */
public abstract class AbstractConfigurator implements Configurator {

    private static final String TILDE = "~";

    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    @Override
    public URL configure(URL url) {
        // If override url is not enabled or is invalid, just return. 如果覆盖 url 未启用或无效，则返回。
        // 这里会根据配置URL的enabled参数以及host决定该URL是否可用，
        // 同时还会根据原始URL是否为空以及原始URL的host是否为空，决定当前是否执行后续覆盖逻辑
        if (!configuratorUrl.getParameter(ENABLED_KEY, true) || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }
        /*
         * This if branch is created since 2.7.0.
         */
        // 针对2.7.0之后版本，这里添加了一个configVersion参数作为区分
        String apiVersion = configuratorUrl.getParameter(CONFIG_VERSION_KEY);
        // 对2.7.0之后版本的配置处理
        if (StringUtils.isNotEmpty(apiVersion)) {
            String currentSide = url.getParameter(SIDE_KEY);
            String configuratorSide = configuratorUrl.getParameter(SIDE_KEY);
            // 根据配置URL中的side参数以及原始URL中的side参数值进行匹配
            if (currentSide.equals(configuratorSide) && CONSUMER.equals(configuratorSide) && 0 == configuratorUrl.getPort()) {
                url = configureIfMatch(NetUtils.getLocalHost(), url);
            } else if (currentSide.equals(configuratorSide) && PROVIDER.equals(configuratorSide) &&
                    url.getPort() == configuratorUrl.getPort()) {
                url = configureIfMatch(url.getHost(), url);
            }
        }
        /*
         * This else branch is deprecated and is left only to keep compatibility with versions before 2.7.0
         */
        else {
            // 2.7.0版本之前对配置的处理
            url = configureDeprecated(url);
        }
        return url;
    }

    /**
     * <p>兼容2.7.0之前的Dubbo版本。
     * <p>
     * 仔细审视一下 AbstractConfigurator.configure() 方法中针对 2.7.0 版本之后动态配置的处理，
     * 其中会根据 side 参数明确判断配置 URL 和原始 URL 属于 Consumer 端还是 Provider 端，判断逻辑也更加清晰。
     * 匹配之后的具体替换过程同样是调用 configureIfMatch() 方法实现的。
     *
     * @param url Configurator URL
     * @return URL
     */
    @Deprecated
    private URL configureDeprecated(URL url) {
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        // 如果配置URL中的端口不为空，则是针对Provider的，需要判断原始URL的端口，
        // 两者端口相同，才能执行configureIfMatch()方法中的配置方法
        if (configuratorUrl.getPort() != 0) {
            if (url.getPort() == configuratorUrl.getPort()) {
                return configureIfMatch(url.getHost(), url);
            }
        } else {
            /*
             *  override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0.
             *  override url 没有端口，表示指定的 ip override url 是消费者地址或 0.0.0.0。
             *  1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore.
             *    如果是consumer ip地址，意图是控制一个特定的consumer实例，必须在consumer端生效，任何接收到这个override url的provider都应该忽略。
             *  2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider.
             *    如果ip是0.0.0.0，这个override url可以用在consumer上，也可以用在provider上。
             */
            // 如果没有指定端口，则该配置URL要么是针对Consumer的，要么是针对任意URL的（即host为0.0.0.0）
            // 如果原始URL属于Consumer，则使用Consumer的host进行匹配
            if (url.getParameter(SIDE_KEY, PROVIDER).equals(CONSUMER)) {
                // NetUtils.getLocalHost is the ip address consumer registered to registry.
                return configureIfMatch(NetUtils.getLocalHost(), url);
            } else if (url.getParameter(SIDE_KEY, CONSUMER).equals(PROVIDER)) {
                // take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
                // 如果是Provider URL，则用0.0.0.0来配置
                return configureIfMatch(ANYHOST_VALUE, url);
            }
        }
        return url;
    }

    /**
     * 此方法会排除匹配 URL 中不可动态修改的参数，并调用 Configurator 子类的 doConfigurator() 方法重写原始 URL
     * @param host IP，本机IP或者0.0.0.0
     * @param url  传入的配置URL
     * @return URL
     */
    private URL configureIfMatch(String host, URL url) {
        // 匹配host
        if (ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            // TODO, to support wildcards
            String providers = configuratorUrl.getParameter(OVERRIDE_PROVIDERS_KEY);
            if (StringUtils.isEmpty(providers) || providers.contains(url.getAddress()) || providers.contains(ANYHOST_VALUE)) {
                String configApplication = configuratorUrl.getParameter(APPLICATION_KEY,
                        configuratorUrl.getUsername());
                String currentApplication = url.getParameter(APPLICATION_KEY, url.getUsername());
                if (configApplication == null || ANY_VALUE.equals(configApplication) // 匹配application
                        || configApplication.equals(currentApplication)) {
                    // 排除不能动态修改的属性，其中包括category、check、dynamic、enabled还有以~开头的属性
                    Set<String> conditionKeys = new HashSet<String>();
                    conditionKeys.add(CATEGORY_KEY);
                    conditionKeys.add(Constants.CHECK_KEY);
                    conditionKeys.add(DYNAMIC_KEY);
                    conditionKeys.add(ENABLED_KEY);
                    conditionKeys.add(GROUP_KEY);
                    conditionKeys.add(VERSION_KEY);
                    conditionKeys.add(APPLICATION_KEY);
                    conditionKeys.add(SIDE_KEY);
                    conditionKeys.add(CONFIG_VERSION_KEY);
                    conditionKeys.add(COMPATIBLE_CONFIG_KEY);
                    conditionKeys.add(INTERFACES);
                    for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        boolean startWithTilde = startWithTilde(key);
                        if (startWithTilde || APPLICATION_KEY.equals(key) || SIDE_KEY.equals(key)) {
                            if (startWithTilde) {
                                conditionKeys.add(key);
                            }
                            // 如果配置URL与原URL中以~开头的参数值不相同，则不使用该配置URL重写原URL
                            if (value != null && !ANY_VALUE.equals(value)
                                    && !value.equals(url.getParameter(startWithTilde ? key.substring(1) : key))) {
                                return url;
                            }
                        }
                    }
                    // 移除配置URL不支持动态配置的参数之后，调用Configurator子类的doConfigure方法重新生成URL
                    return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
                }
            }
        }
        return url;
    }

    private boolean startWithTilde(String key) {
        if (StringUtils.isNotEmpty(key) && key.startsWith(TILDE)) {
            return true;
        }
        return false;
    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
