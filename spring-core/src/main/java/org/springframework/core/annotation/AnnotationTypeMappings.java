/*
 * Copyright 2002-2022 the original author or authors.
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
import java.lang.annotation.Repeatable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * Provides {@link AnnotationTypeMapping} information for a single source
 * annotation type. Performs a recursive breadth first crawl of all
 * meta-annotations to ultimately provide a quick way to map the attributes of
 * a root {@link Annotation}.
 *
 * <p>Supports convention based merging of meta-annotations as well as implicit
 * and explicit {@link AliasFor @AliasFor} aliases. Also provides information
 * about mirrored attributes.
 *
 * <p>This class is designed to be cached so that meta-annotations only need to
 * be searched once, regardless of how many times they are actually used.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @see AnnotationTypeMapping
 * @since 5.2
 */
final class AnnotationTypeMappings {

	private static final IntrospectionFailureLogger failureLogger = IntrospectionFailureLogger.DEBUG;

	private static final Map<AnnotationFilter, Cache> standardRepeatablesCache = new ConcurrentReferenceHashMap<>();

	private static final Map<AnnotationFilter, Cache> noRepeatablesCache = new ConcurrentReferenceHashMap<>();

	/**
	 * 重复访问容器
	 */
	private final RepeatableContainers repeatableContainers;

	/**
	 * 过滤器
	 */
	private final AnnotationFilter filter;

	/**
	 * 记录该annotationType对应的AnnotationTypeMapping列表
	 */
	private final List<AnnotationTypeMapping> mappings;


	/**
	 * {@link Cache#createMappings(java.lang.Class, java.util.Set)}中调用
	 * <p>
	 * 为annotationType创建对应的{@link AnnotationTypeMappings}
	 * </p>
	 *
	 * @param repeatableContainers   {@link RepeatableContainers#standardRepeatables()}
	 * @param filter                 {@link AnnotationFilter#PLAIN}
	 * @param annotationType         注解的class类对象
	 * @param visitedAnnotationTypes 这个外面接口传入了一个空的Set
	 */
	private AnnotationTypeMappings(RepeatableContainers repeatableContainers,
								   AnnotationFilter filter, Class<? extends Annotation> annotationType,
								   Set<Class<? extends Annotation>> visitedAnnotationTypes) {

		this.repeatableContainers = repeatableContainers;
		this.filter = filter;
		this.mappings = new ArrayList<>();
		addAllMappings(annotationType, visitedAnnotationTypes);
		this.mappings.forEach(AnnotationTypeMapping::afterAllMappingsSet);
	}


	/**
	 * {@link AnnotationTypeMappings#AnnotationTypeMappings(org.springframework.core.annotation.RepeatableContainers, org.springframework.core.annotation.AnnotationFilter, java.lang.Class, java.util.Set)}
	 * 中调用
	 *
	 * <p>
	 * 为传入的annotationType,初始化{@link AnnotationTypeMappings#mappings}
	 * 该mappings保存annotationType上的注解，以及注解上的递归注解
	 * </p>
	 *
	 * @param annotationType         注解的class类
	 * @param visitedAnnotationTypes
	 */
	private void addAllMappings(Class<? extends Annotation> annotationType,
								Set<Class<? extends Annotation>> visitedAnnotationTypes) {
		/**
		 * 初始化一个双端队列
		 */
		Deque<AnnotationTypeMapping> queue = new ArrayDeque<>();
		addIfPossible(queue, null, annotationType, null, visitedAnnotationTypes);
		while (!queue.isEmpty()) {
			// 添加的时候，是从后面添加，取的时候是从前面取
			AnnotationTypeMapping mapping = queue.removeFirst();
			// 把传入的annotationType上的注解递归加入到mappings中
			this.mappings.add(mapping);
			addMetaAnnotationsToQueue(queue, mapping);
		}
	}

	/**
	 * <p>
	 * {@link AnnotationTypeMappings#addAllMappings(java.lang.Class, java.util.Set)}
	 * 中调用
	 * </p>
	 * 添加source上的注解到queue。这个是一个递归过程，一直向下找
	 * 注:spring的MetaAnnotation，即元注解，可以理解为可以用在其它注解上的注解
	 *
	 * @param queue
	 * @param source
	 */
	private void addMetaAnnotationsToQueue(Deque<AnnotationTypeMapping> queue, AnnotationTypeMapping source) {
		/**
		 * {@link AnnotationTypeMapping#getAnnotationType()}上的所有注解
		 * 获取该注解上面的那些注解数组
		 */
		Annotation[] metaAnnotations = AnnotationsScanner.getDeclaredAnnotations(source.getAnnotationType(), false);
		for (Annotation metaAnnotation : metaAnnotations) {
			if (!isMappable(source, metaAnnotation)) {
				// 已经加载过,不需要再加载
				continue;
			}
			/**
			 * https://blog.csdn.net/a232884c/article/details/124827155
			 * 获取有注解{@link Repeatable}的方法返回注解数组
			 */
			Annotation[] repeatedAnnotations = this.repeatableContainers.findRepeatedAnnotations(metaAnnotation);
			if (repeatedAnnotations != null) {
				for (Annotation repeatedAnnotation : repeatedAnnotations) {
					if (!isMappable(source, repeatedAnnotation)) {
						continue;
					}
					addIfPossible(queue, source, repeatedAnnotation);
				}
			} else {
				addIfPossible(queue, source, metaAnnotation);
			}
		}
	}

	/**
	 * {@link AnnotationTypeMappings#addMetaAnnotationsToQueue(java.util.Deque, org.springframework.core.annotation.AnnotationTypeMapping)}
	 * 中调用
	 *
	 * @param queue  队列
	 * @param source 父AnnotationTypeMapping
	 * @param ann    {@link AnnotationTypeMapping#annotationType}上的注解
	 */
	private void addIfPossible(Deque<AnnotationTypeMapping> queue, AnnotationTypeMapping source, Annotation ann) {
		addIfPossible(queue, source, ann.annotationType(), ann, new HashSet<>());
	}

	/**
	 * {@link AnnotationTypeMappings#addAllMappings(java.lang.Class, java.util.Set)}
	 * 中调用
	 * {@link AnnotationTypeMappings#addIfPossible(java.util.Deque, org.springframework.core.annotation.AnnotationTypeMapping, java.lang.annotation.Annotation)}
	 * 中调用
	 * <p>
	 * 向双端队列queue中添加一个{@link AnnotationTypeMapping}
	 * </p>
	 *
	 * @param queue                  双端队列
	 * @param source                 原始的class,此处是null
	 * @param annotationType         source上的所有注解中的其中一个
	 * @param ann                    此处传入的null
	 * @param visitedAnnotationTypes
	 */
	private void addIfPossible(Deque<AnnotationTypeMapping> queue, @Nullable AnnotationTypeMapping source,
							   Class<? extends Annotation> annotationType, @Nullable Annotation ann,
							   Set<Class<? extends Annotation>> visitedAnnotationTypes) {

		try {
			queue.addLast(new AnnotationTypeMapping(source, annotationType, ann, visitedAnnotationTypes));
		} catch (Exception ex) {
			AnnotationUtils.rethrowAnnotationConfigurationException(ex);
			if (failureLogger.isEnabled()) {
				failureLogger.log("Failed to introspect meta-annotation " + annotationType.getName(),
						(source != null ? source.getAnnotationType() : null), ex);
			}
		}
	}

	/**
	 * {@link AnnotationTypeMappings#addMetaAnnotationsToQueue(java.util.Deque, org.springframework.core.annotation.AnnotationTypeMapping)}
	 * 中被调用
	 * <p>
	 * 判断metaAnnotation是否需要加载，加载过返回false
	 * </p>
	 *
	 * @param source         父注解
	 * @param metaAnnotation 子注解
	 * @return
	 */
	private boolean isMappable(AnnotationTypeMapping source, @Nullable Annotation metaAnnotation) {
		return (metaAnnotation != null && !this.filter.matches(metaAnnotation) &&
				!AnnotationFilter.PLAIN.matches(source.getAnnotationType()) &&
				!isAlreadyMapped(source, metaAnnotation));
	}

	/**
	 * {@link AnnotationTypeMappings#isMappable(org.springframework.core.annotation.AnnotationTypeMapping, java.lang.annotation.Annotation)}
	 * 中被调用
	 *
	 * <p>
	 * 判断传入的metaAnnotation是否已经加载了
	 * </p>
	 *
	 * @param source
	 * @param metaAnnotation
	 * @return
	 */
	private boolean isAlreadyMapped(AnnotationTypeMapping source, Annotation metaAnnotation) {
		Class<? extends Annotation> annotationType = metaAnnotation.annotationType();
		AnnotationTypeMapping mapping = source;
		while (mapping != null) {
			if (mapping.getAnnotationType() == annotationType) {
				return true;
			}
			// 一直向上找
			mapping = mapping.getSource();
		}
		return false;
	}

	/**
	 * Get the total number of contained mappings.
	 *
	 * @return the total number of mappings
	 */
	int size() {
		return this.mappings.size();
	}

	/**
	 * Get an individual mapping from this instance.
	 * <p>Index {@code 0} will always return the root mapping; higher indexes
	 * will return meta-annotation mappings.
	 *
	 * @param index the index to return
	 * @return the {@link AnnotationTypeMapping}
	 * @throws IndexOutOfBoundsException if the index is out of range
	 *                                   ({@code index < 0 || index >= size()})
	 */
	AnnotationTypeMapping get(int index) {
		return this.mappings.get(index);
	}


	/**
	 * <p>
	 * 根据传入的{@link Annotation#annotationType()},去缓存获取对应的{@link AnnotationTypeMappings}
	 * </p>
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 * <p>
	 * {@link TypeMappedAnnotations.Aggregate#Aggregate(int, java.lang.Object, java.util.List)}
	 * 中被调用
	 * </p>
	 *
	 * @param annotationType 注解的class类,{@link Annotation#annotationType()}
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType) {
		return forAnnotationType(annotationType, new HashSet<>());
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * <p>
	 * {@link AnnotationTypeMappings#forAnnotationType(java.lang.Class)}
	 * 中被调用
	 * </p>
	 *
	 * @param annotationType         the source annotation type
	 * @param visitedAnnotationTypes the set of annotations that we have already
	 *                               visited; used to avoid infinite recursion for recursive annotations which
	 *                               some JVM languages support (such as Kotlin)
	 *                               访问过的annotationType。这个注解上还有注解
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType,
													Set<Class<? extends Annotation>> visitedAnnotationTypes) {

		/**
		 * 默认使用的是{@link RepeatableContainers#standardRepeatables()}作为缓存
		 * 默认使用的过滤器是{@link AnnotationFilter#PLAIN}
		 */
		return forAnnotationType(annotationType, RepeatableContainers.standardRepeatables(),
				AnnotationFilter.PLAIN, visitedAnnotationTypes);
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * @param annotationType       the source annotation type
	 * @param repeatableContainers the repeatable containers that may be used by
	 *                             the meta-annotations
	 * @param annotationFilter     the annotation filter used to limit which
	 *                             annotations are considered
	 * @return type mappings for the annotation type
	 */
	static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType,
													RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		return forAnnotationType(annotationType, repeatableContainers, annotationFilter, new HashSet<>());
	}

	/**
	 * Create {@link AnnotationTypeMappings} for the specified annotation type.
	 *
	 * <p>
	 * {@link AnnotationTypeMappings#forAnnotationType(java.lang.Class, java.util.Set)}
	 * 中被调用
	 * </p>
	 *
	 * @param annotationType         注解的class类
	 * @param repeatableContainers   the repeatable containers that may be used by
	 *                               the meta-annotations
	 * @param annotationFilter       注解的过滤器
	 * @param visitedAnnotationTypes the set of annotations that we have already
	 *                               visited; used to avoid infinite recursion for recursive annotations which
	 *                               some JVM languages support (such as Kotlin)
	 * @return type mappings for the annotation type
	 */
	private static AnnotationTypeMappings forAnnotationType(Class<? extends Annotation> annotationType,
															RepeatableContainers repeatableContainers,
															AnnotationFilter annotationFilter,
															Set<Class<? extends Annotation>> visitedAnnotationTypes) {

		if (repeatableContainers == RepeatableContainers.standardRepeatables()) {
			/**
			 * key:annotationFilter
			 * value:如果key存在,则返回key对应的value。否则new Cache(repeatableContainers, key)
			 * 返回的是Cache的get方法
			 * <p>
			 * 第一层缓存用的是{@link AnnotationFilter}。缓存的值为{@link Cache}
			 * 作为缓存的{@link Cache}对象，由作为key的{@link AnnotationFilter}和{@link RepeatableContainers}中初始化
			 * </p>
			 */
			return standardRepeatablesCache.computeIfAbsent(annotationFilter,
					key -> new Cache(repeatableContainers, key)).get(annotationType, visitedAnnotationTypes);
		}
		if (repeatableContainers == RepeatableContainers.none()) {
			return noRepeatablesCache.computeIfAbsent(annotationFilter,
					key -> new Cache(repeatableContainers, key)).get(annotationType, visitedAnnotationTypes);
		}
		return new AnnotationTypeMappings(repeatableContainers, annotationFilter, annotationType, visitedAnnotationTypes);
	}

	static void clearCache() {
		standardRepeatablesCache.clear();
		noRepeatablesCache.clear();
	}


	/**
	 * Cache created per {@link AnnotationFilter}.
	 */
	private static class Cache {

		/**
		 * 使用的缓存类型
		 */
		private final RepeatableContainers repeatableContainers;

		/**
		 * 该缓存的key，即一个{@link AnnotationFilter}。因为{@link AnnotationFilter}可以看成是一种枚举，因此
		 * 可以把该{@link Cache}也看成枚举
		 */
		private final AnnotationFilter filter;

		private final Map<Class<? extends Annotation>, AnnotationTypeMappings> mappings;

		/**
		 * 缓存。该缓存缓存的是{@link AnnotationFilter}。可以把{@link AnnotationFilter}看成一个枚举。
		 * 因此一个{@link Cache}实例，可以认为对应一种类型的{@link AnnotationFilter}
		 * <p>
		 * 同时初始化参数中，指定了用的缓存的类型{@link RepeatableContainers}
		 * </p>
		 * <p>
		 * Create a cache instance with the specified filter.
		 * <p>
		 * {@link AnnotationTypeMappings#forAnnotationType(java.lang.Class, org.springframework.core.annotation.RepeatableContainers, org.springframework.core.annotation.AnnotationFilter, java.util.Set)}
		 * 中调用
		 * </p>
		 *
		 * @param filter the annotation filter
		 */
		Cache(RepeatableContainers repeatableContainers, AnnotationFilter filter) {
			this.repeatableContainers = repeatableContainers;
			this.filter = filter;
			this.mappings = new ConcurrentReferenceHashMap<>();
		}

		/**
		 * <p>
		 * 从缓存中根据传入的annotationType获取{@link AnnotationTypeMappings}
		 * </p>
		 * Get or create {@link AnnotationTypeMappings} for the specified annotation type.
		 * <p>
		 * {@link AnnotationTypeMappings#forAnnotationType(java.lang.Class, org.springframework.core.annotation.RepeatableContainers, org.springframework.core.annotation.AnnotationFilter, java.util.Set)}
		 * 中被调用
		 * </p>
		 *
		 * @param annotationType         the annotation type
		 * @param visitedAnnotationTypes the set of annotations that we have already
		 *                               visited; used to avoid infinite recursion for recursive annotations which
		 *                               some JVM languages support (such as Kotlin)
		 *                               已经访问过的annotationType。防止重复加载
		 * @return a new or existing {@link AnnotationTypeMappings} instance
		 */
		AnnotationTypeMappings get(Class<? extends Annotation> annotationType,
								   Set<Class<? extends Annotation>> visitedAnnotationTypes) {

			/**
			 * 如果存在以annotationType为key的value {@link AnnotationTypeMappings},则返回
			 * 否则就调用{@link Cache#createMappings(java.lang.Class, java.util.Set)}创建一个
			 */
			return this.mappings.computeIfAbsent(annotationType, key -> createMappings(key, visitedAnnotationTypes));
		}

		/**
		 * <p>
		 * 根据传入的annotationType,创建一个{@link AnnotationTypeMappings}
		 * </p>
		 * {@link Cache#get(java.lang.Class, java.util.Set)}中调用
		 *
		 * @param annotationType
		 * @param visitedAnnotationTypes
		 * @return
		 */
		private AnnotationTypeMappings createMappings(Class<? extends Annotation> annotationType,
													  Set<Class<? extends Annotation>> visitedAnnotationTypes) {

			return new AnnotationTypeMappings(this.repeatableContainers, this.filter, annotationType, visitedAnnotationTypes);
		}
	}

}
