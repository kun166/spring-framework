/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.beans.factory.support;

import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.lang.Nullable;

/**
 * {@link AutowireCandidateResolver} implementation to use when no annotation
 * support is available. This implementation checks the bean definition only.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @since 2.5
 */
public class SimpleAutowireCandidateResolver implements AutowireCandidateResolver {

	/**
	 * Shared instance of {@code SimpleAutowireCandidateResolver}.
	 *
	 * @since 5.2.7
	 */
	public static final SimpleAutowireCandidateResolver INSTANCE = new SimpleAutowireCandidateResolver();


	/**
	 * <p>
	 * {@link DefaultListableBeanFactory#isAutowireCandidate(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, org.springframework.beans.factory.config.DependencyDescriptor, org.springframework.beans.factory.support.AutowireCandidateResolver)}
	 * 中调用
	 * {@link GenericTypeAwareAutowireCandidateResolver#isAutowireCandidate(org.springframework.beans.factory.config.BeanDefinitionHolder, org.springframework.beans.factory.config.DependencyDescriptor)}
	 * 中调用
	 * </p>
	 *
	 * @param bdHolder   the bean definition including bean name and aliases
	 * @param descriptor the descriptor for the target method parameter or field
	 * @return
	 */
	@Override
	public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		/**
		 * {@link AbstractBeanDefinition#autowireCandidate},默认是true
		 */
		return bdHolder.getBeanDefinition().isAutowireCandidate();
	}

	@Override
	public boolean isRequired(DependencyDescriptor descriptor) {
		return descriptor.isRequired();
	}

	@Override
	public boolean hasQualifier(DependencyDescriptor descriptor) {
		return false;
	}

	/**
	 * <p>
	 * {@link DefaultListableBeanFactory#doResolveDependency(org.springframework.beans.factory.config.DependencyDescriptor, java.lang.String, java.util.Set, org.springframework.beans.TypeConverter)}
	 * 中调用
	 * </p>
	 *
	 * @param descriptor the descriptor for the target method parameter or field
	 * @return
	 */
	@Override
	@Nullable
	public Object getSuggestedValue(DependencyDescriptor descriptor) {
		return null;
	}

	/**
	 * <p>
	 * {@link DefaultListableBeanFactory#resolveDependency(org.springframework.beans.factory.config.DependencyDescriptor, java.lang.String, java.util.Set, org.springframework.beans.TypeConverter)}
	 * 中调用
	 * </p>
	 *
	 * @param descriptor the descriptor for the target method parameter or field
	 * @param beanName   the name of the bean that contains the injection point
	 * @return
	 */
	@Override
	@Nullable
	public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, @Nullable String beanName) {
		return null;
	}

	/**
	 * This implementation returns {@code this} as-is.
	 *
	 * @see #INSTANCE
	 */
	@Override
	public AutowireCandidateResolver cloneIfNecessary() {
		return this;
	}

}
