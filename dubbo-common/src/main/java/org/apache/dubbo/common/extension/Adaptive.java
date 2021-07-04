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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provide helpful information for {@link ExtensionLoader} to inject dependency extension instance.
 * <p>
 *
 * <p>&#64;Adaptive注解可以标记在类、 接口、枚举类和方法上， 但是在整个Dubbo框架中， 只有几个地方使用在类级别上。
 * 如AdaptiveExtensionFactory和AdaptiveCompiler,其余都标注在方法上。
 *
 * <p>如果标注在接口的方法上，即方法级别注解，则可以通过参数动态获得实现类。方法级别注解在第一次getExtension时，
 * 会自动生成和编译一个动态的Adaptive类，从而达到动态实现类的效果。
 *
 * <p>此注解如果放在实现类上，主要是为了直接固定对应的实现而不需要动态生成代码实现，就像策略模式直接确定实现类。
 *
 * <p>在扩展点接口的多个实现里（多个实现类上加了&#64;Adaptive），只能有一个实现上可以加@Adaptive注解。
 * 如果多个实现类都有该注解，则会抛出异常：More than 1 adaptive class found 0。
 *
 * @see ExtensionLoader
 * @see URL
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Adaptive {
    /**
     * Decide which target extension to be injected. The name of the target extension is decided by the parameter passed
     * in the URL, and the parameter names are given by this method.
     * <p>
     * If the specified parameters are not found from {@link URL}, then the default extension will be used for
     * dependency injection (specified in its interface's {@link SPI}).
     * <p>
     * For example, given <code>String[] {"key1", "key2"}</code>:
     * <ol>
     * <li>find parameter 'key1' in URL, use its value as the extension's name</li>
     * <li>try 'key2' for extension's name if 'key1' is not found (or its value is empty) in URL</li>
     * <li>use default extension if 'key2' doesn't exist either</li>
     * <li>otherwise, throw {@link IllegalStateException}</li>
     * </ol>
     * If the parameter names are empty, then a default parameter name is generated from interface's
     * class name with the rule: divide classname from capital char into several parts, and separate the parts with
     * dot '.', for example, for {@code org.apache.dubbo.xxx.YyyInvokerWrapper}, the generated name is
     * <code>String[] {"yyy.invoker.wrapper"}</code>.
     *
     * <pre>
     * &#64;SPI("impll")
     * public interface SimpleExt {
     *
     *    &#64;Adaptive
     *    String echo(URL url. String s);
     *
     * }
     *
     * 注意： 如果@Adaptive注解没有传入key参数， 则默认会把类名转化为key。
     * 如： SimpleExt 会转化为 simple.ext
     * 根据key获取对应的扩展点实现名称， 第一个参数是key,第二个是获取不到时的默认值
     * URL 中没有"simple.ext"这个 key,因此 extName 取值 impll
     *
     * 如果@Adaptive注解中有key参数,如@Adaptive( "keyl"),则会变为url.getParameter("key1", "impll");
     *
     * </pre>
     *
     * @return parameter names in URL
     */
    String[] value() default {};

}