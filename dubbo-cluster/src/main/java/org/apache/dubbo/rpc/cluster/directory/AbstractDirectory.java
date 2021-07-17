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
package org.apache.dubbo.rpc.cluster.directory;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterChain;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * Abstract implementation of Directory: Invoker list returned from this Directory's list method have been filtered by Routers
 */
public abstract class AbstractDirectory<T> implements Directory<T> {

    // logger
    private static final Logger logger = LoggerFactory.getLogger(AbstractDirectory.class);

    private final URL url;

    private volatile boolean destroyed = false;

    protected volatile URL consumerUrl;

    /**
     * Consumer URL 中 refer 参数解析后得到的全部 KV
     */
    protected final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    protected final String consumedProtocol;

    protected RouterChain<T> routerChain;

    public AbstractDirectory(URL url) {
        this(url, null, false);
    }

    public AbstractDirectory(URL url, boolean isUrlFromRegistry) {
        this(url, null, isUrlFromRegistry);
    }

    public AbstractDirectory(URL url, RouterChain<T> routerChain, boolean isUrlFromRegistry) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }

        // 解析refer参数值，得到其中Consumer的属性信息
        //"init" -> "false"
        //"side" -> "consumer"
        //"register.ip" -> "1.1.1.1"
        //"methods" -> "method1,method2"
        //"release" -> "2.7.7"
        //"dubbo" -> "2.0.2"
        //"pid" -> "7272"
        //"check" -> "false"
        //"interface" -> "com.cc.user.DemoService"
        //"version" -> "3.0.0"
        //"qos.enable" -> "false"
        //"timeout" -> "60000"
        //"revision" -> "1.0.0"
        //"connect.timeout" -> "10000"
        //"metadata-type" -> "remote"
        //"application" -> "dd-service"
        //"sticky" -> "false"
        //"timestamp" -> "1626506775930"
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        // 获取消费的Protocol，获取不到则为Dubbo
        this.consumedProtocol = this.queryMap.get(PROTOCOL_KEY) == null ? DUBBO : this.queryMap.get(PROTOCOL_KEY);
        // 记录传入的url并移除refer和monitor参数
        this.url = url.removeParameter(REFER_KEY).removeParameter(MONITOR_KEY);

        String path = queryMap.get(PATH_KEY);
        URL consumerUrlFrom = this.url.setProtocol(consumedProtocol).setPath(path == null ? queryMap.get(INTERFACE_KEY) : path);
        if (isUrlFromRegistry) {
            // reserve parameters if url is already a consumer url
            consumerUrlFrom = consumerUrlFrom.clearParameters();
        }
        this.consumerUrl = consumerUrlFrom.addParameters(queryMap).removeParameter(MONITOR_KEY);

        setRouterChain(routerChain);
    }

    public URL getSubscribeConsumerurl() {
        return this.consumerUrl;
    }

    @Override
    public List<Invoker<T>> list(Invocation invocation) throws RpcException {
        if (destroyed) {
            throw new RpcException("Directory already destroyed .url: " + getUrl());
        }

        return doList(invocation);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    public RouterChain<T> getRouterChain() {
        return routerChain;
    }

    public void setRouterChain(RouterChain<T> routerChain) {
        this.routerChain = routerChain;
    }

    protected void addRouters(List<Router> routers) {
        routers = routers == null ? Collections.emptyList() : routers;
        routerChain.addRouters(routers);
    }

    public URL getConsumerUrl() {
        return consumerUrl;
    }

    public void setConsumerUrl(URL consumerUrl) {
        this.consumerUrl = consumerUrl;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void destroy() {
        destroyed = true;
    }

    @Override
    public void discordAddresses() {
        // do nothing by default
    }

    protected abstract List<Invoker<T>> doList(Invocation invocation) throws RpcException;

}
