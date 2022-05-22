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
package org.apache.dubbo.remoting.http;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.remoting.Constants;

/**
 * 该接口是http绑定器接口，其中就定义了一个方法就是绑定方法，并且返回服务器对象。该接口是一个可扩展接口，默认实现JettyHttpBinder。
 *
 * <p>一共有3个子类:
 * <ul>
 *     <li>JettyHttpBinder</li>
 *     <li>ServletHttpBinder</li>
 *     <li>TomcatHttpBinder</li>
 * </ul>
 */
@SPI("jetty")
public interface HttpBinder {

    /**
     * 绑定一个HTTP Server
     *
     * @param url server url.
     * @return server.
     */
    @Adaptive({Constants.SERVER_KEY})
    HttpServer bind(URL url, HttpHandler handler);

}
