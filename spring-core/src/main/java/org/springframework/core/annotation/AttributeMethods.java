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

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils;

/**
 * Provides a quick way to access the attribute methods of an {@link Annotation}
 * with consistent ordering as well as a few useful utility methods.
 * <p>
 * 对Class<? extends Annotation> annotationType进行解析,寻找出所有属性方法(无参且返回值不为void的方法),构建的对象。
 * 这个类的作用就是记录annotationType的属性方法
 * </p>
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 5.2
 */
final class AttributeMethods {

	/**
	 * 注解没有方法属性
	 */
	static final AttributeMethods NONE = new AttributeMethods(null, new Method[0]);

	/**
	 * 缓存,以注解为key，属性方法为value
	 */
	static final Map<Class<? extends Annotation>, AttributeMethods> cache = new ConcurrentReferenceHashMap<>();

	/**
	 * 方法比较器。
	 * 对于注解定义的属性方法(无参,且返回值不为void),铁定两个方法不可能名字一样。
	 * 下面的排序方法总结一下就是:
	 * 1,非属性方法排后面
	 * 2,属性方法按属性名排序
	 */
	private static final Comparator<Method> methodComparator = (m1, m2) -> {
		if (m1 != null && m2 != null) {
			/**
			 * 方法都不为空,比较两个方法的name
			 */
			return m1.getName().compareTo(m2.getName());
		}
		/**
		 * m1不为空,则m1排前面。否则m1排后面。
		 * 就是如果两者都为空的话，m1也排后面
		 */
		return m1 != null ? -1 : 1;
	};


	/**
	 * 注解的class类
	 */
	@Nullable
	private final Class<? extends Annotation> annotationType;

	/**
	 * 该注解的获取属性的方法,类似于pojo的get方法。参数为0且返回不为void
	 */
	private final Method[] attributeMethods;

	/**
	 * 感觉像是方法不抛异常？
	 */
	private final boolean[] canThrowTypeNotPresentException;

	/**
	 * 该注解方法的属性方法,至少含有一个默认值
	 */
	private final boolean hasDefaultValueMethod;

	/**
	 * 该注解方法的属性方法,至少含有一个返回值为注解的
	 */
	private final boolean hasNestedAnnotation;


	/**
	 * <p>
	 * {@link AttributeMethods#compute(java.lang.Class)}中调用
	 * <p>
	 * 私有构造函数。
	 * 感觉主要就是记录annotationType,以及该注解上的所有属性方法(无参,且返回值不为void)
	 *
	 * @param annotationType   注解的class
	 * @param attributeMethods annotationType上的属性方法(无参,且返回值不为void)。注意这个是根据属性名排好序有序数组
	 */
	private AttributeMethods(@Nullable Class<? extends Annotation> annotationType,
							 Method[] attributeMethods) {
		this.annotationType = annotationType;
		this.attributeMethods = attributeMethods;
		this.canThrowTypeNotPresentException = new boolean[attributeMethods.length];
		/**
		 * 所有的属性方法,只要有一个有默认值,就是true
		 */
		boolean foundDefaultValueMethod = false;
		// 镶套注解，即注解持有的方法里面，含有属性方法返回的对象是注解
		boolean foundNestedAnnotation = false;
		for (int i = 0; i < attributeMethods.length; i++) {
			/**
			 * 遍历该annotationType定义的所有属性方法
			 */
			Method method = this.attributeMethods[i];
			/**
			 * 方法返回类型
			 */
			Class<?> type = method.getReturnType();
			if (!foundDefaultValueMethod && (method.getDefaultValue() != null)) {
				/**
				 * {@link Method#getDefaultValue()}返回注解的默认值。
				 * 呃，好像这个方法，就是为了注解定义的属性方法用的。
				 */
				foundDefaultValueMethod = true;
			}
			/**
			 * getComponentType:返回数组中元素的Class对象，如果不是Class对象那么返回null
			 */
			if (!foundNestedAnnotation && (type.isAnnotation() || (type.isArray() && type.getComponentType().isAnnotation()))) {
				foundNestedAnnotation = true;
			}
			ReflectionUtils.makeAccessible(method);
			this.canThrowTypeNotPresentException[i] = (type == Class.class || type == Class[].class || type.isEnum());
		}
		this.hasDefaultValueMethod = foundDefaultValueMethod;
		this.hasNestedAnnotation = foundNestedAnnotation;
	}


	/**
	 * Determine if values from the given annotation can be safely accessed without
	 * causing any {@link TypeNotPresentException TypeNotPresentExceptions}.
	 * <p>
	 * {@link AnnotationsScanner#getDeclaredAnnotations(java.lang.reflect.AnnotatedElement, boolean)}
	 * 中被调用
	 * </p>
	 *
	 * @param annotation the annotation to check
	 * @return {@code true} if all values are present
	 * @see #validate(Annotation)
	 */
	boolean isValid(Annotation annotation) {
		assertAnnotation(annotation);
		for (int i = 0; i < size(); i++) {
			if (canThrowTypeNotPresentException(i)) {
				try {
					AnnotationUtils.invokeAnnotationMethod(get(i), annotation);
				} catch (Throwable ex) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Check if values from the given annotation can be safely accessed without causing
	 * any {@link TypeNotPresentException TypeNotPresentExceptions}. In particular,
	 * this method is designed to cover Google App Engine's late arrival of such
	 * exceptions for {@code Class} values (instead of the more typical early
	 * {@code Class.getAnnotations() failure}).
	 *
	 * @param annotation the annotation to validate
	 * @throws IllegalStateException if a declared {@code Class} attribute could not be read
	 * @see #isValid(Annotation)
	 */
	void validate(Annotation annotation) {
		assertAnnotation(annotation);
		for (int i = 0; i < size(); i++) {
			if (canThrowTypeNotPresentException(i)) {
				try {
					AnnotationUtils.invokeAnnotationMethod(get(i), annotation);
				} catch (Throwable ex) {
					throw new IllegalStateException("Could not obtain annotation attribute value for " + get(i).getName() + " declared on " + annotation.annotationType(), ex);
				}
			}
		}
	}

	/**
	 * {@link AttributeMethods#isValid(java.lang.annotation.Annotation)}
	 * 中被调用
	 *
	 * @param annotation
	 */
	private void assertAnnotation(Annotation annotation) {
		Assert.notNull(annotation, "Annotation must not be null");
		if (this.annotationType != null) {
			Assert.isInstanceOf(this.annotationType, annotation);
		}
	}

	/**
	 * Get the attribute with the specified name or {@code null} if no
	 * matching attribute exists.
	 *
	 * @param name the attribute name to find
	 * @return the attribute method or {@code null}
	 */
	@Nullable
	Method get(String name) {
		int index = indexOf(name);
		return index != -1 ? this.attributeMethods[index] : null;
	}

	/**
	 * Get the attribute at the specified index.
	 *
	 * @param index the index of the attribute to return
	 * @return the attribute method
	 * @throws IndexOutOfBoundsException if the index is out of range
	 *                                   ({@code index < 0 || index >= size()})
	 */
	Method get(int index) {
		return this.attributeMethods[index];
	}

	/**
	 * Determine if the attribute at the specified index could throw a
	 * {@link TypeNotPresentException} when accessed.
	 * <p>
	 * {@link AttributeMethods#isValid(java.lang.annotation.Annotation)}
	 * 中被调用
	 * </p>
	 *
	 * @param index the index of the attribute to check
	 * @return {@code true} if the attribute can throw a
	 * {@link TypeNotPresentException}
	 */
	boolean canThrowTypeNotPresentException(int index) {
		return this.canThrowTypeNotPresentException[index];
	}

	/**
	 * Get the index of the attribute with the specified name, or {@code -1}
	 * if there is no attribute with the name.
	 *
	 * @param name the name to find
	 * @return the index of the attribute, or {@code -1}
	 */
	int indexOf(String name) {
		for (int i = 0; i < this.attributeMethods.length; i++) {
			if (this.attributeMethods[i].getName().equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Get the index of the specified attribute, or {@code -1} if the
	 * attribute is not in this collection.
	 *
	 * @param attribute the attribute to find
	 * @return the index of the attribute, or {@code -1}
	 */
	int indexOf(Method attribute) {
		for (int i = 0; i < this.attributeMethods.length; i++) {
			if (this.attributeMethods[i].equals(attribute)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Get the number of attributes in this collection.
	 *
	 * @return the number of attributes
	 */
	int size() {
		return this.attributeMethods.length;
	}

	/**
	 * Determine if at least one of the attribute methods has a default value.
	 *
	 * @return {@code true} if there is at least one attribute method with a default value
	 */
	boolean hasDefaultValueMethod() {
		return this.hasDefaultValueMethod;
	}

	/**
	 * Determine if at least one of the attribute methods is a nested annotation.
	 *
	 * @return {@code true} if there is at least one attribute method with a nested
	 * annotation type
	 */
	boolean hasNestedAnnotation() {
		return this.hasNestedAnnotation;
	}


	/**
	 * <p>
	 * {@link AnnotationsScanner#getDeclaredAnnotations(java.lang.reflect.AnnotatedElement, boolean)}
	 * 中调用
	 * {@link RepeatableContainers.StandardRepeatableContainers#computeRepeatedAnnotationsMethod(java.lang.Class)}
	 * 中调用
	 * </p>
	 * Get the attribute methods for the given annotation type.
	 * <p>
	 * 获取annotationType的声明方法(不包含继承的),选取参数长度为0,返回不为void的方法数组,和annotationType封装成
	 * {@link AttributeMethods}返回
	 * </p>
	 *
	 * @param annotationType 这个是注解的class
	 * @return the attribute methods for the annotation type
	 */
	static AttributeMethods forAnnotationType(@Nullable Class<? extends Annotation> annotationType) {
		if (annotationType == null) {
			return NONE;
		}
		// 对该注解及它持有的方法做了一个缓存
		return cache.computeIfAbsent(annotationType, AttributeMethods::compute);
	}

	/**
	 * {@link AttributeMethods#forAnnotationType(java.lang.Class)}
	 * 中调用
	 *
	 * @param annotationType
	 * @return
	 */
	private static AttributeMethods compute(Class<? extends Annotation> annotationType) {
		/**
		 * 取注解定义的所有方法
		 */
		Method[] methods = annotationType.getDeclaredMethods();
		int size = methods.length;
		for (int i = 0; i < methods.length; i++) {
			/**
			 * 遍历。过滤掉非属性方法
			 */
			if (!isAttributeMethod(methods[i])) {
				/**
				 * 该方法返回传入的参数:无参且返回不能是void
				 * 前面加的!,就是上述两者起码有一个不满足
				 */
				methods[i] = null;
				size--;
			}
		}
		if (size == 0) {
			return NONE;
		}
		// 根据方法名称排序
		Arrays.sort(methods, methodComparator);
		Method[] attributeMethods = Arrays.copyOf(methods, size);
		return new AttributeMethods(annotationType, attributeMethods);
	}

	/**
	 * 判断方法是否为获取属性方法。类似于pojo的get方法
	 * 即：方法参数为0,且方法返回类型不能为void
	 * {@link AttributeMethods#compute(java.lang.Class)}
	 * 中被调用
	 *
	 * @param method
	 * @return
	 */
	private static boolean isAttributeMethod(Method method) {
		return (method.getParameterCount() == 0 && method.getReturnType() != void.class);
	}

	/**
	 * Create a description for the given attribute method suitable to use in
	 * exception messages and logs.
	 *
	 * @param attribute the attribute to describe
	 * @return a description of the attribute
	 */
	static String describe(@Nullable Method attribute) {
		if (attribute == null) {
			return "(none)";
		}
		return describe(attribute.getDeclaringClass(), attribute.getName());
	}

	/**
	 * Create a description for the given attribute method suitable to use in
	 * exception messages and logs.
	 *
	 * @param annotationType the annotation type
	 * @param attributeName  the attribute name
	 * @return a description of the attribute
	 */
	static String describe(@Nullable Class<?> annotationType, @Nullable String attributeName) {
		if (attributeName == null) {
			return "(none)";
		}
		String in = (annotationType != null ? " in annotation [" + annotationType.getName() + "]" : "");
		return "attribute '" + attributeName + "'" + in;
	}

}
