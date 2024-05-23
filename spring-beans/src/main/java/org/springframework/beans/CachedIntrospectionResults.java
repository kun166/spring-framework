/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.SpringProperties;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.StringUtils;

/**
 * Internal class that caches JavaBeans {@link java.beans.PropertyDescriptor}
 * information for a Java class. Not intended for direct use by application code.
 *
 * <p>Necessary for Spring's own caching of bean descriptors within the application
 * {@link ClassLoader}, rather than relying on the JDK's system-wide {@link BeanInfo}
 * cache (in order to avoid leaks on individual application shutdown in a shared JVM).
 *
 * <p>Information is cached statically, so we don't need to create new
 * objects of this class for every JavaBean we manipulate. Hence, this class
 * implements the factory design pattern, using a private constructor and
 * a static {@link #forClass(Class)} factory method to obtain instances.
 *
 * <p>Note that for caching to work effectively, some preconditions need to be met:
 * Prefer an arrangement where the Spring jars live in the same ClassLoader as the
 * application classes, which allows for clean caching along with the application's
 * lifecycle in any case. For a web application, consider declaring a local
 * {@link org.springframework.web.util.IntrospectorCleanupListener} in {@code web.xml}
 * in case of a multi-ClassLoader layout, which will allow for effective caching as well.
 *
 * <p>In case of a non-clean ClassLoader arrangement without a cleanup listener having
 * been set up, this class will fall back to a weak-reference-based caching model that
 * recreates much-requested entries every time the garbage collector removed them. In
 * such a scenario, consider the {@link #IGNORE_BEANINFO_PROPERTY_NAME} system property.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #acceptClassLoader(ClassLoader)
 * @see #clearClassLoader(ClassLoader)
 * @see #forClass(Class)
 * @since 05 May 2001
 */
public final class CachedIntrospectionResults {

	/**
	 * System property that instructs Spring to use the {@link Introspector#IGNORE_ALL_BEANINFO}
	 * mode when calling the JavaBeans {@link Introspector}: "spring.beaninfo.ignore", with a
	 * value of "true" skipping the search for {@code BeanInfo} classes (typically for scenarios
	 * where no such classes are being defined for beans in the application in the first place).
	 * <p>The default is "false", considering all {@code BeanInfo} metadata classes, like for
	 * standard {@link Introspector#getBeanInfo(Class)} calls. Consider switching this flag to
	 * "true" if you experience repeated ClassLoader access for non-existing {@code BeanInfo}
	 * classes, in case such access is expensive on startup or on lazy loading.
	 * <p>Note that such an effect may also indicate a scenario where caching doesn't work
	 * effectively: Prefer an arrangement where the Spring jars live in the same ClassLoader
	 * as the application classes, which allows for clean caching along with the application's
	 * lifecycle in any case. For a web application, consider declaring a local
	 * {@link org.springframework.web.util.IntrospectorCleanupListener} in {@code web.xml}
	 * in case of a multi-ClassLoader layout, which will allow for effective caching as well.
	 *
	 * @see Introspector#getBeanInfo(Class, int)
	 */
	public static final String IGNORE_BEANINFO_PROPERTY_NAME = "spring.beaninfo.ignore";


	private static final boolean shouldIntrospectorIgnoreBeaninfoClasses =
			SpringProperties.getFlag(IGNORE_BEANINFO_PROPERTY_NAME);

	/**
	 * Stores the BeanInfoFactory instances.
	 * 具体怎么获取的没有细追,该接口有两个实现:
	 * {@link SimpleBeanInfoFactory}
	 * {@link ExtendedBeanInfoFactory}
	 * 应该是读取的"META-INF/spring.factories"下的配置
	 */
	private static final List<BeanInfoFactory> beanInfoFactories = SpringFactoriesLoader.loadFactories(
			BeanInfoFactory.class, CachedIntrospectionResults.class.getClassLoader());

	private static final Log logger = LogFactory.getLog(CachedIntrospectionResults.class);

	/**
	 * Set of ClassLoaders that this CachedIntrospectionResults class will always
	 * accept classes from, even if the classes do not qualify as cache-safe.
	 */
	static final Set<ClassLoader> acceptedClassLoaders =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * Map keyed by Class containing CachedIntrospectionResults, strongly held.
	 * This variant is being used for cache-safe bean classes.
	 */
	static final ConcurrentMap<Class<?>, CachedIntrospectionResults> strongClassCache =
			new ConcurrentHashMap<>(64);

	/**
	 * Map keyed by Class containing CachedIntrospectionResults, softly held.
	 * This variant is being used for non-cache-safe bean classes.
	 */
	static final ConcurrentMap<Class<?>, CachedIntrospectionResults> softClassCache =
			new ConcurrentReferenceHashMap<>(64);


	/**
	 * Accept the given ClassLoader as cache-safe, even if its classes would
	 * not qualify as cache-safe in this CachedIntrospectionResults class.
	 * <p>This configuration method is only relevant in scenarios where the Spring
	 * classes reside in a 'common' ClassLoader (e.g. the system ClassLoader)
	 * whose lifecycle is not coupled to the application. In such a scenario,
	 * CachedIntrospectionResults would by default not cache any of the application's
	 * classes, since they would create a leak in the common ClassLoader.
	 * <p>Any {@code acceptClassLoader} call at application startup should
	 * be paired with a {@link #clearClassLoader} call at application shutdown.
	 *
	 * @param classLoader the ClassLoader to accept
	 */
	public static void acceptClassLoader(@Nullable ClassLoader classLoader) {
		if (classLoader != null) {
			acceptedClassLoaders.add(classLoader);
		}
	}

	/**
	 * Clear the introspection cache for the given ClassLoader, removing the
	 * introspection results for all classes underneath that ClassLoader, and
	 * removing the ClassLoader (and its children) from the acceptance list.
	 *
	 * @param classLoader the ClassLoader to clear the cache for
	 */
	public static void clearClassLoader(@Nullable ClassLoader classLoader) {
		acceptedClassLoaders.removeIf(registeredLoader ->
				isUnderneathClassLoader(registeredLoader, classLoader));
		strongClassCache.keySet().removeIf(beanClass ->
				isUnderneathClassLoader(beanClass.getClassLoader(), classLoader));
		softClassCache.keySet().removeIf(beanClass ->
				isUnderneathClassLoader(beanClass.getClassLoader(), classLoader));
	}

	/**
	 * Create CachedIntrospectionResults for the given bean class.
	 * <p>
	 * {@link BeanWrapperImpl#getCachedIntrospectionResults()}中调用
	 * {@link BeanUtils#getPropertyDescriptors(java.lang.Class)}中调用
	 * </p>
	 *
	 * @param beanClass the bean class to analyze
	 * @return the corresponding CachedIntrospectionResults
	 * @throws BeansException in case of introspection failure
	 */
	static CachedIntrospectionResults forClass(Class<?> beanClass) throws BeansException {
		CachedIntrospectionResults results = strongClassCache.get(beanClass);
		if (results != null) {
			return results;
		}
		results = softClassCache.get(beanClass);
		if (results != null) {
			return results;
		}

		results = new CachedIntrospectionResults(beanClass);
		ConcurrentMap<Class<?>, CachedIntrospectionResults> classCacheToUse;

		if (ClassUtils.isCacheSafe(beanClass, CachedIntrospectionResults.class.getClassLoader()) ||
				isClassLoaderAccepted(beanClass.getClassLoader())) {
			classCacheToUse = strongClassCache;
		} else {
			if (logger.isDebugEnabled()) {
				logger.debug("Not strongly caching class [" + beanClass.getName() + "] because it is not cache-safe");
			}
			classCacheToUse = softClassCache;
		}

		CachedIntrospectionResults existing = classCacheToUse.putIfAbsent(beanClass, results);
		return (existing != null ? existing : results);
	}

	/**
	 * Check whether this CachedIntrospectionResults class is configured
	 * to accept the given ClassLoader.
	 *
	 * @param classLoader the ClassLoader to check
	 * @return whether the given ClassLoader is accepted
	 * @see #acceptClassLoader
	 */
	private static boolean isClassLoaderAccepted(ClassLoader classLoader) {
		for (ClassLoader acceptedLoader : acceptedClassLoaders) {
			if (isUnderneathClassLoader(classLoader, acceptedLoader)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Check whether the given ClassLoader is underneath the given parent,
	 * that is, whether the parent is within the candidate's hierarchy.
	 *
	 * @param candidate the candidate ClassLoader to check
	 * @param parent    the parent ClassLoader to check for
	 */
	private static boolean isUnderneathClassLoader(@Nullable ClassLoader candidate, @Nullable ClassLoader parent) {
		if (candidate == parent) {
			return true;
		}
		if (candidate == null) {
			return false;
		}
		ClassLoader classLoaderToCheck = candidate;
		while (classLoaderToCheck != null) {
			classLoaderToCheck = classLoaderToCheck.getParent();
			if (classLoaderToCheck == parent) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Retrieve a {@link BeanInfo} descriptor for the given target class.
	 * <p>
	 * {@link CachedIntrospectionResults#CachedIntrospectionResults(java.lang.Class)}中调用
	 * </p>
	 *
	 * @param beanClass the target class to introspect
	 * @return the resulting {@code BeanInfo} descriptor (never {@code null})
	 * @throws IntrospectionException from the underlying {@link Introspector}
	 */
	private static BeanInfo getBeanInfo(Class<?> beanClass) throws IntrospectionException {
		for (BeanInfoFactory beanInfoFactory : beanInfoFactories) {
			BeanInfo beanInfo = beanInfoFactory.getBeanInfo(beanClass);
			if (beanInfo != null) {
				return beanInfo;
			}
		}

		BeanInfo beanInfo = (shouldIntrospectorIgnoreBeaninfoClasses ?
				Introspector.getBeanInfo(beanClass, Introspector.IGNORE_ALL_BEANINFO) :
				Introspector.getBeanInfo(beanClass));

		// Immediately remove class from Introspector cache to allow for proper garbage
		// collection on class loader shutdown; we cache it in CachedIntrospectionResults
		// in a GC-friendly manner. This is necessary (again) for the JDK ClassInfo cache.
		Class<?> classToFlush = beanClass;
		do {
			Introspector.flushFromCaches(classToFlush);
			classToFlush = classToFlush.getSuperclass();
		}
		while (classToFlush != null && classToFlush != Object.class);

		return beanInfo;
	}


	/**
	 * The BeanInfo object for the introspected bean class.
	 */
	private final BeanInfo beanInfo;

	/**
	 * PropertyDescriptor objects keyed by property name String.
	 * <p>
	 * {@link CachedIntrospectionResults#CachedIntrospectionResults(java.lang.Class)}
	 * 构造器中添加值
	 * </p>
	 */
	private final Map<String, PropertyDescriptor> propertyDescriptors;

	/**
	 * TypeDescriptor objects keyed by PropertyDescriptor.
	 */
	private final ConcurrentMap<PropertyDescriptor, TypeDescriptor> typeDescriptorCache;


	/**
	 * Create a new CachedIntrospectionResults instance for the given class.
	 * <p>
	 * {@link CachedIntrospectionResults#forClass(java.lang.Class)}中调用
	 * </p>
	 * 注意,构造方法是私有的。
	 *
	 * @param beanClass the bean class to analyze
	 * @throws BeansException in case of introspection failure
	 */
	private CachedIntrospectionResults(Class<?> beanClass) throws BeansException {
		try {
			if (logger.isTraceEnabled()) {
				logger.trace("Getting BeanInfo for class [" + beanClass.getName() + "]");
			}
			this.beanInfo = getBeanInfo(beanClass);

			if (logger.isTraceEnabled()) {
				logger.trace("Caching PropertyDescriptors for class [" + beanClass.getName() + "]");
			}
			this.propertyDescriptors = new LinkedHashMap<>();

			/**
			 * 有read方法的属性集合
			 */
			Set<String> readMethodNames = new HashSet<>();

			// This call is slow so we do it once.
			/**
			 * getXX,setXX的属性方法。
			 * 可能只有getXX或者只有setXX。
			 * 每一个XX都会封装成一个{@link PropertyDescriptor}
			 */
			PropertyDescriptor[] pds = this.beanInfo.getPropertyDescriptors();
			for (PropertyDescriptor pd : pds) {
				if (Class.class == beanClass
						&& !("name".equals(pd.getName())
						|| (pd.getName().endsWith("Name") && String.class == pd.getPropertyType()))) {
					// Only allow all name variants of Class properties
					/**
					 * 如果传入的beanClass是{@link Class}
					 * 且属性即不为"name",也不为xxName(属性的Java类型为String)
					 * 则忽略。
					 * 意思也就是,如果传入的beanClass是{@link Class},
					 * 只处理那些属性名称为"name","xxName"且是String类型的属性
					 */
					continue;
				}
				if (URL.class == beanClass && "content".equals(pd.getName())) {
					// Only allow URL attribute introspection, not content resolution
					/**
					 * 如果传入的beanClass是{@link URL},
					 * 不处理"content"属性
					 */
					continue;
				}
				if (pd.getWriteMethod() == null && isInvalidReadOnlyPropertyType(pd.getPropertyType(), beanClass)) {
					// Ignore read-only properties such as ClassLoader - no need to bind to those
					/**
					 * 没有write方法(那就只有read方法了……)
					 * 且满足方法条件,看注释吧
					 */
					continue;
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Found bean property '" + pd.getName() + "'" +
							(pd.getPropertyType() != null ? " of type [" + pd.getPropertyType().getName() + "]" : "") +
							(pd.getPropertyEditorClass() != null ?
									"; editor [" + pd.getPropertyEditorClass().getName() + "]" : ""));
				}
				pd = buildGenericTypeAwarePropertyDescriptor(beanClass, pd);
				this.propertyDescriptors.put(pd.getName(), pd);
				Method readMethod = pd.getReadMethod();
				if (readMethod != null) {
					readMethodNames.add(readMethod.getName());
				}
			}

			// Explicitly check implemented interfaces for setter/getter methods as well,
			// in particular for Java 8 default methods...
			Class<?> currClass = beanClass;
			while (currClass != null && currClass != Object.class) {
				introspectInterfaces(beanClass, currClass, readMethodNames);
				/**
				 * 递归遍历父类
				 */
				currClass = currClass.getSuperclass();
			}

			// Check for record-style accessors without prefix: e.g. "lastName()"
			// - accessor method directly referring to instance field of same name
			// - same convention for component accessors of Java 15 record classes
			introspectPlainAccessors(beanClass, readMethodNames);

			this.typeDescriptorCache = new ConcurrentReferenceHashMap<>();
		} catch (IntrospectionException ex) {
			throw new FatalBeanException("Failed to obtain BeanInfo for class [" + beanClass.getName() + "]", ex);
		}
	}

	/**
	 * <p>
	 * {@link CachedIntrospectionResults#CachedIntrospectionResults(java.lang.Class)}
	 * 中调用
	 * </p>
	 *
	 * @param beanClass
	 * @param currClass
	 * @param readMethodNames
	 * @throws IntrospectionException
	 */
	private void introspectInterfaces(Class<?> beanClass, Class<?> currClass, Set<String> readMethodNames)
			throws IntrospectionException {

		for (Class<?> ifc : currClass.getInterfaces()) {
			/**
			 *  获取所有实现的接口
			 */
			if (!ClassUtils.isJavaLanguageInterface(ifc)) {
				for (PropertyDescriptor pd : getBeanInfo(ifc).getPropertyDescriptors()) {
					PropertyDescriptor existingPd = this.propertyDescriptors.get(pd.getName());
					if (existingPd == null ||
							(existingPd.getReadMethod() == null && pd.getReadMethod() != null)) {
						// GenericTypeAwarePropertyDescriptor leniently resolves a set* write method
						// against a declared read method, so we prefer read method descriptors here.
						pd = buildGenericTypeAwarePropertyDescriptor(beanClass, pd);
						if (pd.getWriteMethod() == null &&
								isInvalidReadOnlyPropertyType(pd.getPropertyType(), beanClass)) {
							// Ignore read-only properties such as ClassLoader - no need to bind to those
							continue;
						}
						this.propertyDescriptors.put(pd.getName(), pd);
						Method readMethod = pd.getReadMethod();
						if (readMethod != null) {
							readMethodNames.add(readMethod.getName());
						}
					}
				}
				introspectInterfaces(ifc, ifc, readMethodNames);
			}
		}
	}

	private void introspectPlainAccessors(Class<?> beanClass, Set<String> readMethodNames)
			throws IntrospectionException {

		for (Method method : beanClass.getMethods()) {
			if (!this.propertyDescriptors.containsKey(method.getName()) &&
					!readMethodNames.contains(method.getName()) && isPlainAccessor(method)) {
				this.propertyDescriptors.put(method.getName(),
						new GenericTypeAwarePropertyDescriptor(beanClass, method.getName(), method, null, null));
				readMethodNames.add(method.getName());
			}
		}
	}

	private boolean isPlainAccessor(Method method) {
		if (Modifier.isStatic(method.getModifiers()) ||
				method.getDeclaringClass() == Object.class || method.getDeclaringClass() == Class.class ||
				method.getParameterCount() > 0 || method.getReturnType() == void.class ||
				isInvalidReadOnlyPropertyType(method.getReturnType(), method.getDeclaringClass())) {
			return false;
		}
		try {
			// Accessor method referring to instance field of same name?
			method.getDeclaringClass().getDeclaredField(method.getName());
			return true;
		} catch (Exception ex) {
			return false;
		}
	}

	/**
	 * <p>
	 * {@link CachedIntrospectionResults#CachedIntrospectionResults(java.lang.Class)}
	 * 中调用
	 * </p>
	 * 满足以下条件
	 * 1,returnType不为null
	 * 2,returnType要么是{@link ClassLoader}的实现类
	 * 3,returnType要么是{@link ProtectionDomain}的实现类
	 * 4,returnType要么是{@link AutoCloseable}的实现类,且beanClass不是{@link AutoCloseable}的实现类
	 *
	 * @param returnType
	 * @param beanClass
	 * @return
	 */
	private boolean isInvalidReadOnlyPropertyType(@Nullable Class<?> returnType, Class<?> beanClass) {
		return (returnType != null && (ClassLoader.class.isAssignableFrom(returnType) ||
				ProtectionDomain.class.isAssignableFrom(returnType) ||
				(AutoCloseable.class.isAssignableFrom(returnType) &&
						!AutoCloseable.class.isAssignableFrom(beanClass))));
	}


	BeanInfo getBeanInfo() {
		return this.beanInfo;
	}

	Class<?> getBeanClass() {
		return this.beanInfo.getBeanDescriptor().getBeanClass();
	}

	@Nullable
	PropertyDescriptor getPropertyDescriptor(String name) {
		PropertyDescriptor pd = this.propertyDescriptors.get(name);
		if (pd == null && StringUtils.hasLength(name)) {
			// Same lenient fallback checking as in Property...
			pd = this.propertyDescriptors.get(StringUtils.uncapitalize(name));
			if (pd == null) {
				pd = this.propertyDescriptors.get(StringUtils.capitalize(name));
			}
		}
		return pd;
	}

	PropertyDescriptor[] getPropertyDescriptors() {
		return this.propertyDescriptors.values().toArray(PropertyDescriptorUtils.EMPTY_PROPERTY_DESCRIPTOR_ARRAY);
	}

	private PropertyDescriptor buildGenericTypeAwarePropertyDescriptor(Class<?> beanClass, PropertyDescriptor pd) {
		try {
			return new GenericTypeAwarePropertyDescriptor(beanClass, pd.getName(), pd.getReadMethod(),
					pd.getWriteMethod(), pd.getPropertyEditorClass());
		} catch (IntrospectionException ex) {
			throw new FatalBeanException("Failed to re-introspect class [" + beanClass.getName() + "]", ex);
		}
	}

	TypeDescriptor addTypeDescriptor(PropertyDescriptor pd, TypeDescriptor td) {
		TypeDescriptor existing = this.typeDescriptorCache.putIfAbsent(pd, td);
		return (existing != null ? existing : td);
	}

	@Nullable
	TypeDescriptor getTypeDescriptor(PropertyDescriptor pd) {
		return this.typeDescriptorCache.get(pd);
	}

}
