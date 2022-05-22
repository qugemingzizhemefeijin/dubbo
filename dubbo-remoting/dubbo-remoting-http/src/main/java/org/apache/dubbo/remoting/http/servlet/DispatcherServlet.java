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
package org.apache.dubbo.remoting.http.servlet;

import org.apache.dubbo.remoting.http.HttpHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service dispatcher Servlet.
 */
public class DispatcherServlet extends HttpServlet {

    private static final long serialVersionUID = 5766349180380479888L;

    /**
     * http服务器处理器
     */
    private static final Map<Integer, HttpHandler> HANDLERS = new ConcurrentHashMap<Integer, HttpHandler>();

    /**
     * 这个地方这么写，挺有意思的。有点像单例，又不是单例。
     */
    private static DispatcherServlet INSTANCE;

    public DispatcherServlet() {
        DispatcherServlet.INSTANCE = this;
    }

    // 添加处理器
    public static void addHttpHandler(int port, HttpHandler processor) {
        HANDLERS.put(port, processor);
    }

    public static void removeHttpHandler(int port) {
        HANDLERS.remove(port);
    }

    public static DispatcherServlet getInstance() {
        return INSTANCE;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // 获得处理器
        HttpHandler handler = HANDLERS.get(request.getLocalPort());
        // 如果处理器不存在
        if (handler == null) {// service not found.
            // 返回404
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Service not found.");
        } else {
            // 处理请求
            handler.handle(request, response);
        }
    }

}
