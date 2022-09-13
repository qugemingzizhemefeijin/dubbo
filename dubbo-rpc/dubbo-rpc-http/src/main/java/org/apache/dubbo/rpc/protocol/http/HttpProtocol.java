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
package org.apache.dubbo.rpc.protocol.http;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;

import com.googlecode.jsonrpc4j.HttpException;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.spring.JsonProxyFactoryBean;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.support.RemoteInvocation;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;

public class HttpProtocol extends AbstractProxyProtocol {
    public static final String ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = "Access-Control-Allow-Origin";
    public static final String ACCESS_CONTROL_ALLOW_METHODS_HEADER = "Access-Control-Allow-Methods";
    public static final String ACCESS_CONTROL_ALLOW_HEADERS_HEADER = "Access-Control-Allow-Headers";

    private final Map<String, JsonRpcServer> skeletonMap = new ConcurrentHashMap<>();

    // 自动注入 HttpBinder$Adaptive
    private HttpBinder httpBinder;

    public HttpProtocol() {
        super(HttpException.class, JsonRpcClientException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    public int getDefaultPort() {
        return 80;
    }

    private class InternalHandler implements HttpHandler {

        private boolean cors;

        public InternalHandler(boolean cors) {
            this.cors = cors;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws ServletException {
            String uri = request.getRequestURI();
            // 这里是否应该判断一下更好，如果skeleton为空，直接抛出500，而不是ServletException异常呢？
            JsonRpcServer skeleton = skeletonMap.get(uri);
            // 是否支持跨域
            if (cors) {
                response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
                response.setHeader(ACCESS_CONTROL_ALLOW_METHODS_HEADER, "POST");
                response.setHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER, "*");
            }
            // OPTIONS调用只是判断是否暴露的服务顺畅
            if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
                response.setStatus(200);
            } else if (request.getMethod().equalsIgnoreCase("POST")) {
                // 处理POST的请求，对方法进行反射调用
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                try {
                    skeleton.handle(request.getInputStream(), response.getOutputStream());
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            } else {
                response.setStatus(500);
            }
        }

    }

    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        // addr = host:port
        String addr = getAddr(url);
        // 查看是否启动过服务，如果未启动，则将服务启动
        ProtocolServer protocolServer = serverMap.get(addr);
        if (protocolServer == null) {
            // 根据绑定的类型创建不同的server，默认是jetty，并绑定 InternalHandler 用于接受请求的处理
            RemotingServer remotingServer = httpBinder.bind(url, new InternalHandler(url.getParameter("cors", false)));
            // 设置服务绑定映射，ProxyProtocolServer代理了JettyHttpBinder对象
            serverMap.put(addr, new ProxyProtocolServer(remotingServer));
        }
        // com.ddky.xxx.XXService
        final String path = url.getAbsolutePath();
        // com.ddky.xxx.XXService/generic
        final String genericPath = path + "/" + GENERIC_KEY;
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // JsonRpcServer 的接收处理
        JsonRpcServer skeleton = new JsonRpcServer(mapper, impl, type);
        // JsonRpcServer 泛化调用的接收处理
        JsonRpcServer genericServer = new JsonRpcServer(mapper, impl, GenericService.class);
        // 维护处理映射关系 org.apache.dubbo.rpc.protocol.http.HttpProtocol.InternalHandler.handle
        skeletonMap.put(path, skeleton);
        skeletonMap.put(genericPath, genericServer);
        // 当服务销毁，执行清理动作
        return () -> {
            skeletonMap.remove(path);
            skeletonMap.remove(genericPath);
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <T> T doRefer(final Class<T> serviceType, URL url) throws RpcException {
        // 判断是否支持泛化调用
        final String generic = url.getParameter(GENERIC_KEY);
        final boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);
        JsonProxyFactoryBean jsonProxyFactoryBean = new JsonProxyFactoryBean();
        // 初始化jsonrpc4j的client实体
        JsonRpcProxyFactoryBean jsonRpcProxyFactoryBean = new JsonRpcProxyFactoryBean(jsonProxyFactoryBean);
        jsonRpcProxyFactoryBean.setRemoteInvocationFactory((methodInvocation) -> {
            RemoteInvocation invocation = new JsonRemoteInvocation(methodInvocation);
            if (isGeneric) {
                invocation.addAttribute(GENERIC_KEY, generic);
            }
            return invocation;
        });
        // 强制将url的协议指定为http，如果要支持https，这里的代码需要改造
        String key = url.setProtocol("http").toIdentityString();
        if (isGeneric) {
            key = key + "/" + GENERIC_KEY;
        }

        jsonRpcProxyFactoryBean.setServiceUrl(key);
        jsonRpcProxyFactoryBean.setServiceInterface(serviceType);

        jsonProxyFactoryBean.afterPropertiesSet();
        return (T) jsonProxyFactoryBean.getObject();
    }

    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null) {
            Class<?> cls = e.getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                return RpcException.SERIALIZATION_EXCEPTION;
            }

            if (e instanceof HttpProtocolErrorCode) {
                return ((HttpProtocolErrorCode) e).getErrorCode();
            }
        }
        return super.getErrorCode(e);
    }

    @Override
    public void destroy() {
        super.destroy();
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close jsonrpc server " + server.getUrl());
                    }
                    server.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }


}
