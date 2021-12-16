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
package org.apache.dubbo.registry.client.metadata;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.registry.client.ServiceInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.lang.String.valueOf;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PORT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.metadata.MetadataConstants.DEFAULT_METADATA_TIMEOUT_VALUE;
import static org.apache.dubbo.metadata.MetadataConstants.METADATA_PROXY_TIMEOUT_KEY;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getMetadataServiceURLsParams;

/**
 * Standard Dubbo provider enabling introspection service discovery mode.
 *
 * @see MetadataService
 * @since 2.7.5
 */
public class StandardMetadataServiceURLBuilder implements MetadataServiceURLBuilder {
    
    public static final String NAME = "standard";

    /**
     * Build the {@link URL urls} from {@link ServiceInstance#getMetadata() the metadata} of {@link ServiceInstance}
     *
     * 该方法用于创建连接服务端的URL
     *
     * @param serviceInstance {@link ServiceInstance}
     * @return the not-null {@link List}
     */
    @Override
    public List<URL> build(ServiceInstance serviceInstance) {
        // getMetadataServiceURLsParams方法从入参的metadata属性中查找
        // key=dubbo.metadata-service.url-params的value值，
        // 根据value生成了paramsMap，value里面记录的是服务端发布的MetadataService服务信息
        // 其中包括了MetadataService服务发布的端口。
        // key=dubbo.metadata-service.url-params的value值是在
        // CustomizableServiceInstanceListener监听到事件ServiceInstancePreRegisteredEvent之后，调用MetadataServiceURLParamsMetadataCustomizer添加到metadata中的。
        Map<String, Map<String, String>> paramsMap = getMetadataServiceURLsParams(serviceInstance);

        List<URL> urls = new ArrayList<>(paramsMap.size());

        String serviceName = serviceInstance.getServiceName();

        String host = serviceInstance.getHost();

        for (Map.Entry<String, Map<String, String>> entry : paramsMap.entrySet()) {
            String protocol = entry.getKey();
            Map<String, String> params = entry.getValue();
            int port = Integer.parseInt(params.get(PORT_KEY));
            URLBuilder urlBuilder = new URLBuilder()
                    .setHost(host)
                    .setPort(port) // 设置MetadataService服务的端口
                    .setProtocol(protocol)
                    .setPath(MetadataService.class.getName())
                    .addParameter(TIMEOUT_KEY, ConfigurationUtils.get(METADATA_PROXY_TIMEOUT_KEY, DEFAULT_METADATA_TIMEOUT_VALUE))
                    .addParameter(SIDE_KEY, CONSUMER);

            // add parameters
            params.forEach((name, value) -> urlBuilder.addParameter(name, valueOf(value)));

            // add the default parameters
            urlBuilder.addParameter(GROUP_KEY, serviceName);

            urls.add(urlBuilder.build());
        }

        return urls;
    }
}
