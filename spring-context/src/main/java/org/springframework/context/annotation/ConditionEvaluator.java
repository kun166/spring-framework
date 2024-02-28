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

package org.springframework.context.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnvironmentCapable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MultiValueMap;

/**
 * Internal class used to evaluate {@link Conditional} annotations.
 * 内部类评估注解
 *
 * @author Phillip Webb
 * @author Juergen Hoeller
 * @since 4.0
 */
class ConditionEvaluator {

	private final ConditionContextImpl context;


	/**
	 * Create a new {@link ConditionEvaluator} instance.
	 * {@link AnnotatedBeanDefinitionReader#AnnotatedBeanDefinitionReader(org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.core.env.Environment)}
	 * 中被调用
	 *
	 * @param registry       {@link AnnotationConfigApplicationContext}
	 * @param environment    {@link StandardEnvironment#StandardEnvironment()}
	 * @param resourceLoader null
	 */
	public ConditionEvaluator(@Nullable BeanDefinitionRegistry registry,
							  @Nullable Environment environment, @Nullable ResourceLoader resourceLoader) {

		this.context = new ConditionContextImpl(registry, environment, resourceLoader);
	}


	/**
	 * Determine if an item should be skipped based on {@code @Conditional} annotations.
	 * The {@link ConfigurationPhase} will be deduced from the type of item (i.e. a
	 * {@code @Configuration} class will be {@link ConfigurationPhase#PARSE_CONFIGURATION})
	 * <p>
	 * {@link AnnotatedBeanDefinitionReader#doRegisterBean(java.lang.Class, java.lang.String, java.lang.Class[], java.util.function.Supplier, org.springframework.beans.factory.config.BeanDefinitionCustomizer[])}
	 * 中被调用
	 * {@link ClassPathScanningCandidateComponentProvider#isConditionMatch(org.springframework.core.type.classreading.MetadataReader)}
	 * 中有调用
	 * 还有{@link ConfigurationClassBeanDefinitionReader}和{@link ConfigurationClassParser},{@link ConfigurationClassPostProcessor}
	 * 会调用，等用到的时候补上
	 * </p>
	 *
	 * @param metadata the meta data {@link StandardAnnotationMetadata}
	 * @return if the item should be skipped
	 */
	public boolean shouldSkip(AnnotatedTypeMetadata metadata) {
		return shouldSkip(metadata, null);
	}

	/**
	 * Determine if an item should be skipped based on {@code @Conditional} annotations.
	 * <p>
	 * {@link ConditionEvaluator#shouldSkip(org.springframework.core.type.AnnotatedTypeMetadata)}
	 * 中被调用
	 * {@link ConfigurationClassParser#processConfigurationClass(org.springframework.context.annotation.ConfigurationClass, java.util.function.Predicate)}
	 * 中调用
	 * {@link ConfigurationClassBeanDefinitionReader.TrackedConditionEvaluator#shouldSkip(org.springframework.context.annotation.ConfigurationClass)}
	 * 中调用
	 * </p>
	 *
	 * @param metadata the meta data {@link StandardAnnotationMetadata}
	 * @param phase    the phase of the call
	 *                 传递null
	 * @return if the item should be skipped
	 */
	public boolean shouldSkip(@Nullable AnnotatedTypeMetadata metadata, @Nullable ConfigurationPhase phase) {
		/**
		 * metadata为空，或者是没有{@link Conditional}注解，返回false
		 */
		if (metadata == null || !metadata.isAnnotated(Conditional.class.getName())) {
			return false;
		}

		if (phase == null) {
			if (metadata instanceof AnnotationMetadata &&
					ConfigurationClassUtils.isConfigurationCandidate((AnnotationMetadata) metadata)) {
				/**
				 * 符合候选者的条件
				 * 1,不是接口
				 * 2,有{@link Component},{@link ComponentScan},{@link Import},{@link ImportResource}注解之中的一个
				 * 3,或者方法上有{@link Bean}注解
				 */
				return shouldSkip(metadata, ConfigurationPhase.PARSE_CONFIGURATION);
			}
			// 说明不是注解方式的bean definition。也就不需要关注Conditional注解？
			return shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN);
		}

		List<Condition> conditions = new ArrayList<>();
		for (String[] conditionClasses : getConditionClasses(metadata)) {

			for (String conditionClass : conditionClasses) {
				Condition condition = getCondition(conditionClass, this.context.getClassLoader());
				conditions.add(condition);
			}
		}

		AnnotationAwareOrderComparator.sort(conditions);

		for (Condition condition : conditions) {
			ConfigurationPhase requiredPhase = null;
			if (condition instanceof ConfigurationCondition) {
				requiredPhase = ((ConfigurationCondition) condition).getConfigurationPhase();
			}
			if ((requiredPhase == null || requiredPhase == phase) && !condition.matches(this.context, metadata)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * {@link ConditionEvaluator#shouldSkip(org.springframework.core.type.AnnotatedTypeMetadata, org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase)}
	 * 中调用
	 * <p>
	 * 返回注解上的{@link Condition}数组集合集合
	 *
	 * @param metadata
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private List<String[]> getConditionClasses(AnnotatedTypeMetadata metadata) {
		/**
		 * 关于{@link Conditional},可以参考:https://www.jb51.net/program/30615648i.htm
		 */
		MultiValueMap<String, Object> attributes = metadata.getAllAnnotationAttributes(Conditional.class.getName(), true);
		Object values = (attributes != null ? attributes.get("value") : null);
		return (List<String[]>) (values != null ? values : Collections.emptyList());
	}

	private Condition getCondition(String conditionClassName, @Nullable ClassLoader classloader) {
		Class<?> conditionClass = ClassUtils.resolveClassName(conditionClassName, classloader);
		return (Condition) BeanUtils.instantiateClass(conditionClass);
	}


	/**
	 * Implementation of a {@link ConditionContext}.
	 */
	private static class ConditionContextImpl implements ConditionContext {

		/**
		 * {@link AnnotationConfigApplicationContext}
		 */
		@Nullable
		private final BeanDefinitionRegistry registry;

		/**
		 * {@link DefaultListableBeanFactory}
		 */
		@Nullable
		private final ConfigurableListableBeanFactory beanFactory;

		/**
		 * {@link StandardEnvironment#StandardEnvironment()}
		 */
		private final Environment environment;

		/**
		 * {@link AnnotationConfigApplicationContext}
		 */
		private final ResourceLoader resourceLoader;

		/**
		 * {@link ClassUtils#getDefaultClassLoader()}
		 */
		@Nullable
		private final ClassLoader classLoader;

		/**
		 * {@link ConditionEvaluator#ConditionEvaluator(org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.core.env.Environment, org.springframework.core.io.ResourceLoader)}
		 * 中被调用
		 *
		 * @param registry       {@link AnnotationConfigApplicationContext}
		 * @param environment    {@link StandardEnvironment#StandardEnvironment()}
		 * @param resourceLoader null
		 */
		public ConditionContextImpl(@Nullable BeanDefinitionRegistry registry,
									@Nullable Environment environment, @Nullable ResourceLoader resourceLoader) {

			this.registry = registry;
			/**
			 * {@link DefaultListableBeanFactory}
			 */
			this.beanFactory = deduceBeanFactory(registry);
			/**
			 * {@link StandardEnvironment#StandardEnvironment()}
			 */
			this.environment = (environment != null ? environment : deduceEnvironment(registry));
			/**
			 * {@link AnnotationConfigApplicationContext}
			 */
			this.resourceLoader = (resourceLoader != null ? resourceLoader : deduceResourceLoader(registry));
			/**
			 * {@link ClassUtils#getDefaultClassLoader()}
			 */
			this.classLoader = deduceClassLoader(resourceLoader, this.beanFactory);
		}

		/**
		 * {@link ConditionContextImpl#ConditionContextImpl(org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.core.env.Environment, org.springframework.core.io.ResourceLoader)}
		 * 中被调用
		 *
		 * @param source
		 * @return
		 */
		@Nullable
		private ConfigurableListableBeanFactory deduceBeanFactory(@Nullable BeanDefinitionRegistry source) {
			if (source instanceof ConfigurableListableBeanFactory) {
				return (ConfigurableListableBeanFactory) source;
			}
			if (source instanceof ConfigurableApplicationContext) {
				// 走下面这个
				return (((ConfigurableApplicationContext) source).getBeanFactory());
			}
			return null;
		}

		private Environment deduceEnvironment(@Nullable BeanDefinitionRegistry source) {
			if (source instanceof EnvironmentCapable) {
				return ((EnvironmentCapable) source).getEnvironment();
			}
			return new StandardEnvironment();
		}

		/**
		 * {@link ConditionContextImpl#ConditionContextImpl(org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.core.env.Environment, org.springframework.core.io.ResourceLoader)}
		 * 中被调用
		 *
		 * @param source {@link AnnotationConfigApplicationContext}
		 * @return
		 */
		private ResourceLoader deduceResourceLoader(@Nullable BeanDefinitionRegistry source) {
			if (source instanceof ResourceLoader) {
				// 走下面这个
				return (ResourceLoader) source;
			}
			return new DefaultResourceLoader();
		}

		/**
		 * {@link ConditionContextImpl#ConditionContextImpl(org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.core.env.Environment, org.springframework.core.io.ResourceLoader)}
		 * 中调用
		 *
		 * @param resourceLoader null
		 * @param beanFactory    {@link DefaultListableBeanFactory}
		 * @return
		 */
		@Nullable
		private ClassLoader deduceClassLoader(@Nullable ResourceLoader resourceLoader,
											  @Nullable ConfigurableListableBeanFactory beanFactory) {

			if (resourceLoader != null) {
				ClassLoader classLoader = resourceLoader.getClassLoader();
				if (classLoader != null) {
					return classLoader;
				}
			}
			if (beanFactory != null) {
				return beanFactory.getBeanClassLoader();
			}
			return ClassUtils.getDefaultClassLoader();
		}

		@Override
		public BeanDefinitionRegistry getRegistry() {
			Assert.state(this.registry != null, "No BeanDefinitionRegistry available");
			return this.registry;
		}

		@Override
		@Nullable
		public ConfigurableListableBeanFactory getBeanFactory() {
			return this.beanFactory;
		}

		@Override
		public Environment getEnvironment() {
			return this.environment;
		}

		@Override
		public ResourceLoader getResourceLoader() {
			return this.resourceLoader;
		}

		@Override
		@Nullable
		public ClassLoader getClassLoader() {
			return this.classLoader;
		}
	}

}
