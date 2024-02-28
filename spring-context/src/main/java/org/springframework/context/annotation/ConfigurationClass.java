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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.parsing.Location;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * Represents a user-defined {@link Configuration @Configuration} class.
 * <p>Includes a set of {@link Bean} methods, including all such methods
 * defined in the ancestry of the class, in a 'flattened-out' manner.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @see BeanMethod
 * @see ConfigurationClassParser
 * @since 3.0
 */
final class ConfigurationClass {

	/**
	 * 构造函数中赋值
	 * {@link AnnotatedBeanDefinition#getMetadata()}
	 */
	private final AnnotationMetadata metadata;

	/**
	 * {@link DescriptiveResource#DescriptiveResource(java.lang.String)}
	 */
	private final Resource resource;

	@Nullable
	private String beanName;

	/**
	 * 标注{@link Import}注解的原始类
	 * 如果该{@link ConfigurationClass} 不是通过{@link Import}加载的，则为空
	 */
	private final Set<ConfigurationClass> importedBy = new LinkedHashSet<>(1);

	/**
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 * {@link ConfigurationClassParser#processInterfaces(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass)}
	 * 中调用
	 */
	private final Set<BeanMethod> beanMethods = new LinkedHashSet<>();

	/**
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 */
	private final Map<String, Class<? extends BeanDefinitionReader>> importedResources =
			new LinkedHashMap<>();

	/**
	 * {@link ConfigurationClassParser#processImports(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.Collection, java.util.function.Predicate, boolean)}
	 * 中调用
	 */
	private final Map<ImportBeanDefinitionRegistrar, AnnotationMetadata> importBeanDefinitionRegistrars =
			new LinkedHashMap<>();

	final Set<String> skippedBeanMethods = new HashSet<>();


	/**
	 * Create a new {@link ConfigurationClass} with the given name.
	 *
	 * @param metadataReader reader used to parse the underlying {@link Class}
	 * @param beanName       must not be {@code null}
	 * @see ConfigurationClass#ConfigurationClass(Class, ConfigurationClass)
	 */
	ConfigurationClass(MetadataReader metadataReader, String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		this.metadata = metadataReader.getAnnotationMetadata();
		this.resource = metadataReader.getResource();
		this.beanName = beanName;
	}

	/**
	 * Create a new {@link ConfigurationClass} representing a class that was imported
	 * using the {@link Import} annotation or automatically processed as a nested
	 * configuration class (if importedBy is not {@code null}).
	 *
	 * @param metadataReader reader used to parse the underlying {@link Class}
	 * @param importedBy     the configuration class importing this one or {@code null}
	 * @since 3.1.1
	 */
	ConfigurationClass(MetadataReader metadataReader, @Nullable ConfigurationClass importedBy) {
		this.metadata = metadataReader.getAnnotationMetadata();
		this.resource = metadataReader.getResource();
		this.importedBy.add(importedBy);
	}

	/**
	 * Create a new {@link ConfigurationClass} with the given name.
	 *
	 * @param clazz    the underlying {@link Class} to represent
	 * @param beanName name of the {@code @Configuration} class bean
	 * @see ConfigurationClass#ConfigurationClass(Class, ConfigurationClass)
	 */
	ConfigurationClass(Class<?> clazz, String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		this.metadata = AnnotationMetadata.introspect(clazz);
		this.resource = new DescriptiveResource(clazz.getName());
		this.beanName = beanName;
	}

	/**
	 * Create a new {@link ConfigurationClass} representing a class that was imported
	 * using the {@link Import} annotation or automatically processed as a nested
	 * configuration class (if imported is {@code true}).
	 * <p>
	 * {@link ConfigurationClassParser.SourceClass#asConfigClass(org.springframework.context.annotation.ConfigurationClass)}
	 * 中调用
	 * </p>
	 *
	 * @param clazz      the underlying {@link Class} to represent
	 * @param importedBy the configuration class importing this one (or {@code null})
	 * @since 3.1.1
	 */
	ConfigurationClass(Class<?> clazz, @Nullable ConfigurationClass importedBy) {
		this.metadata = AnnotationMetadata.introspect(clazz);
		this.resource = new DescriptiveResource(clazz.getName());
		this.importedBy.add(importedBy);
	}

	/**
	 * Create a new {@link ConfigurationClass} with the given name.
	 * <p>
	 * {@link ConfigurationClassParser#parse(org.springframework.core.type.AnnotationMetadata, java.lang.String)}
	 * 中调用
	 * </p>
	 *
	 * @param metadata the metadata for the underlying class to represent
	 *                 {@link AnnotatedBeanDefinition#getMetadata()}
	 * @param beanName name of the {@code @Configuration} class bean
	 *                 bean的名字
	 * @see ConfigurationClass#ConfigurationClass(Class, ConfigurationClass)
	 */
	ConfigurationClass(AnnotationMetadata metadata, String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		this.metadata = metadata;
		this.resource = new DescriptiveResource(metadata.getClassName());
		this.beanName = beanName;
	}


	AnnotationMetadata getMetadata() {
		return this.metadata;
	}

	Resource getResource() {
		return this.resource;
	}

	String getSimpleName() {
		return ClassUtils.getShortName(getMetadata().getClassName());
	}

	void setBeanName(String beanName) {
		this.beanName = beanName;
	}

	@Nullable
	public String getBeanName() {
		return this.beanName;
	}

	/**
	 * Return whether this configuration class was registered via @{@link Import} or
	 * automatically registered due to being nested within another configuration class.
	 *
	 * @see #getImportedBy()
	 * @since 3.1.1
	 */
	public boolean isImported() {
		return !this.importedBy.isEmpty();
	}

	/**
	 * Merge the imported-by declarations from the given configuration class into this one.
	 *
	 * @since 4.0.5
	 */
	void mergeImportedBy(ConfigurationClass otherConfigClass) {
		this.importedBy.addAll(otherConfigClass.importedBy);
	}

	/**
	 * Return the configuration classes that imported this class,
	 * or an empty Set if this configuration was not imported.
	 *
	 * @see #isImported()
	 * @since 4.0.5
	 */
	Set<ConfigurationClass> getImportedBy() {
		return this.importedBy;
	}

	/**
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 * {@link ConfigurationClassParser#processInterfaces(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass)}
	 * 中调用
	 *
	 * @param method
	 */
	void addBeanMethod(BeanMethod method) {
		this.beanMethods.add(method);
	}

	Set<BeanMethod> getBeanMethods() {
		return this.beanMethods;
	}

	/**
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 *
	 * @param importedResource
	 * @param readerClass
	 */
	void addImportedResource(String importedResource, Class<? extends BeanDefinitionReader> readerClass) {
		this.importedResources.put(importedResource, readerClass);
	}

	/**
	 * {@link ConfigurationClassParser#processImports(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.Collection, java.util.function.Predicate, boolean)}
	 * 中调用
	 *
	 * @param registrar
	 * @param importingClassMetadata
	 */
	void addImportBeanDefinitionRegistrar(ImportBeanDefinitionRegistrar registrar, AnnotationMetadata importingClassMetadata) {
		this.importBeanDefinitionRegistrars.put(registrar, importingClassMetadata);
	}

	Map<ImportBeanDefinitionRegistrar, AnnotationMetadata> getImportBeanDefinitionRegistrars() {
		return this.importBeanDefinitionRegistrars;
	}

	Map<String, Class<? extends BeanDefinitionReader>> getImportedResources() {
		return this.importedResources;
	}

	void validate(ProblemReporter problemReporter) {
		// A configuration class may not be final (CGLIB limitation) unless it declares proxyBeanMethods=false
		Map<String, Object> attributes = this.metadata.getAnnotationAttributes(Configuration.class.getName());
		if (attributes != null && (Boolean) attributes.get("proxyBeanMethods")) {
			if (this.metadata.isFinal()) {
				problemReporter.error(new FinalConfigurationProblem());
			}
			for (BeanMethod beanMethod : this.beanMethods) {
				beanMethod.validate(problemReporter);
			}
		}
	}

	/**
	 * 重写了equals
	 * 也就是说两个{@link ConfigurationClass}表示的className一样，即equals
	 *
	 * @param other
	 * @return
	 */
	@Override
	public boolean equals(@Nullable Object other) {
		return (this == other || (other instanceof ConfigurationClass &&
				getMetadata().getClassName().equals(((ConfigurationClass) other).getMetadata().getClassName())));
	}

	/**
	 * 重写了hashCode,
	 * 也就是说两个{@link ConfigurationClass}表示的className一样，即一样
	 *
	 * @return
	 */
	@Override
	public int hashCode() {
		return getMetadata().getClassName().hashCode();
	}

	@Override
	public String toString() {
		return "ConfigurationClass: beanName '" + this.beanName + "', " + this.resource;
	}


	/**
	 * Configuration classes must be non-final to accommodate CGLIB subclassing.
	 */
	private class FinalConfigurationProblem extends Problem {

		FinalConfigurationProblem() {
			super(String.format("@Configuration class '%s' may not be final. Remove the final modifier to continue.",
					getSimpleName()), new Location(getResource(), getMetadata()));
		}
	}

}
