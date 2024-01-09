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

package org.springframework.core.type;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.annotation.*;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.lang.Nullable;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ReflectionUtils;

/**
 * {@link AnnotationMetadata} implementation that uses standard reflection
 * to introspect a given {@link Class}.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Chris Beams
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 2.5
 */
public class StandardAnnotationMetadata extends StandardClassMetadata implements AnnotationMetadata {

	/**
	 * {@link TypeMappedAnnotations}
	 */
	private final MergedAnnotations mergedAnnotations;

	/**
	 * {@link StandardAnnotationMetadata#StandardAnnotationMetadata(java.lang.Class, boolean)}
	 * 中被调用,传入true
	 */
	private final boolean nestedAnnotationsAsMap;

	/**
	 * {@link StandardAnnotationMetadata#getAnnotationTypes()}中赋值
	 */
	@Nullable
	private Set<String> annotationTypes;


	/**
	 * Create a new {@code StandardAnnotationMetadata} wrapper for the given Class.
	 *
	 * @param introspectedClass the Class to introspect
	 * @see #StandardAnnotationMetadata(Class, boolean)
	 * @deprecated since 5.2 in favor of the factory method {@link AnnotationMetadata#introspect(Class)}
	 */
	@Deprecated
	public StandardAnnotationMetadata(Class<?> introspectedClass) {
		this(introspectedClass, false);
	}

	/**
	 * Create a new {@link StandardAnnotationMetadata} wrapper for the given Class,
	 * providing the option to return any nested annotations or annotation arrays in the
	 * form of {@link org.springframework.core.annotation.AnnotationAttributes} instead
	 * of actual {@link Annotation} instances.
	 * <p>
	 * {@link StandardAnnotationMetadata#from(java.lang.Class)}
	 * 中被调用
	 * </p>
	 *
	 * @param introspectedClass      the Class to introspect
	 * @param nestedAnnotationsAsMap return nested annotations and annotation arrays as
	 *                               {@link org.springframework.core.annotation.AnnotationAttributes} for compatibility
	 *                               with ASM-based {@link AnnotationMetadata} implementations
	 *                               镶套的注解
	 * @since 3.1.1
	 * @deprecated since 5.2 in favor of the factory method {@link AnnotationMetadata#introspect(Class)}.
	 * Use {@link MergedAnnotation#asMap(org.springframework.core.annotation.MergedAnnotation.Adapt...) MergedAnnotation.asMap}
	 * from {@link #getAnnotations()} rather than {@link #getAnnotationAttributes(String)}
	 * if {@code nestedAnnotationsAsMap} is {@code false}
	 */
	@Deprecated
	public StandardAnnotationMetadata(Class<?> introspectedClass, boolean nestedAnnotationsAsMap) {
		super(introspectedClass);
		/**
		 * 关于{@link AnnotatedElement},
		 * 可以参考:https://blog.csdn.net/xichenguan/article/details/87856550
		 * 1,{@link org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition}
		 * 持有一个{@link StandardAnnotationMetadata}
		 * 2,{@link StandardAnnotationMetadata}又持有一个{@link MergedAnnotations}
		 *
		 * 字面意思吧：
		 * 注解Bean定义 持有一个 标准注解元数据
		 * 标准注解元数据 又持有一个 合并注解
		 */
		this.mergedAnnotations = MergedAnnotations.from(introspectedClass,
				SearchStrategy.INHERITED_ANNOTATIONS, RepeatableContainers.none());
		// 镶套的注解as map?
		this.nestedAnnotationsAsMap = nestedAnnotationsAsMap;
	}


	/**
	 * {@link AnnotatedTypeMetadata#getAnnotationAttributes(java.lang.String, boolean)}
	 * 中被调用
	 *
	 * @return
	 */
	@Override
	public MergedAnnotations getAnnotations() {
		return this.mergedAnnotations;
	}

	/**
	 * {@link org.springframework.context.annotation.AnnotationBeanNameGenerator#determineBeanNameFromAnnotation(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition)}
	 * 中被调用
	 *
	 * @return
	 */
	@Override
	public Set<String> getAnnotationTypes() {
		// 做了一个缓存
		Set<String> annotationTypes = this.annotationTypes;
		if (annotationTypes == null) {
			// 如果没有值的话，就调用父类的该方法
			annotationTypes = Collections.unmodifiableSet(AnnotationMetadata.super.getAnnotationTypes());
			this.annotationTypes = annotationTypes;
		}
		return annotationTypes;
	}

	@Override
	@Nullable
	public Map<String, Object> getAnnotationAttributes(String annotationName, boolean classValuesAsString) {
		if (this.nestedAnnotationsAsMap) {
			return AnnotationMetadata.super.getAnnotationAttributes(annotationName, classValuesAsString);
		}
		return AnnotatedElementUtils.getMergedAnnotationAttributes(
				getIntrospectedClass(), annotationName, classValuesAsString, false);
	}

	/**
	 * {@link org.springframework.context.annotation.ConditionEvaluator#getConditionClasses(org.springframework.core.type.AnnotatedTypeMetadata)}
	 * 中被调用
	 *
	 * @param annotationName      the fully qualified class name of the annotation
	 *                            type to look for
	 * @param classValuesAsString whether to convert class references to String
	 * @return
	 */
	@Override
	@Nullable
	public MultiValueMap<String, Object> getAllAnnotationAttributes(String annotationName, boolean classValuesAsString) {
		if (this.nestedAnnotationsAsMap) {
			return AnnotationMetadata.super.getAllAnnotationAttributes(annotationName, classValuesAsString);
		}
		return AnnotatedElementUtils.getAllAnnotationAttributes(
				getIntrospectedClass(), annotationName, classValuesAsString, false);
	}

	/**
	 * {@link org.springframework.context.annotation.ConfigurationClassUtils#hasBeanMethods(org.springframework.core.type.AnnotationMetadata)}
	 * 中调用
	 *
	 * @param annotationName the fully qualified class name of the annotation
	 * @return
	 */
	@Override
	public boolean hasAnnotatedMethods(String annotationName) {
		if (AnnotationUtils.isCandidateClass(getIntrospectedClass(), annotationName)) {
			try {
				Method[] methods = ReflectionUtils.getDeclaredMethods(getIntrospectedClass());
				for (Method method : methods) {
					if (isAnnotatedMethod(method, annotationName)) {
						return true;
					}
				}
			} catch (Throwable ex) {
				throw new IllegalStateException("Failed to introspect annotated methods on " + getIntrospectedClass(), ex);
			}
		}
		return false;
	}

	@Override
	@SuppressWarnings("deprecation")
	public Set<MethodMetadata> getAnnotatedMethods(String annotationName) {
		Set<MethodMetadata> annotatedMethods = null;
		if (AnnotationUtils.isCandidateClass(getIntrospectedClass(), annotationName)) {
			try {
				Method[] methods = ReflectionUtils.getDeclaredMethods(getIntrospectedClass());
				for (Method method : methods) {
					if (isAnnotatedMethod(method, annotationName)) {
						if (annotatedMethods == null) {
							annotatedMethods = new LinkedHashSet<>(4);
						}
						annotatedMethods.add(new StandardMethodMetadata(method, this.nestedAnnotationsAsMap));
					}
				}
			} catch (Throwable ex) {
				throw new IllegalStateException("Failed to introspect annotated methods on " + getIntrospectedClass(), ex);
			}
		}
		return (annotatedMethods != null ? annotatedMethods : Collections.emptySet());
	}


	private static boolean isAnnotatedMethod(Method method, String annotationName) {
		return !method.isBridge() && method.getAnnotations().length > 0 &&
				AnnotatedElementUtils.isAnnotated(method, annotationName);
	}

	/**
	 * {@link AnnotationMetadata#introspect(java.lang.Class)}
	 * 中被调用
	 *
	 * @param introspectedClass
	 * @return
	 */
	static AnnotationMetadata from(Class<?> introspectedClass) {
		return new StandardAnnotationMetadata(introspectedClass, true);
	}

}
