/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.aop.aspectj.autoproxy.AspectJAwareAdvisorAutoProxyCreator;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Utility class for handling registration of AOP auto-proxy creators.
 *
 * <p>Only a single auto-proxy creator should be registered yet multiple concrete
 * implementations are available. This class provides a simple escalation protocol,
 * allowing a caller to request a particular auto-proxy creator and know that creator,
 * <i>or a more capable variant thereof</i>, will be registered as a post-processor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @see AopNamespaceUtils
 * @since 2.5
 */
public abstract class AopConfigUtils {

	/**
	 * The bean name of the internally managed auto-proxy creator.
	 */
	public static final String AUTO_PROXY_CREATOR_BEAN_NAME =
			"org.springframework.aop.config.internalAutoProxyCreator";

	/**
	 * Stores the auto proxy creator classes in escalation order.
	 */
	private static final List<Class<?>> APC_PRIORITY_LIST = new ArrayList<>(3);

	static {
		// Set up the escalation list...
		APC_PRIORITY_LIST.add(InfrastructureAdvisorAutoProxyCreator.class);
		APC_PRIORITY_LIST.add(AspectJAwareAdvisorAutoProxyCreator.class);
		APC_PRIORITY_LIST.add(AnnotationAwareAspectJAutoProxyCreator.class);
	}


	@Nullable
	public static BeanDefinition registerAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAutoProxyCreatorIfNecessary(registry, null);
	}

	@Nullable
	public static BeanDefinition registerAutoProxyCreatorIfNecessary(
			BeanDefinitionRegistry registry, @Nullable Object source) {

		return registerOrEscalateApcAsRequired(InfrastructureAdvisorAutoProxyCreator.class, registry, source);
	}

	@Nullable
	public static BeanDefinition registerAspectJAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAspectJAutoProxyCreatorIfNecessary(registry, null);
	}

	/**
	 * <p>
	 * {@link AopNamespaceUtils#registerAspectJAutoProxyCreatorIfNecessary(org.springframework.beans.factory.xml.ParserContext, org.w3c.dom.Element)}
	 * 中调用
	 * </p>
	 *
	 * @param registry
	 * @param source
	 * @return
	 */
	@Nullable
	public static BeanDefinition registerAspectJAutoProxyCreatorIfNecessary(
			BeanDefinitionRegistry registry, @Nullable Object source) {

		return registerOrEscalateApcAsRequired(AspectJAwareAdvisorAutoProxyCreator.class, registry, source);
	}

	@Nullable
	public static BeanDefinition registerAspectJAnnotationAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAspectJAnnotationAutoProxyCreatorIfNecessary(registry, null);
	}

	/**
	 * <p>
	 * {@link AopNamespaceUtils#registerAspectJAnnotationAutoProxyCreatorIfNecessary(org.springframework.beans.factory.xml.ParserContext, org.w3c.dom.Element)}
	 * 中调用
	 * </p>
	 *
	 * @param registry
	 * @param source
	 * @return
	 */
	@Nullable
	public static BeanDefinition registerAspectJAnnotationAutoProxyCreatorIfNecessary(
			BeanDefinitionRegistry registry, @Nullable Object source) {

		return registerOrEscalateApcAsRequired(AnnotationAwareAspectJAutoProxyCreator.class, registry, source);
	}

	/**
	 * <p>
	 * {@link AopNamespaceUtils#useClassProxyingIfNecessary(org.springframework.beans.factory.support.BeanDefinitionRegistry, org.w3c.dom.Element)}
	 * 中调用
	 * "proxy-target-class"属性,值为"true"
	 * </p>
	 * 设置名字为{@link AopConfigUtils#AUTO_PROXY_CREATOR_BEAN_NAME}的bean,
	 * 配置项"proxyTargetClass"为true
	 *
	 * @param registry
	 */
	public static void forceAutoProxyCreatorToUseClassProxying(BeanDefinitionRegistry registry) {
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			BeanDefinition definition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			definition.getPropertyValues().add("proxyTargetClass", Boolean.TRUE);
		}
	}

	/**
	 * <p>
	 * {@link AopNamespaceUtils#useClassProxyingIfNecessary(org.springframework.beans.factory.support.BeanDefinitionRegistry, org.w3c.dom.Element)}
	 * 中调用
	 * "expose-proxy"属性,值为"true"
	 * </p>
	 * 设置名字为{@link AopConfigUtils#AUTO_PROXY_CREATOR_BEAN_NAME}的bean,
	 * 配置项"exposeProxy"为true
	 *
	 * @param registry
	 */
	public static void forceAutoProxyCreatorToExposeProxy(BeanDefinitionRegistry registry) {
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			BeanDefinition definition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			definition.getPropertyValues().add("exposeProxy", Boolean.TRUE);
		}
	}

	/**
	 * <p>
	 * {@link AopConfigUtils#registerAspectJAutoProxyCreatorIfNecessary(org.springframework.beans.factory.support.BeanDefinitionRegistry, java.lang.Object)}
	 * 中调用
	 * {@link AopConfigUtils#registerAspectJAnnotationAutoProxyCreatorIfNecessary(org.springframework.beans.factory.support.BeanDefinitionRegistry, java.lang.Object)}
	 * 中调用
	 * </p>
	 * 向spring中注册id为{@link AopConfigUtils#AUTO_PROXY_CREATOR_BEAN_NAME}的BeanDefinition。
	 * class按如下顺序，越往下权重越高
	 * {@link InfrastructureAdvisorAutoProxyCreator}
	 * {@link AspectJAwareAdvisorAutoProxyCreator}
	 * {@link AnnotationAwareAspectJAutoProxyCreator}
	 *
	 * @param cls
	 * @param registry
	 * @param source
	 * @return
	 */
	@Nullable
	private static BeanDefinition registerOrEscalateApcAsRequired(Class<?> cls,
																  BeanDefinitionRegistry registry,
																  @Nullable Object source) {

		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");

		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			/**
			 * 该名字的bean,会在方法下面注册进spring。进入这个分支，说明方法不是第一次调用了
			 */
			BeanDefinition apcDefinition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			if (!cls.getName().equals(apcDefinition.getBeanClassName())) {
				/**
				 * 注册的名字为{@link AopConfigUtils#AUTO_PROXY_CREATOR_BEAN_NAME}的bean,与传入的cls不一致
				 * 这个返回的是{@link AopConfigUtils#APC_PRIORITY_LIST}里添加的三个class的index
				 */
				int currentPriority = findPriorityForClass(apcDefinition.getBeanClassName());
				int requiredPriority = findPriorityForClass(cls);
				if (currentPriority < requiredPriority) {
					/**
					 *{@link AopConfigUtils#APC_PRIORITY_LIST}里面的三个类
					 * {@link InfrastructureAdvisorAutoProxyCreator}
					 * {@link AspectJAwareAdvisorAutoProxyCreator}
					 * {@link AnnotationAwareAspectJAutoProxyCreator}
					 * 越往下的权限越高,会覆盖掉上面的类
					 */
					apcDefinition.setBeanClassName(cls.getName());
				}
			}
			return null;
		}

		RootBeanDefinition beanDefinition = new RootBeanDefinition(cls);
		beanDefinition.setSource(source);
		/**
		 * order设置为{@link Integer#MIN_VALUE}了
		 */
		beanDefinition.getPropertyValues().add("order", Ordered.HIGHEST_PRECEDENCE);
		/**
		 * 系统内置的bean
		 */
		beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
		registry.registerBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME, beanDefinition);
		return beanDefinition;
	}

	private static int findPriorityForClass(Class<?> clazz) {
		return APC_PRIORITY_LIST.indexOf(clazz);
	}

	private static int findPriorityForClass(@Nullable String className) {
		for (int i = 0; i < APC_PRIORITY_LIST.size(); i++) {
			Class<?> clazz = APC_PRIORITY_LIST.get(i);
			if (clazz.getName().equals(className)) {
				return i;
			}
		}
		throw new IllegalArgumentException(
				"Class name [" + className + "] is not a known auto-proxy creator class");
	}

}
