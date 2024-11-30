/*
 * Copyright 2002-2021 the original author or authors.
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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AbstractTypeHierarchyTraversingFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Parser for the @{@link ComponentScan} annotation.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @see ClassPathBeanDefinitionScanner#scan(String...)
 * @see ComponentScanBeanDefinitionParser
 * @since 3.1
 */
class ComponentScanAnnotationParser {

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	private final BeanNameGenerator beanNameGenerator;

	private final BeanDefinitionRegistry registry;


	/**
	 * {@link ConfigurationClassParser#ConfigurationClassParser(org.springframework.core.type.classreading.MetadataReaderFactory, org.springframework.beans.factory.parsing.ProblemReporter, org.springframework.core.env.Environment, org.springframework.core.io.ResourceLoader, org.springframework.beans.factory.support.BeanNameGenerator, org.springframework.beans.factory.support.BeanDefinitionRegistry)}
	 * 中被调用
	 *
	 * @param environment
	 * @param resourceLoader
	 * @param beanNameGenerator
	 * @param registry
	 */
	public ComponentScanAnnotationParser(Environment environment, ResourceLoader resourceLoader,
										 BeanNameGenerator beanNameGenerator, BeanDefinitionRegistry registry) {

		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.beanNameGenerator = beanNameGenerator;
		this.registry = registry;
	}

	/**
	 * <p>
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 * </p>
	 *
	 * @param componentScan
	 * @param declaringClass 标注{@link ComponentScan}的类
	 * @return
	 */
	public Set<BeanDefinitionHolder> parse(AnnotationAttributes componentScan, String declaringClass) {
		ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(this.registry,
				componentScan.getBoolean("useDefaultFilters"), this.environment, this.resourceLoader);

		/**
		 * {@link ComponentScan}的{@link ComponentScan#nameGenerator()}默认就是{@link BeanNameGenerator}
		 */
		Class<? extends BeanNameGenerator> generatorClass = componentScan.getClass("nameGenerator");
		boolean useInheritedGenerator = (BeanNameGenerator.class == generatorClass);
		scanner.setBeanNameGenerator(useInheritedGenerator ? this.beanNameGenerator :
				BeanUtils.instantiateClass(generatorClass));

		/**
		 * {@link ComponentScan#scopedProxy()}默认即是{@link ScopedProxyMode#DEFAULT}
		 */
		ScopedProxyMode scopedProxyMode = componentScan.getEnum("scopedProxy");
		if (scopedProxyMode != ScopedProxyMode.DEFAULT) {
			scanner.setScopedProxyMode(scopedProxyMode);
		} else {
			Class<? extends ScopeMetadataResolver> resolverClass = componentScan.getClass("scopeResolver");
			scanner.setScopeMetadataResolver(BeanUtils.instantiateClass(resolverClass));
		}

		scanner.setResourcePattern(componentScan.getString("resourcePattern"));

		/**
		 * 默认这两者应该都是null
		 */
		for (AnnotationAttributes includeFilterAttributes : componentScan.getAnnotationArray("includeFilters")) {
			List<TypeFilter> typeFilters = TypeFilterUtils.createTypeFiltersFor(includeFilterAttributes, this.environment,
					this.resourceLoader, this.registry);
			for (TypeFilter typeFilter : typeFilters) {
				scanner.addIncludeFilter(typeFilter);
			}
		}
		for (AnnotationAttributes excludeFilterAttributes : componentScan.getAnnotationArray("excludeFilters")) {
			List<TypeFilter> typeFilters = TypeFilterUtils.createTypeFiltersFor(excludeFilterAttributes, this.environment,
					this.resourceLoader, this.registry);
			for (TypeFilter typeFilter : typeFilters) {
				scanner.addExcludeFilter(typeFilter);
			}
		}

		boolean lazyInit = componentScan.getBoolean("lazyInit");
		if (lazyInit) {
			scanner.getBeanDefinitionDefaults().setLazyInit(true);
		}

		/**
		 * 是一个set
		 */
		Set<String> basePackages = new LinkedHashSet<>();
		String[] basePackagesArray = componentScan.getStringArray("basePackages");
		for (String pkg : basePackagesArray) {
			String[] tokenized = StringUtils.tokenizeToStringArray(this.environment.resolvePlaceholders(pkg),
					ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
			Collections.addAll(basePackages, tokenized);
		}
		for (Class<?> clazz : componentScan.getClassArray("basePackageClasses")) {
			/**
			 * 扫描这个clazz所在的包,包括子包
			 */
			basePackages.add(ClassUtils.getPackageName(clazz));
		}
		if (basePackages.isEmpty()) {
			/**
			 * 如果没有配置{@link ComponentScan#value()},{@link ComponentScan#basePackages()},{@link ComponentScan#basePackageClasses()}
			 * 即，没有配置扫描的包,则扫描该注解所在的类的包名
			 */
			basePackages.add(ClassUtils.getPackageName(declaringClass));
		}
		/**
		 * 把{@link ComponentScan}标签所在的类排除，因为已经加载过了
		 */
		scanner.addExcludeFilter(new AbstractTypeHierarchyTraversingFilter(false, false) {
			@Override
			protected boolean matchClassName(String className) {
				return declaringClass.equals(className);
			}
		});
		return scanner.doScan(StringUtils.toStringArray(basePackages));
	}

}
