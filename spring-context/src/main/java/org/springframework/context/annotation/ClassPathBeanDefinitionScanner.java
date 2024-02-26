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

package org.springframework.context.annotation;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.*;
import org.springframework.beans.factory.xml.BeanDefinitionParserDelegate;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnvironmentCapable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.PatternMatchUtils;

/**
 * A bean definition scanner that detects bean candidates on the classpath,
 * registering corresponding bean definitions with a given registry ({@code BeanFactory}
 * or {@code ApplicationContext}).
 *
 * <p>Candidate classes are detected through configurable type filters. The
 * default filters include classes that are annotated with Spring's
 * {@link org.springframework.stereotype.Component @Component},
 * {@link org.springframework.stereotype.Repository @Repository},
 * {@link org.springframework.stereotype.Service @Service}, or
 * {@link org.springframework.stereotype.Controller @Controller} stereotype.
 *
 * <p>Also supports Java EE 6's {@link javax.annotation.ManagedBean} and
 * JSR-330's {@link javax.inject.Named} annotations, if available.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Chris Beams
 * @see AnnotationConfigApplicationContext#scan
 * @see org.springframework.stereotype.Component
 * @see org.springframework.stereotype.Repository
 * @see org.springframework.stereotype.Service
 * @see org.springframework.stereotype.Controller
 * @since 2.5
 */
public class ClassPathBeanDefinitionScanner extends ClassPathScanningCandidateComponentProvider {

	/**
	 * {@link AnnotationConfigApplicationContext}
	 * 如果是<context:component-scan base-package="com"/>，则传入的是
	 * {@link DefaultListableBeanFactory}
	 */
	private final BeanDefinitionRegistry registry;

	/**
	 * {@link ComponentScanBeanDefinitionParser#configureScanner(org.springframework.beans.factory.xml.ParserContext, org.w3c.dom.Element)}
	 * 中调用
	 * 赋值为{@link BeanDefinitionParserDelegate#getBeanDefinitionDefaults()}
	 */
	private BeanDefinitionDefaults beanDefinitionDefaults = new BeanDefinitionDefaults();

	@Nullable
	private String[] autowireCandidatePatterns;

	private BeanNameGenerator beanNameGenerator = AnnotationBeanNameGenerator.INSTANCE;

	private ScopeMetadataResolver scopeMetadataResolver = new AnnotationScopeMetadataResolver();

	private boolean includeAnnotationConfig = true;


	/**
	 * Create a new {@code ClassPathBeanDefinitionScanner} for the given bean factory.
	 * <p>
	 * {@link AnnotationConfigApplicationContext#AnnotationConfigApplicationContext()}
	 * 中被调用
	 * 传参{@link AnnotationConfigApplicationContext}
	 * </p>
	 *
	 * @param registry the {@code BeanFactory} to load bean definitions into, in the form
	 *                 of a {@code BeanDefinitionRegistry}
	 */
	public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry) {
		this(registry, true);
	}

	/**
	 * Create a new {@code ClassPathBeanDefinitionScanner} for the given bean factory.
	 * <p>If the passed-in bean factory does not only implement the
	 * {@code BeanDefinitionRegistry} interface but also the {@code ResourceLoader}
	 * interface, it will be used as default {@code ResourceLoader} as well. This will
	 * usually be the case for {@link org.springframework.context.ApplicationContext}
	 * implementations.
	 * <p>If given a plain {@code BeanDefinitionRegistry}, the default {@code ResourceLoader}
	 * will be a {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver}.
	 * <p>If the passed-in bean factory also implements {@link EnvironmentCapable} its
	 * environment will be used by this reader.  Otherwise, the reader will initialize and
	 * use a {@link org.springframework.core.env.StandardEnvironment}. All
	 * {@code ApplicationContext} implementations are {@code EnvironmentCapable}, while
	 * normal {@code BeanFactory} implementations are not.
	 * <p>
	 * {@link ClassPathBeanDefinitionScanner#ClassPathBeanDefinitionScanner(org.springframework.beans.factory.support.BeanDefinitionRegistry)}
	 * 中调用
	 * </p>
	 *
	 * @param registry          the {@code BeanFactory} to load bean definitions into, in the form
	 *                          of a {@code BeanDefinitionRegistry}
	 *                          {@link AnnotationConfigApplicationContext}
	 * @param useDefaultFilters whether to include the default filters for the
	 *                          {@link org.springframework.stereotype.Component @Component},
	 *                          {@link org.springframework.stereotype.Repository @Repository},
	 *                          {@link org.springframework.stereotype.Service @Service}, and
	 *                          {@link org.springframework.stereotype.Controller @Controller} stereotype annotations
	 *                          true
	 * @see #setResourceLoader
	 * @see #setEnvironment
	 */
	public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry, boolean useDefaultFilters) {
		this(registry, useDefaultFilters, getOrCreateEnvironment(registry));
	}

	/**
	 * Create a new {@code ClassPathBeanDefinitionScanner} for the given bean factory and
	 * using the given {@link Environment} when evaluating bean definition profile metadata.
	 * <p>If the passed-in bean factory does not only implement the {@code
	 * BeanDefinitionRegistry} interface but also the {@link ResourceLoader} interface, it
	 * will be used as default {@code ResourceLoader} as well. This will usually be the
	 * case for {@link org.springframework.context.ApplicationContext} implementations.
	 * <p>If given a plain {@code BeanDefinitionRegistry}, the default {@code ResourceLoader}
	 * will be a {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver}.
	 * <p>
	 * {@link ClassPathBeanDefinitionScanner#ClassPathBeanDefinitionScanner(org.springframework.beans.factory.support.BeanDefinitionRegistry, boolean)}
	 * 中被调用
	 * </p>
	 *
	 * @param registry          the {@code BeanFactory} to load bean definitions into, in the form
	 *                          of a {@code BeanDefinitionRegistry}
	 *                          {@link AnnotationConfigApplicationContext}
	 * @param useDefaultFilters whether to include the default filters for the
	 *                          {@link org.springframework.stereotype.Component @Component},
	 *                          {@link org.springframework.stereotype.Repository @Repository},
	 *                          {@link org.springframework.stereotype.Service @Service}, and
	 *                          {@link org.springframework.stereotype.Controller @Controller} stereotype annotations
	 *                          true
	 * @param environment       the Spring {@link Environment} to use when evaluating bean
	 *                          definition profile metadata
	 *                          {@link StandardEnvironment#StandardEnvironment()}
	 * @see #setResourceLoader
	 * @since 3.1
	 */
	public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry, boolean useDefaultFilters,
										  Environment environment) {

		this(registry, useDefaultFilters, environment,
				(registry instanceof ResourceLoader ? (ResourceLoader) registry : null));
	}

	/**
	 * Create a new {@code ClassPathBeanDefinitionScanner} for the given bean factory and
	 * using the given {@link Environment} when evaluating bean definition profile metadata.
	 * <p>
	 * {@link ClassPathBeanDefinitionScanner#ClassPathBeanDefinitionScanner(org.springframework.beans.factory.support.BeanDefinitionRegistry, boolean, org.springframework.core.env.Environment)}
	 * 中调用
	 * {@link ComponentScanBeanDefinitionParser#createScanner(org.springframework.beans.factory.xml.XmlReaderContext, boolean)}
	 * 中调用
	 * {@link ComponentScanAnnotationParser#parse(org.springframework.core.annotation.AnnotationAttributes, java.lang.String)}中调用
	 * </p>
	 *
	 * @param registry          the {@code BeanFactory} to load bean definitions into, in the form
	 *                          of a {@code BeanDefinitionRegistry}
	 *                          {@link AnnotationConfigApplicationContext}
	 *                          {@link DefaultListableBeanFactory}
	 * @param useDefaultFilters whether to include the default filters for the
	 *                          {@link org.springframework.stereotype.Component @Component},
	 *                          {@link org.springframework.stereotype.Repository @Repository},
	 *                          {@link org.springframework.stereotype.Service @Service}, and
	 *                          {@link org.springframework.stereotype.Controller @Controller} stereotype annotations
	 *                          true
	 * @param environment       the Spring {@link Environment} to use when evaluating bean
	 *                          definition profile metadata
	 *                          {@link StandardEnvironment#StandardEnvironment()}
	 * @param resourceLoader    the {@link ResourceLoader} to use
	 *                          {@link AnnotationConfigApplicationContext}
	 * @since 4.3.6
	 */
	public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry, boolean useDefaultFilters,
										  Environment environment, @Nullable ResourceLoader resourceLoader) {

		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		this.registry = registry;

		if (useDefaultFilters) {
			// 这个方法很重要
			registerDefaultFilters();
		}
		setEnvironment(environment);
		/**
		 * 可以参考{@link AbstractBeanDefinitionReader#resourceLoader}
		 */
		setResourceLoader(resourceLoader);
	}


	/**
	 * Return the BeanDefinitionRegistry that this scanner operates on.
	 */
	@Override
	public final BeanDefinitionRegistry getRegistry() {
		return this.registry;
	}

	/**
	 * Set the defaults to use for detected beans.
	 * <p>
	 * {@link ComponentScanBeanDefinitionParser#configureScanner(org.springframework.beans.factory.xml.ParserContext, org.w3c.dom.Element)}
	 * 中调用
	 * 赋值为{@link BeanDefinitionParserDelegate#getBeanDefinitionDefaults()}
	 * </p>
	 *
	 * @see BeanDefinitionDefaults
	 */
	public void setBeanDefinitionDefaults(@Nullable BeanDefinitionDefaults beanDefinitionDefaults) {
		this.beanDefinitionDefaults =
				(beanDefinitionDefaults != null ? beanDefinitionDefaults : new BeanDefinitionDefaults());
	}

	/**
	 * Return the defaults to use for detected beans (never {@code null}).
	 *
	 * @since 4.1
	 */
	public BeanDefinitionDefaults getBeanDefinitionDefaults() {
		return this.beanDefinitionDefaults;
	}

	/**
	 * Set the name-matching patterns for determining autowire candidates.
	 *
	 * @param autowireCandidatePatterns the patterns to match against
	 */
	public void setAutowireCandidatePatterns(@Nullable String... autowireCandidatePatterns) {
		this.autowireCandidatePatterns = autowireCandidatePatterns;
	}

	/**
	 * Set the BeanNameGenerator to use for detected bean classes.
	 * <p>Default is a {@link AnnotationBeanNameGenerator}.
	 */
	public void setBeanNameGenerator(@Nullable BeanNameGenerator beanNameGenerator) {
		this.beanNameGenerator =
				(beanNameGenerator != null ? beanNameGenerator : AnnotationBeanNameGenerator.INSTANCE);
	}

	/**
	 * Set the ScopeMetadataResolver to use for detected bean classes.
	 * Note that this will override any custom "scopedProxyMode" setting.
	 * <p>The default is an {@link AnnotationScopeMetadataResolver}.
	 *
	 * @see #setScopedProxyMode
	 */
	public void setScopeMetadataResolver(@Nullable ScopeMetadataResolver scopeMetadataResolver) {
		this.scopeMetadataResolver =
				(scopeMetadataResolver != null ? scopeMetadataResolver : new AnnotationScopeMetadataResolver());
	}

	/**
	 * Specify the proxy behavior for non-singleton scoped beans.
	 * Note that this will override any custom "scopeMetadataResolver" setting.
	 * <p>The default is {@link ScopedProxyMode#NO}.
	 *
	 * @see #setScopeMetadataResolver
	 */
	public void setScopedProxyMode(ScopedProxyMode scopedProxyMode) {
		this.scopeMetadataResolver = new AnnotationScopeMetadataResolver(scopedProxyMode);
	}

	/**
	 * Specify whether to register annotation config post-processors.
	 * <p>The default is to register the post-processors. Turn this off
	 * to be able to ignore the annotations or to process them differently.
	 */
	public void setIncludeAnnotationConfig(boolean includeAnnotationConfig) {
		this.includeAnnotationConfig = includeAnnotationConfig;
	}


	/**
	 * Perform a scan within the specified base packages.
	 *
	 * @param basePackages the packages to check for annotated classes
	 * @return number of beans registered
	 */
	public int scan(String... basePackages) {
		int beanCountAtScanStart = this.registry.getBeanDefinitionCount();

		doScan(basePackages);

		// Register annotation config processors, if necessary.
		if (this.includeAnnotationConfig) {
			AnnotationConfigUtils.registerAnnotationConfigProcessors(this.registry);
		}

		return (this.registry.getBeanDefinitionCount() - beanCountAtScanStart);
	}

	/**
	 * Perform a scan within the specified base packages,
	 * returning the registered bean definitions.
	 * <p>This method does <i>not</i> register an annotation config processor
	 * but rather leaves this up to the caller.
	 * <p>
	 * {@link ComponentScanBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * </p>
	 *
	 * @param basePackages the packages to check for annotated classes
	 * @return set of beans registered if any for tooling registration purposes (never {@code null})
	 */
	protected Set<BeanDefinitionHolder> doScan(String... basePackages) {
		Assert.notEmpty(basePackages, "At least one base package must be specified");
		// 声明一个存放解析到的BeanDefinition的容器
		Set<BeanDefinitionHolder> beanDefinitions = new LinkedHashSet<>();
		for (String basePackage : basePackages) {
			/**
			 * 返回的是{@link ScannedGenericBeanDefinition}
			 */
			Set<BeanDefinition> candidates = findCandidateComponents(basePackage);
			for (BeanDefinition candidate : candidates) {
				/**
				 * 获取{@link ScopeMetadata}
				 * 注意，这个地方有两个作用:
				 * 第一个是获取{@link Scope#scopeName()}
				 * 第二个是获取{@link Scope#proxyMode()}
				 */
				ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(candidate);
				// 设置scope
				candidate.setScope(scopeMetadata.getScopeName());
				// 获取beanName
				String beanName = this.beanNameGenerator.generateBeanName(candidate, this.registry);
				if (candidate instanceof AbstractBeanDefinition) {
					// 走这个分支
					postProcessBeanDefinition((AbstractBeanDefinition) candidate, beanName);
				}
				if (candidate instanceof AnnotatedBeanDefinition) {
					// 走这个分支
					AnnotationConfigUtils.processCommonDefinitionAnnotations((AnnotatedBeanDefinition) candidate);
				}
				if (checkCandidate(beanName, candidate)) {
					/**
					 * beanName可用，且需要注册
					 * 注意，每一个{@link BeanDefinition}都需要包装成{@link BeanDefinitionHolder}才能注册
					 * 且beanName封装到了{@link BeanDefinitionHolder}中
					 */
					BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(candidate, beanName);
					// 下面这个代码挺重要的，等有时间看下吧
					definitionHolder =
							AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
					beanDefinitions.add(definitionHolder);
					registerBeanDefinition(definitionHolder, this.registry);
				}
			}
		}
		return beanDefinitions;
	}

	/**
	 * Apply further settings to the given bean definition,
	 * beyond the contents retrieved from scanning the component class.
	 * <p>
	 * {@link ClassPathBeanDefinitionScanner#doScan(java.lang.String...)}
	 * 中调用
	 * </p>
	 * 调用{@link AbstractBeanDefinition#applyDefaults(org.springframework.beans.factory.support.BeanDefinitionDefaults)}
	 * 传递参数{@link ClassPathBeanDefinitionScanner#beanDefinitionDefaults}
	 * 和{@link AbstractBeanDefinition#setAutowireCandidate(boolean)}
	 *
	 * @param beanDefinition the scanned bean definition
	 * @param beanName       the generated bean name for the given bean
	 */
	protected void postProcessBeanDefinition(AbstractBeanDefinition beanDefinition, String beanName) {
		beanDefinition.applyDefaults(this.beanDefinitionDefaults);
		if (this.autowireCandidatePatterns != null) {
			beanDefinition.setAutowireCandidate(PatternMatchUtils.simpleMatch(this.autowireCandidatePatterns, beanName));
		}
	}

	/**
	 * Register the specified bean with the given registry.
	 * <p>Can be overridden in subclasses, e.g. to adapt the registration
	 * process or to register further bean definitions for each scanned bean.
	 * <p>
	 * {@link ClassPathBeanDefinitionScanner#doScan(java.lang.String...)}中调用
	 * </p>
	 *
	 * @param definitionHolder the bean definition plus bean name for the bean
	 * @param registry         the BeanDefinitionRegistry to register the bean with
	 */
	protected void registerBeanDefinition(BeanDefinitionHolder definitionHolder, BeanDefinitionRegistry registry) {
		BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, registry);
	}


	/**
	 * Check the given candidate's bean name, determining whether the corresponding
	 * bean definition needs to be registered or conflicts with an existing definition.
	 * 检查给定候选者的bean名称，确定对应的bean definition需要注册或与现有的definition冲突。
	 * 即检测给定的beanName是否可用
	 * <p>
	 * {@link ClassPathBeanDefinitionScanner#doScan(java.lang.String...)}中调用
	 * </p>
	 * <p>
	 * candidate /ˈkændɪdət/ 候选人
	 * </p>
	 *
	 * @param beanName       the suggested name for the bean
	 * @param beanDefinition the corresponding bean definition
	 * @return {@code true} if the bean can be registered as-is;
	 * {@code false} if it should be skipped because there is an
	 * existing, compatible bean definition for the specified name
	 * @throws IllegalStateException if an existing, incompatible bean definition
	 *                               has been found for the specified name
	 */
	protected boolean checkCandidate(String beanName, BeanDefinition beanDefinition) throws IllegalStateException {
		if (!this.registry.containsBeanDefinition(beanName)) {
			// 如果给定的beanName未被使用，直接返回true
			return true;
		}
		// 根据传入的beanName获取已经存在的BeanDefinition
		BeanDefinition existingDef = this.registry.getBeanDefinition(beanName);
		BeanDefinition originatingDef = existingDef.getOriginatingBeanDefinition();
		if (originatingDef != null) {
			// 拿到原始的BeanDefinition
			existingDef = originatingDef;
		}
		if (isCompatible(beanDefinition, existingDef)) {
			// 如果两个BeanDefinition兼容,返回false
			return false;
		}
		// 两个BeanDefinition不兼容,直接抛异常了
		throw new ConflictingBeanDefinitionException("Annotation-specified bean name '" + beanName +
				"' for bean class [" + beanDefinition.getBeanClassName() + "] conflicts with existing, " +
				"non-compatible bean definition of same name and class [" + existingDef.getBeanClassName() + "]");
	}

	/**
	 * Determine whether the given new bean definition is compatible with
	 * the given existing bean definition.
	 * <p>The default implementation considers them as compatible when the existing
	 * bean definition comes from the same source or from a non-scanning source.
	 * 确定给定的新bean definition是否与已经存在的 bean definition兼容。
	 * 如果这两个bean definition来自同一个source或者来自一个non-scanning source则认为是兼容的
	 * <p>
	 * {@link ClassPathBeanDefinitionScanner#checkCandidate(java.lang.String, org.springframework.beans.factory.config.BeanDefinition)}
	 * 调用
	 * </p>
	 *
	 * @param newDef      the new bean definition, originated from scanning
	 * @param existingDef the existing bean definition, potentially an
	 *                    explicitly defined one or a previously generated one from scanning
	 * @return whether the definitions are considered as compatible, with the
	 * new definition to be skipped in favor of the existing definition
	 */
	protected boolean isCompatible(BeanDefinition newDef, BeanDefinition existingDef) {
		/**
		 * 1,如果已经存在的BeanDefinition不是一个ScannedGenericBeanDefinition,则返回true,兼容
		 * 2,两个BeanDefinition的Source不为空，且equals,则返回true,兼容
		 * 3,两个BeanDefinition equals,则返回true,兼容
		 */
		return (!(existingDef instanceof ScannedGenericBeanDefinition) ||  // explicitly registered overriding bean
				(newDef.getSource() != null && newDef.getSource().equals(existingDef.getSource())) ||  // scanned same file twice
				newDef.equals(existingDef));  // scanned equivalent class twice
	}


	/**
	 * Get the Environment from the given registry if possible, otherwise return a new
	 * StandardEnvironment.
	 * <p>
	 * {@link ClassPathBeanDefinitionScanner#ClassPathBeanDefinitionScanner(org.springframework.beans.factory.support.BeanDefinitionRegistry, boolean)}
	 * 中被调用
	 * </p>
	 */
	private static Environment getOrCreateEnvironment(BeanDefinitionRegistry registry) {
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		if (registry instanceof EnvironmentCapable) {
			return ((EnvironmentCapable) registry).getEnvironment();
		}
		return new StandardEnvironment();
	}

}
