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
package org.apache.dubbo.rpc.cluster.router.file;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.utils.IOUtils;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterFactory;
import org.apache.dubbo.rpc.cluster.router.script.ScriptRouterFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import static org.apache.dubbo.rpc.cluster.Constants.ROUTER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RULE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RUNTIME_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.TYPE_KEY;

/**
 * FileRouterFactory 是 ScriptRouterFactory 的装饰器，其扩展名为 file，
 * FileRouterFactory 在 ScriptRouterFactory 基础上增加了读取文件的能力。
 *
 * 可以将 ScriptRouter 使用的路由规则保存到文件中，然后在 URL 中指定文件路径，
 * FileRouterFactory 从中解析到该脚本文件的路径并进行读取，
 * 调用 ScriptRouterFactory 去创建相应的 ScriptRouter 对象。
 */
public class FileRouterFactory implements RouterFactory {

    public static final String NAME = "file";

    private RouterFactory routerFactory;

    public void setRouterFactory(RouterFactory routerFactory) {
        this.routerFactory = routerFactory;
    }

    // 首先会将 file:// 协议转换成 script:// 协议，然后会添加 type 参数和 rule 参数，
    // 其中 type 参数值根据文件后缀名确定，该示例为 js，rule 参数值为文件内容。
    //
    // file:///d:/path/to/route.js?router=script TO
    // script:///d:/path/to/route.js?type=js&rule=<file-content>
    @Override
    public Router getRouter(URL url) {
        try {
            // Transform File URL into Script Route URL, and Load
            // file:///d:/path/to/route.js?router=script ==> script:///d:/path/to/route.js?type=js&rule=<file-content>
            // 默认使用script协议
            String protocol = url.getParameter(ROUTER_KEY, ScriptRouterFactory.NAME); // Replace original protocol (maybe 'file') with 'script'
            String type = null; // Use file suffix to config script type, e.g., js, groovy ...
            String path = url.getPath();
            // 获取脚本文件的语言类型
            if (path != null) {
                int i = path.lastIndexOf('.');
                if (i > 0) {
                    type = path.substring(i + 1);
                }
            }

            // 读取脚本文件中的内容
            String rule = IOUtils.read(new FileReader(new File(url.getAbsolutePath())));

            // FIXME: this code looks useless
            boolean runtime = url.getParameter(RUNTIME_KEY, false);
            // 创建script协议的URL
            URL script = URLBuilder.from(url)
                    .setProtocol(protocol)
                    .addParameter(TYPE_KEY, type)
                    .addParameter(RUNTIME_KEY, runtime)
                    .addParameterAndEncoded(RULE_KEY, rule)
                    .build();
            // 获取script对应的Router实现
            return routerFactory.getRouter(script);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

}
