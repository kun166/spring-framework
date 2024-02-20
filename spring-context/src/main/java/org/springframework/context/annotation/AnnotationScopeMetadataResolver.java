/*
 * Copyright 2002-2017 the original author or authors.
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

import java.lang.annotation.Annotation;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.util.Assert;

/**
 * A {@link ScopeMetadataResolver} implementation that by default checks for
 * the presence of Spring's {@link Scope @Scope} annotation on the bean class.
 *
 * <p>The exact type of annotation that is checked for is configurable via
 * {@link #setScopeAnnotationType(Class)}.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @see org.springframework.context.annotation.Scope
 * @since 2.5
 */
public class AnnotationScopeMetadataResolver implements ScopeMetadataResolver {

	private final ScopedProxyMode defaultProxyMode;

	protected Class<? extends Annotation> scopeAnnotationType = Scope.class;


	/**
	 * Construct a new {@code AnnotationScopeMetadataResolver}.
	 *
	 * @see #AnnotationScopeMetadataResolver(ScopedProxyMode)
	 * @see ScopedProxyMode#NO
	 */
	public AnnotationScopeMetadataResolver() {
		this.defaultProxyMode = ScopedProxyMode.NO;
	}

	/**
	 * Construct a new {@code AnnotationScopeMetadataResolver} using the
	 * supplied default {@link ScopedProxyMode}.
	 *
	 * @param defaultProxyMode the default scoped-proxy mode
	 */
	public AnnotationScopeMetadataResolver(ScopedProxyMode defaultProxyMode) {
		Assert.notNull(defaultProxyMode, "'defaultProxyMode' must not be null");
		this.defaultProxyMode = defaultProxyMode;
	}


	/**
	 * Set the type of annotation that is checked for by this
	 * {@code AnnotationScopeMetadataResolver}.
	 *
	 * @param scopeAnnotationType the target annotation type
	 */
	public void setScopeAnnotationType(Class<? extends Annotation> scopeAnnotationType) {
		Assert.notNull(scopeAnnotationType, "'scopeAnnotationType' must not be null");
		this.scopeAnnotationType = scopeAnnotationType;
	}


	/**
	 * {@link AnnotatedBeanDefinitionReader#doRegisterBean(java.lang.Class, java.lang.String, java.lang.Class[], java.util.function.Supplier, org.springframework.beans.factory.config.BeanDefinitionCustomizer[])}
	 * 中调用
	 * {@link ClassPathBeanDefinitionScanner#doScan(java.lang.String...)}中调用
	 *
	 * @param definition the target bean definition
	 * @return
	 */
	@Override
	public ScopeMetadata resolveScopeMetadata(BeanDefinition definition) {
		/**
		 * 生成一个默认的{@link ScopeMetadata}
		 * 默认的{@link ScopeMetadata#scopeName}为"singleton"
		 * 默认的{@link ScopeMetadata#scopedProxyMode}为{@link ScopedProxyMode#NO}
		 */
		ScopeMetadata metadata = new ScopeMetadata();
		if (definition instanceof AnnotatedBeanDefinition) {
			/**
			 * 如果扫描的bean中未标注{@link Scope},则使用默认的
			 */
			AnnotatedBeanDefinition annDef = (AnnotatedBeanDefinition) definition;
			AnnotationAttributes attributes = AnnotationConfigUtils.attributesFor(
					annDef.getMetadata(), this.scopeAnnotationType);
			if (attributes != null) {
				metadata.setScopeName(attributes.getString("value"));
				/**
				 * 关于ScopedProxyMode
				 * 可以参考:https://blog.csdn.net/m0_43448868/article/details/111643397
				 */
				ScopedProxyMode proxyMode = attributes.getEnum("proxyMode");
				if (proxyMode == ScopedProxyMode.DEFAULT) {
					proxyMode = this.defaultProxyMode;
				}
				metadata.setScopedProxyMode(proxyMode);
			}
		}
		return metadata;
	}

}
