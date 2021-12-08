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
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.extension.support.WrapperComparator;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static java.util.Collections.sort;
import static java.util.ServiceLoader.load;
import static java.util.stream.StreamSupport.stream;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 *
 * <p>此类是实现Dubbo SPI机制的核心类
 * <p>Dubbo的SPI与Java的SPI的区别：
 * <ul>
 * <li>1.JDK的SPI会一次性实例化扩展点的所有实例，如果实例初始化非常耗时或者实例没有用上，会非常的耗损资源。</li>
 * <li>2.如果JDK的扩展类加载失败的话，异常信息会被JDK吞掉。</li>
 * <li>3.Dubbo SPI增加了对扩展的IOC和AOP的支持，一个扩展可以直接通过setter注入其他扩展。</li>
 * </ul>
 *
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 * <p>
 * 扩展点的特性
 * <ul>
 * <li>自动注入依赖 </li>
 * <li>自动包装，如果发现这个扩展类包含其他扩展点作为构造函数的参数，则这个扩展类就会被认为是Wrapper类，会自动传入一个实现的接口的类 </li>
 * <li>默认扩展名是自适应实例，通过@Adaptive根据URL中的参数</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>(64);

    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>(64);

    /**
     * 当前ExtensionLoader维护的扩展点接口类
     */
    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();

    /**
     * Adaptive Class的具体实例化对象
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();

    /**
     * Adaptive具体实现类的Class类型
     */
    private volatile Class<?> cachedAdaptiveClass = null;
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    /**
     * Record all unacceptable exceptions when using SPI
     */
    private Set<String> unacceptableExceptions = new ConcurrentHashSet<>();

    private static volatile LoadingStrategy[] strategies = loadLoadingStrategies();

    public static void setLoadingStrategies(LoadingStrategy... strategies) {
        if (ArrayUtils.isNotEmpty(strategies)) {
            ExtensionLoader.strategies = strategies;
        }
    }

    /**
     * Load all {@link Prioritized prioritized} {@link LoadingStrategy Loading Strategies} via {@link ServiceLoader}
     *
     * @return non-null
     * @since 2.7.7
     */
    private static LoadingStrategy[] loadLoadingStrategies() {
        return stream(load(LoadingStrategy.class).spliterator(), false)
                .sorted()
                .toArray(LoadingStrategy[]::new);
    }

    /**
     * Get all {@link LoadingStrategy Loading Strategies}
     *
     * @return non-null
     * @see LoadingStrategy
     * @see Prioritized
     * @since 2.7.7
     */
    public static List<LoadingStrategy> getLoadingStrategies() {
        return asList(strategies);
    }

    /**
     * 构造ExtensionLoader，这里会根据type类型来创建ExtensionFactory。
     * 一般Dubbo中最多有三个ExtensionFactory，默认的是AdaptiveExtensionFactory
     * 其他两个是SpiExtensionFactory、SpringExtensionFactory
     * @param type 要加载的SPI类
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        // 这个objectFactory属性的类型是ExtensionFactory，也就是扩展工厂，这个工厂就是用来根据入参
        // 获取到真正的实现实例。默认会创建一个AdaptiveExtensionFactory实例，这个是一个自适应扩展类，这个类里
        // 面有一个List<ExtensionFactory> factories属性，在创建的时候会将ExtensionFactory的另外两个实现
        // SpiExtensionFactory、SpringExtensionFactory实例化后添加到factories集合中。
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        // 基础校验
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                    ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }
        // 先从本地缓存EXTENSION_LOADERS中通过Class类型获取当前Class类型的扩展载入器，EXTENSION_LOADERS是一个ConcurrentHashMap
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            // 如果缓存中没有，就创建一个当前所需Class类型的载入器并添加到本地缓存。
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            // 然后再次从本地缓存中获取
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        // 返回构建好的当前Class类的扩展载入器
        return loader;
    }

    // For testing purposes only
    @Deprecated
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    // only for unit test
    @Deprecated
    public static void destroyAll() {
        EXTENSION_INSTANCES.forEach((_type, instance) -> {
            if (instance instanceof Lifecycle) {
                Lifecycle lifecycle = (Lifecycle) instance;
                try {
                    lifecycle.destroy();
                } catch (Exception e) {
                    logger.error("Error destroying extension " + lifecycle, e);
                }
            }
        });
    }

    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * <pre>
     * key表示url中配置参数的名称，比如我们在服务提供方配置：
     *     filter="cache,token,-timeout" 在服务提供者解析阶段会往URL中
     *     添加参数service.filter=cache,token,-timeout 且在服务发布过程中会调用
     *     List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).
     *                    getActivateExtension(invoker.getUrl(), "service.filter", "provider");
     *     方法去获取到需要激活的Filter。
     *
     * </pre>
     *
     * @param url   表示dubbo服务的url。
     * @param key   获取扩展的名称
     * @param group group表示在什么分组下激活，是激活扩展点的过滤条件。
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        // 1、通过key从Url中获取参数的value值。
        String value = url.getParameter(key);
        // 2、将value值进行split(",")后，去获取符合条件的激活扩展点列表。
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    dubbo服务的url
     * @param values url中按某参数名称获取到的value值split(",")的数组，一般情况下是激活扩展点实现的名称，如上面说的["cache","token","-timeout"]timeout表示移除这个激活扩展点的实现。
     * @param group  获取激活扩展点的传入的分组，如provider、consumer
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> activateExtensions = new ArrayList<>();
        // solve the bug of using @SPI's wrapper method to report a null pointer exception.
        // 解决使用@SPI的wrapper方法报空指针异常的bug？？这一句话是什么意思呢？
        // 原来 activateExtensions.add(getExtension(name)) 方法可能getExtension(name)为空，造成存储了一个空的对象。
        // 用TreeMap肯定能保证加载的Class也就是不为空，如果为空，则无法put到map中。Class不为空那么也就意味着value不为空。
        TreeMap<Class, T> activateExtensionsMap = new TreeMap<>(ActivateComparator.COMPARATOR);
        // 1、使用一个names的集合来存放values。
        List<String> names = values == null ? new ArrayList<>(0) : asList(values);
        // 2、如果values中不包含"-default",也就是说这个if成立的化应该是去加载默认的激活扩展点。
        //    而"-default"就表示移除默认的激活扩展点。默认的激活扩展点就是我们没有显示配置的激活扩展点。
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            // 3、在载入当前扩展点接口所有的实现类，将激活扩展的实现Class信息缓存到cachedActivates中。
            getExtensionClasses();
            // 4、循环当前扩展点的所有激活扩展的实现类。
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                // 5、获取当前激活扩展实现的name
                String name = entry.getKey();
                // 6、获取当前激活扩展实现的@Activate注解描述实实例，没错cachedActivates缓存中放的key=实现的name，value=@Activate的注解描述实例。
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;

                // 7、这一步就是获取@Activate注解的group、value属性。
                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }

                // 8、判断当前循环的激活扩展点实现是否是本次获取激活扩展点所需要的，判断的条件：
                //    条件1-分组匹配:
                //    如果传入的group与当前激活扩展点实现类上标注的@Activate中的group是匹配的，
                //    如果传入的group为空就默认表示匹配，
                //    如果传入group不为空那就查看当前循环的实现是支持当前分组。

                //    条件2-当前循环的扩展点实现的名称不在配置的valus数组中
                //    这个if里面是处理默认的激活扩展点，也就是判断不在我们配置的范围内的激活扩展实现，
                //    这样做的好处就是必要的激活扩展点的实现不需要我们显示的指定。
                //    在names集合中的也就是我们显示配置的激活扩展点的实现在下面会将其加入到我们所需要的激活扩展点的列表中。

                //    条件3-当循环的激活扩展点的实现是否是我们配置需要移除的实现
                //    比如我们上面配置的filter="cache","token","-timeout" 我们要移除name=timeout的扩展点实现。

                //    条件4-当前循环的激活扩展点实现是否需要激活判断依据：
                //        1、如果当前激活扩展点实现类上的@Activate注解的成员属性value数组为空，那就表示默认需要激活。
                //        2、如果当前激活扩展点实现类上的@Activate注解的成员属性value数组不为空，那就遍历URL的参数列表，
                //        如果存在数组中的任何一个参数名称，那就表示需要激活。
                //        举例如下：
                //           假设当前循环的扩展点实现标注了@Active(value={"cache","xxx"})，那就表示如果当前的URL参数中只要有cache、或者xxx参数，
                //           那么就表示当前的激活扩展点实现是需要激活的。
                if (isMatchGroup(group, activateGroup)
                        && !names.contains(name)
                        && !names.contains(REMOVE_VALUE_PREFIX + name)
                        && isActive(activateValue, url)) {
                    // 9、上面4个条件都满足后，那么默认的激活扩展点存放到Map中，key=扩展点Class名称、value=扩展点实例
                    //    此Map是TreeMap，已经实现了排序了，排序的算法在ActivateComparator.COMPARATOR引用中。
                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            }
            // 10、如果获取到的激活扩展点存放到activateExtensions中。
            // 在老版本中，此处 没有使用 activateExtensionsMap，而是使用的 activateExtensions.sort(ActivateComparator.COMPARATOR)
            // 至于为什么替换，可以看上面 activateExtensionsMap 变量实例化的地方的代码注释。
            if(!activateExtensionsMap.isEmpty()){
                activateExtensions.addAll(activateExtensionsMap.values());
            }
        }

        // 11、创建一个空集合用存存放我们显示配置的激活扩展点实现列表。
        List<T> loadedExtensions = new ArrayList<>();
        // 12、处理我们显示配置的激活扩展点，names=values也就是我们显示配置的激活扩展点，
        //     如filter="cache","token","-timeout" 就表示我们显示的配置3个激活扩展点。
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);

            // 13、如果是不需要移除的扩展点实现那就加入到返回结果集合里面。
            if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                if (DEFAULT_KEY.equals(name)) {
                    if (!loadedExtensions.isEmpty()) {
                        activateExtensions.addAll(0, loadedExtensions);
                        loadedExtensions.clear();
                    }
                } else {
                    loadedExtensions.add(getExtension(name));
                }
            }
        }

        // 14、如果显示配置的激活扩展点实现不为空，那就加入到结果集activateExtensions集合中，
        //     从此步可以看出，显示配置的激活扩展点实现会放在默认的激活扩展点实现的后面。
        if (!loadedExtensions.isEmpty()) {
            activateExtensions.addAll(loadedExtensions);
        }

        // 15、返回本次获取激活扩展点实现的列表。
        return activateExtensions;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            // @Active(value="key1:value1, key2:value2")
            String keyValue = null;
            if (key.contains(":")) {
                String[] arr = key.split(":");
                key = arr[0];
                keyValue = arr[1];
            }

            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ((keyValue != null && keyValue.equals(v)) || (keyValue == null && ConfigUtils.isNotEmpty(v)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public List<T> getLoadedExtensionInstances() {
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

//    public T getPrioritizedExtensionInstance() {
//        Set<String> supported = getSupportedExtensions();
//
//        Set<T> instances = new HashSet<>();
//        Set<T> prioritized = new HashSet<>();
//        for (String s : supported) {
//
//        }
//
//    }

    /**
     * <p>Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     * <br/>
     * <br/>
     * <p>
     * 查找具有给定名称的扩展名。如果未找到指定的名称，则会抛出 {@link IllegalStateException}。
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        // 入参是实现的名称，以及是否需要包装实现，默认是需要
        return getExtension(name, true);
    }

    /**
     * 格局名称获取扩展类，根据wrap来判断是否需要生成扩展类，默认都是true，false好像也就是Cluster中用到了
     * @param name 扩展类名称
     * @param wrap 如果未找到，是否需要封装包装类
     * @return
     */
    public T getExtension(String name, boolean wrap) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
            // 如果传入的名称是“true”那就获取一个默认的扩展点实现实例
            return getDefaultExtension();
        }
        // 创建一个Holder实例用于存放我们找到的扩展点实现类的实例
        final Holder<Object> holder = getOrCreateHolder(name);
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    // 根据名称+是否包装来获取一个扩展点实现的实例
                    instance = createExtension(name, wrap);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * 如果找到，则通过指定名称获取扩展名，或者 {@link #getDefaultExtension()} 返回默认值
     *
     * @param name 扩展类名称
     * @return T
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name) ? getExtension(name) : getDefaultExtension();
    }

    /**
     * 返回默认扩展名，如果没有配置则返回<code>null<code>.
     */
    public T getDefaultExtension() {
        // 1、先加载META-INF中的配置信息
        getExtensionClasses();
        // 2、如果 cachedDefaultName empty 或者 true 则返回空
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        // 3、否则通过getExtension方法获取默认名称的扩展实现类
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    public Set<T> getSupportedExtensionInstances() {
        List<T> instances = new LinkedList<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        // sort the Prioritized instances
        sort(instances, Prioritized.COMPARATOR);
        return new LinkedHashSet<>(instances);
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 获取ExtensionFactory自适应扩展点的自适应实现实例
     * @return T
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 1、先获取缓存，看看是否存在，默认情况下，类级别的自适应扩展点实现只有一个，如果被创建了就会缓存在构建一个Holder实例缓存在cachedAdaptiveInstance中
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException("Failed to create adaptive instance: " +
                        createAdaptiveInstanceError.toString(),
                        createAdaptiveInstanceError);
            }

            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                        // 2、创建一个自适应扩展点的实现实例
                        instance = createAdaptiveExtension();
                        // 3、将获取到的自适应扩展点的实现实例缓存在cachedAdaptiveInstance中
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }
        // 4、返回自适应扩展点的实现实例
        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);

        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith(name.toLowerCase())) {
                if (i == 1) {
                    buf.append(", possible causes: ");
                }
                buf.append("\r\n(");
                buf.append(i++);
                buf.append(") ");
                buf.append(entry.getKey());
                buf.append(":\r\n");
                buf.append(StringUtils.toString(entry.getValue()));
            }
        }

        if (i == 1) {
            buf.append(", no related exception was found, please check whether related SPI module is missing.");
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name, boolean wrap) {
        //  1、getExtensionClasses()方法加载当前扩展点所有的实现类，就是去加载路径下的spi文件
        //     然后按照key=名称,value=实现.class放在HashMap中返回。那么在这一步我们就能根据名
        //     获取到我们所需实现的Class信息。同时也会判断实现的类Class信息是否是包装类、自适应、激活扩展方式。
        //     是否是包装              是    缓存到当前ExtensionLoader的cachedWrapperClasses中
        //     类级别是否是自适应扩展方式 是    缓存到当前ExtensionLoader的cachedAdaptiveClass中
        //     类级别是否是激活扩展方式   是    缓存到当前ExtensionLoader的cachedActivates
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null || unacceptableExceptions.contains(name)) {
            throw findException(name);
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                // 2、通过反射创建一个我们所需要的扩展点实现类的实例
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.getDeclaredConstructor().newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            // 3、向扩展类注入其依赖的属性， 如扩展类A又依赖了扩展类B
            // 如果我们所需的实现里面有一些属性，那么在此处进行依赖注入，类似于spring DI，
            // 这里就会使用我们之前构建好的ExtensionFactory来进行依赖的类实例构建，比如扩展点里面依赖扩展点也是在此处进行依赖注入。
            injectExtension(instance);

            // 4、如果需要包装，默认是需要包装
            if (wrap) {
                List<Class<?>> wrapperClassesList = new ArrayList<>();
                // 5、cachedWrapperClasses这个Set缓存会在第1步中载入扩展类所有的实现类Class信息的时候
                // 会进行判断以及缓存在cachedWrapperClasses中表示当前扩展点的包装类。
                if (cachedWrapperClasses != null) {
                    wrapperClassesList.addAll(cachedWrapperClasses);
                    // 6、正排序包装类Class信息
                    wrapperClassesList.sort(WrapperComparator.COMPARATOR);
                    // 7、然后再反转成倒排序
                    Collections.reverse(wrapperClassesList);
                }

                if (CollectionUtils.isNotEmpty(wrapperClassesList)) {
                    // 遍历扩展点包装类， 用于初始化包装类实例
                    for (Class<?> wrapperClass : wrapperClassesList) {
                        Wrapper wrapper = wrapperClass.getAnnotation(Wrapper.class);
                        if (wrapper == null
                                || (ArrayUtils.contains(wrapper.matches(), name) && !ArrayUtils.contains(wrapper.mismatches(), name))) {
                            // 8、使用扩展点实现的实例来构建包装实例，然后再依赖注入包装实例
                            // 找到构造方法参数类型为type (扩展类的类型)的包装类， 为其注入扩展类实例
                            instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                        }
                    }
                }
            }

            // 9、初始化扩展点实例（此处要么是真正的实现实例，要么是包装类实例）
            initExtension(instance);

            // 10、返回我们根据名称找到的扩展点实现的实例
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 判断当前的扩展加载器中是否有指定名称的扩展类
     * @param name 扩展名称
     * @return boolean
     */
    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    private T injectExtension(T instance) {
        if (objectFactory == null) {
            return instance;
        }

        try {
            for (Method method : instance.getClass().getMethods()) {
                // 找到以 set 开头的方法。要求只能有一个参数，并且是public方法
                if (!isSetter(method)) {
                    continue;
                }
                /**
                 * Check {@link DisableInject} to see if we need auto injection for this property
                 */
                if (method.getAnnotation(DisableInject.class) != null) {
                    continue;
                }
                Class<?> pt = method.getParameterTypes()[0];
                if (ReflectUtils.isPrimitives(pt)) {
                    continue;
                }

                try {
                    String property = getSetterProperty(method);
                    Object object = objectFactory.getExtension(pt, property);
                    if (object != null) {
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error("Failed to inject via method " + method.getName()
                            + " of interface " + type.getName() + ": " + e.getMessage(), e);
                }

            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {
            Lifecycle lifecycle = (Lifecycle) instance;
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    /**
     * <p>加载当前扩展点所有的实现类，就是加载jar路径下的spi文件。
     * <p>如果文件已经加载，则直接返回cachedClasses静态Map，并从Map中获取name对应的扩展类Class
     *
     * @param name 要获取的扩展类名称
     * @return Class<?>
     */
    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    /**
     * 加载当前扩展点所有的实现类，如果文件已经加载，则直接返回cachedClasses静态Map
     * @return Map<String, Class<?>>
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // 1、从缓存中获取
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            // 2、经典的单例模式写法
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    // 3、加载配置信息
                    classes = loadExtensionClasses();
                    // 4、加载完毕后设置到Holder中，所以此Holder不需要设置volatile，因为早new出来了
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * synchronized in getExtensionClasses
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();

        for (LoadingStrategy strategy : strategies) {
            loadDirectory(extensionClasses, strategy.directory(), type.getName(), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
            loadDirectory(extensionClasses, strategy.directory(), type.getName().replace("org.apache", "com.alibaba"), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
        }

        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }

        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                        + ": " + Arrays.toString(names));
            }
            if (names.length == 1) {
                cachedDefaultName = names[0];
            }
        }
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        loadDirectory(extensionClasses, dir, type, false, false);
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type,
                               boolean extensionLoaderClassLoaderFirst, boolean overridden, String... excludedPackages) {
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls = null;
            ClassLoader classLoader = findClassLoader();

            // try to load from ExtensionLoader's ClassLoader first
            if (extensionLoaderClassLoaderFirst) {
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    urls = extensionLoaderClassLoader.getResources(fileName);
                }
            }

            // 通过 getResources 或 getSystemResources 得到配置文件
            if (urls == null || !urls.hasMoreElements()) {
                if (classLoader != null) {
                    urls = classLoader.getResources(fileName);
                } else {
                    urls = ClassLoader.getSystemResources(fileName);
                }
            }

            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    loadResource(extensionClasses, classLoader, resourceURL, overridden, excludedPackages);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader,
                              java.net.URL resourceURL, boolean overridden, String... excludedPackages) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                String clazz = null;
                while ((line = reader.readLine()) != null) {
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                clazz = line.substring(i + 1).trim();
                            } else {
                                clazz = line;
                            }
                            if (StringUtils.isNotEmpty(clazz) && !isExcluded(clazz, excludedPackages)) {
                                loadClass(extensionClasses, resourceURL, Class.forName(clazz, true, classLoader), name, overridden);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    private boolean isExcluded(String className, String... excludedPackages) {
        if (excludedPackages != null) {
            for (String excludePackage : excludedPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name,
                           boolean overridden) throws NoSuchMethodException {
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            cacheAdaptiveClass(clazz, overridden);
        } else if (isWrapperClass(clazz)) {
            cacheWrapperClass(clazz);
        } else {
            clazz.getConstructor();
            if (StringUtils.isEmpty(name)) {
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                cacheActivateClass(clazz, names[0]);
                // 不是自适应类型， 也不是包装类型， 剩下的就是普通扩展类了， 也会缓存起来
                //注意： 自动激活也是普通扩展类的一种， 只是会根据不同条件同时激活罢了
                for (String n : names) {
                    cacheName(clazz, n);
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
            }
        }
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name, boolean overridden) {
        Class<?> c = extensionClasses.get(name);
        if (c == null || overridden) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            // duplicate implementation is unacceptable
            unacceptableExceptions.add(name);
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName();
            logger.error(duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        // 如果有自动激活注解(Activate ),则缓存到自动激活的缓存中
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz, boolean overridden) {
        if (cachedAdaptiveClass == null || overridden) {
            // 如果是自适应类(Adaptive )则缓存,缓存的自适应类只能有一个
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            // 如果发现有多个自适应类， 则抛出异常
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getName()
                    + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        // 如果是包装扩展类(Wrapper ),则直接加入包装扩展类的Set集合
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    /**
     * 创建自适应扩展实现实例
     * @return T
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // 获取自适应扩展的实现类Class信息，分为两种
            // 1、在实现类上面使用@Adaptive注解的时候，就直接返回类的Class信息
            // 2、在扩展点接口的方法上面标注@Adaptive注解的时候，这个时候dubbo会动态生成一个实现类的源码code，然后获取一个编译器Compiler进行编译后返回。

            // 获取到自适应扩展点接口的实现类的Class后，反射实例化，然后再对实例进行依赖注入。
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    private Class<?> getAdaptiveExtensionClass() {
        // 1、先去获取扩展点接口的spi文件，然后将所有的实现的Class信息载入并缓存，这一步跟使用名称获取扩展点实现的时候是一样的处理方式。
        getExtensionClasses();

        // 2、如果当前自适应扩展点有类级别的实现，那就直接返回其Class信息。
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }

        // 3、如果当前扩展点没有类级别的自适应扩展实现，那就动态创建一个自适应实现，这个动态的实现只会实现被@Adaptive标注的扩展点接口方法。
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 生成自适应扩展实现类
     * <pre>
     *     类名为扩展点接口simpleName&Adaptive,这些代码全是在允许期间动态生成的，然后会在运行期间编译。
     *
     *     public class Protocol$Adaptive implements org.apache.dubbo.rpc.Protocol {
     *
     *     public void destroy() {
     *         throw new UnsupportedOperationException("The method public abstract void org.apache.dubbo.rpc.Protocol.destroy() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
     *     }
     *
     *     public int getDefaultPort() {
     *         throw new UnsupportedOperationException("The method public abstract int org.apache.dubbo.rpc.Protocol.getDefaultPort() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
     *     }
     *
     *     public Exporter export(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
     *         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
     *         if (arg0.getUrl() == null)
     *             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
     *         URL url = arg0.getUrl();
     *         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
     *         if (extName == null)
     *             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
     *         Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
     *         return extension.export(arg0);
     *     }
     *
     *     public org.apache.dubbo.rpc.Invoker refer(java.lang.Class arg0, org.apache.dubbo.common.URL arg1) throws org.apache.dubbo.rpc.RpcException {
     *         if (arg1 == null) throw new IllegalArgumentException("url == null");
     *         org.apache.dubbo.common.URL url = arg1;
     *         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
     *         if (extName == null)
     *             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
     *         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
     *         return extension.refer(arg0, arg1);
     *     }
     *
     *     public java.util.List getServers() {
     *         throw new UnsupportedOperationException("The method public default java.util.List org.apache.dubbo.rpc.Protocol.getServers() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
     *     }
     *
     *   }
     * </pre>
     * @return Class<?>
     */
    private Class<?> createAdaptiveExtensionClass() {
        // 1、先去生成自适应扩展点的实现代码code，如果扩展点接口中没有被@Adaptive标注的方法的话，将会抛出IllegalStateException(No adaptive method exist on extension
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();

        // 2、获取ExtensionLoader.class类的类装载器。
        ClassLoader classLoader = findClassLoader();

        // 3、获取一个代码编译器进行代码编译从而得到动态生成的自适应扩展点实现类的Class信息。
        //    Compiler也是自适应扩展点，有一个类级别的自适应实现AdaptiveCompiler这个实现
        //    也就是一个适配器，里面会选择实现，默认会选择JavassistCompiler作为编译器。
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();

        // 使用编译器来便器动态生成的自适应扩展点的源码code，从而得到Class信息。
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
