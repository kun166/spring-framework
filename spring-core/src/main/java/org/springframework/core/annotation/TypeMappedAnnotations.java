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

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;

/**
 * {@link MergedAnnotations} implementation that searches for and adapts
 * annotations and meta-annotations using {@link AnnotationTypeMappings}.
 *
 * @author Phillip Webb
 * @since 5.2
 */
final class TypeMappedAnnotations implements MergedAnnotations {

	/**
	 * Shared instance that can be used when there are no annotations.
	 * 传入的element没有注解
	 */
	static final MergedAnnotations NONE = new TypeMappedAnnotations(
			null, new Annotation[0], RepeatableContainers.none(), AnnotationFilter.ALL);


	/**
	 * 同element
	 */
	@Nullable
	private final Object source;

	/**
	 * 每一个Class对象都是一个AnnotatedElement
	 */
	@Nullable
	private final AnnotatedElement element;

	/**
	 * 注解的搜索策略
	 * {@link SearchStrategy#INHERITED_ANNOTATIONS}
	 */
	@Nullable
	private final SearchStrategy searchStrategy;

	/**
	 * 注解数组
	 */
	@Nullable
	private final Annotation[] annotations;

	/**
	 * {@link RepeatableContainers.NoRepeatableContainers#INSTANCE}
	 */
	private final RepeatableContainers repeatableContainers;

	/**
	 * 注解过滤器
	 * {@link AnnotationFilter#PLAIN}
	 */
	private final AnnotationFilter annotationFilter;

	/**
	 * {@link Aggregate}注解聚合
	 * 每一个{@link Aggregate},包含一个{@link Aggregate#source},它是注解所在的源class类。
	 * 包含一个{@link Aggregate#annotations},这是{@link Aggregate#source}上自身标注的注解集合
	 * <p>
	 * 而这个List<Aggregate>，则是{@link TypeMappedAnnotations#source}及它的父类的{@link Aggregate}
	 * 即一个class类封装成了一个{@link Aggregate}。从子类到父类，就形成了一个List
	 * </p>
	 */
	@Nullable
	private volatile List<Aggregate> aggregates;


	/**
	 * {@link TypeMappedAnnotations#from(java.lang.reflect.AnnotatedElement, org.springframework.core.annotation.MergedAnnotations.SearchStrategy, org.springframework.core.annotation.RepeatableContainers, org.springframework.core.annotation.AnnotationFilter)}
	 * 中被调用
	 *
	 * @param element
	 * @param searchStrategy       {@link SearchStrategy#INHERITED_ANNOTATIONS}
	 * @param repeatableContainers {@link RepeatableContainers.NoRepeatableContainers#INSTANCE}
	 * @param annotationFilter     {@link AnnotationFilter#PLAIN}
	 */
	private TypeMappedAnnotations(AnnotatedElement element, SearchStrategy searchStrategy,
								  RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		this.source = element;
		this.element = element;
		this.searchStrategy = searchStrategy;
		this.annotations = null;
		this.repeatableContainers = repeatableContainers;
		this.annotationFilter = annotationFilter;
	}

	private TypeMappedAnnotations(@Nullable Object source, Annotation[] annotations,
								  RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		this.source = source;
		this.element = null;
		this.searchStrategy = null;
		this.annotations = annotations;
		this.repeatableContainers = repeatableContainers;
		this.annotationFilter = annotationFilter;
	}


	/**
	 * 判断给定的注解是否存在
	 *
	 * @param annotationType the annotation type to check
	 * @param <A>
	 * @return
	 */
	@Override
	public <A extends Annotation> boolean isPresent(Class<A> annotationType) {
		if (this.annotationFilter.matches(annotationType)) {
			// 给定的注解,符合过滤器规则，返回false
			return false;
		}
		return Boolean.TRUE.equals(scan(annotationType,
				IsPresent.get(this.repeatableContainers, this.annotationFilter, false)));
	}

	@Override
	public boolean isPresent(String annotationType) {
		if (this.annotationFilter.matches(annotationType)) {
			return false;
		}
		return Boolean.TRUE.equals(scan(annotationType,
				IsPresent.get(this.repeatableContainers, this.annotationFilter, false)));
	}

	@Override
	public <A extends Annotation> boolean isDirectlyPresent(Class<A> annotationType) {
		if (this.annotationFilter.matches(annotationType)) {
			return false;
		}
		return Boolean.TRUE.equals(scan(annotationType,
				IsPresent.get(this.repeatableContainers, this.annotationFilter, true)));
	}

	@Override
	public boolean isDirectlyPresent(String annotationType) {
		if (this.annotationFilter.matches(annotationType)) {
			return false;
		}
		return Boolean.TRUE.equals(scan(annotationType,
				IsPresent.get(this.repeatableContainers, this.annotationFilter, true)));
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(Class<A> annotationType) {
		return get(annotationType, null, null);
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(Class<A> annotationType,
														  @Nullable Predicate<? super MergedAnnotation<A>> predicate) {

		return get(annotationType, predicate, null);
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(Class<A> annotationType,
														  @Nullable Predicate<? super MergedAnnotation<A>> predicate,
														  @Nullable MergedAnnotationSelector<A> selector) {

		if (this.annotationFilter.matches(annotationType)) {
			return MergedAnnotation.missing();
		}
		MergedAnnotation<A> result = scan(annotationType,
				new MergedAnnotationFinder<>(annotationType, predicate, selector));
		return (result != null ? result : MergedAnnotation.missing());
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(String annotationType) {
		return get(annotationType, null, null);
	}

	@Override
	public <A extends Annotation> MergedAnnotation<A> get(String annotationType,
														  @Nullable Predicate<? super MergedAnnotation<A>> predicate) {

		return get(annotationType, predicate, null);
	}

	/**
	 * {@link AnnotatedTypeMetadata#getAnnotationAttributes(java.lang.String, boolean)}
	 * 中被调用
	 *
	 * @param annotationType 查询的注解的全类名
	 * @param predicate      a predicate that must match, or {@code null} if only
	 *                       type matching is required
	 * @param selector       a selector used to choose the most appropriate annotation
	 *                       within an aggregate, or {@code null} to select the
	 *                       {@linkplain MergedAnnotationSelectors#nearest() nearest}
	 *                       {@link MergedAnnotationSelectors.FirstDirectlyDeclared}
	 * @param <A>
	 * @return
	 */
	@Override
	public <A extends Annotation> MergedAnnotation<A> get(String annotationType,
														  @Nullable Predicate<? super MergedAnnotation<A>> predicate,
														  @Nullable MergedAnnotationSelector<A> selector) {

		if (this.annotationFilter.matches(annotationType)) {
			return MergedAnnotation.missing();
		}
		/**
		 * 注意,这个返回对象是{@link MergedAnnotation}
		 * 而不是{@link MergedAnnotations}
		 */
		MergedAnnotation<A> result = scan(annotationType,
				new MergedAnnotationFinder<>(annotationType, predicate, selector));
		return (result != null ? result : MergedAnnotation.missing());
	}

	@Override
	public <A extends Annotation> Stream<MergedAnnotation<A>> stream(Class<A> annotationType) {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			return Stream.empty();
		}
		return StreamSupport.stream(spliterator(annotationType), false);
	}

	@Override
	public <A extends Annotation> Stream<MergedAnnotation<A>> stream(String annotationType) {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			return Stream.empty();
		}
		return StreamSupport.stream(spliterator(annotationType), false);
	}

	/**
	 * {@link AnnotationMetadata#getAnnotationTypes()}调用
	 *
	 * @return
	 */
	@Override
	public Stream<MergedAnnotation<Annotation>> stream() {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			/**
			 * 如果{@link TypeMappedAnnotations#annotationFilter}是{@link AnnotationFilter#ALL}
			 * 则所有的注解都过滤掉了，返回一个空的
			 */
			return Stream.empty();
		}
		// StreamSupport.stream 这个要学学啊
		return StreamSupport.stream(spliterator(), false);
	}

	@Override
	public Iterator<MergedAnnotation<Annotation>> iterator() {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			return Collections.emptyIterator();
		}
		return Spliterators.iterator(spliterator());
	}

	/**
	 * {@link TypeMappedAnnotations#stream()}中调用
	 *
	 * @return
	 */
	@Override
	public Spliterator<MergedAnnotation<Annotation>> spliterator() {
		if (this.annotationFilter == AnnotationFilter.ALL) {
			return Spliterators.emptySpliterator();
		}
		return spliterator(null);
	}

	/**
	 * {@link TypeMappedAnnotations#spliterator()}中调用
	 *
	 * @param annotationType
	 * @param <A>
	 * @return
	 */
	private <A extends Annotation> Spliterator<MergedAnnotation<A>> spliterator(@Nullable Object annotationType) {

		return new AggregatesSpliterator<>(annotationType, getAggregates());
	}

	/**
	 * <p>
	 * {@link TypeMappedAnnotations#spliterator(java.lang.Object)}
	 * 中调用
	 * </p>
	 * 获取
	 *
	 * @return
	 */
	private List<Aggregate> getAggregates() {
		// 又是一个缓存
		List<Aggregate> aggregates = this.aggregates;
		if (aggregates == null) {
			/**
			 * 如果没收集过,就去收集
			 * 收集器采用{@link AggregatesCollector}
			 */
			aggregates = scan(this, new AggregatesCollector());
			if (aggregates == null || aggregates.isEmpty()) {
				aggregates = Collections.emptyList();
			}
			this.aggregates = aggregates;
		}
		return aggregates;
	}

	/**
	 * 扫描C,根据processor返回R
	 * {@link TypeMappedAnnotations#getAggregates()}中调用
	 * {@link TypeMappedAnnotations#get(java.lang.String, java.util.function.Predicate, org.springframework.core.annotation.MergedAnnotationSelector)}
	 * 中调用
	 *
	 * @param criteria  {@link TypeMappedAnnotations}
	 * @param processor {@link AggregatesCollector}
	 * @param <C>       {@link TypeMappedAnnotations}
	 * @param <R>       List<Aggregate>
	 * @return
	 */
	@Nullable
	private <C, R> R scan(C criteria, AnnotationsProcessor<C, R> processor) {
		if (this.annotations != null) {
			// 不走这个分支
			R result = processor.doWithAnnotations(criteria, 0, this.source, this.annotations);
			return processor.finish(result);
		}
		if (this.element != null && this.searchStrategy != null) {
			/**
			 * 根据传入的原始类对象element和搜集策略searchStrategy去收集
			 * 这个方法里面要特别注意，其实就是
			 * {@link AggregatesCollector#finish(java.util.List)}方法，返回对象和传入的对象无关
			 */
			return AnnotationsScanner.scan(criteria, this.element, this.searchStrategy, processor);
		}
		return null;
	}


	/**
	 * {@link MergedAnnotations#from(java.lang.reflect.AnnotatedElement, org.springframework.core.annotation.MergedAnnotations.SearchStrategy, org.springframework.core.annotation.RepeatableContainers, org.springframework.core.annotation.AnnotationFilter)}
	 * 中被调用
	 *
	 * @param element              目标源class,我们就是要处理它上面的注解
	 * @param searchStrategy       {@link SearchStrategy#INHERITED_ANNOTATIONS} 查询策略
	 * @param repeatableContainers {@link RepeatableContainers.NoRepeatableContainers#INSTANCE}  {@link Repeatable}注解容器
	 * @param annotationFilter     {@link AnnotationFilter#PLAIN} 注解过滤器
	 * @return
	 */
	static MergedAnnotations from(AnnotatedElement element, SearchStrategy searchStrategy,
								  RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		if (AnnotationsScanner.isKnownEmpty(element, searchStrategy)) {
			return NONE;
		}
		return new TypeMappedAnnotations(element, searchStrategy, repeatableContainers, annotationFilter);
	}

	static MergedAnnotations from(@Nullable Object source, Annotation[] annotations,
								  RepeatableContainers repeatableContainers, AnnotationFilter annotationFilter) {

		if (annotations.length == 0) {
			return NONE;
		}
		return new TypeMappedAnnotations(source, annotations, repeatableContainers, annotationFilter);
	}

	/**
	 * {@link MergedAnnotationFinder#process(java.lang.Object, int, java.lang.Object, java.lang.annotation.Annotation)}
	 *
	 * @param mapping
	 * @param annotationFilter
	 * @param requiredType
	 * @return
	 */
	private static boolean isMappingForType(AnnotationTypeMapping mapping,
											AnnotationFilter annotationFilter, @Nullable Object requiredType) {

		/**
		 * 注解的类
		 */
		Class<? extends Annotation> actualType = mapping.getAnnotationType();
		return (!annotationFilter.matches(actualType) &&
				(requiredType == null || actualType == requiredType || actualType.getName().equals(requiredType)));
	}


	/**
	 * {@link AnnotationsProcessor} used to detect if an annotation is directly
	 * present or meta-present.
	 */
	private static final class IsPresent implements AnnotationsProcessor<Object, Boolean> {

		/**
		 * Shared instances that save us needing to create a new processor for
		 * the common combinations.
		 * 缓存常用的四种IsPresent
		 */
		private static final IsPresent[] SHARED;

		static {
			SHARED = new IsPresent[4];
			SHARED[0] = new IsPresent(RepeatableContainers.none(), AnnotationFilter.PLAIN, true);
			SHARED[1] = new IsPresent(RepeatableContainers.none(), AnnotationFilter.PLAIN, false);
			SHARED[2] = new IsPresent(RepeatableContainers.standardRepeatables(), AnnotationFilter.PLAIN, true);
			SHARED[3] = new IsPresent(RepeatableContainers.standardRepeatables(), AnnotationFilter.PLAIN, false);
		}

		private final RepeatableContainers repeatableContainers;

		private final AnnotationFilter annotationFilter;

		private final boolean directOnly;

		private IsPresent(RepeatableContainers repeatableContainers,
						  AnnotationFilter annotationFilter, boolean directOnly) {

			this.repeatableContainers = repeatableContainers;
			this.annotationFilter = annotationFilter;
			this.directOnly = directOnly;
		}

		/**
		 * 有用的好像只有这一个方法
		 *
		 * @param requiredType
		 * @param aggregateIndex the aggregate index of the provided annotations
		 * @param source         the original source of the annotations, if known
		 * @param annotations    the annotations to process (this array may contain
		 *                       {@code null} elements)
		 * @return
		 */
		@Override
		@Nullable
		public Boolean doWithAnnotations(Object requiredType, int aggregateIndex,
										 @Nullable Object source, Annotation[] annotations) {

			for (Annotation annotation : annotations) {
				if (annotation != null) {
					Class<? extends Annotation> type = annotation.annotationType();
					if (type != null && !this.annotationFilter.matches(type)) {
						if (type == requiredType || type.getName().equals(requiredType)) {
							return Boolean.TRUE;
						}
						Annotation[] repeatedAnnotations =
								this.repeatableContainers.findRepeatedAnnotations(annotation);
						if (repeatedAnnotations != null) {
							Boolean result = doWithAnnotations(
									requiredType, aggregateIndex, source, repeatedAnnotations);
							if (result != null) {
								return result;
							}
						}
						if (!this.directOnly) {
							AnnotationTypeMappings mappings = AnnotationTypeMappings.forAnnotationType(type);
							for (int i = 0; i < mappings.size(); i++) {
								AnnotationTypeMapping mapping = mappings.get(i);
								if (isMappingForType(mapping, this.annotationFilter, requiredType)) {
									return Boolean.TRUE;
								}
							}
						}
					}
				}
			}
			return null;
		}

		static IsPresent get(RepeatableContainers repeatableContainers,
							 AnnotationFilter annotationFilter, boolean directOnly) {

			// Use a single shared instance for common combinations
			/**
			 * 如果传入的{@link AnnotationFilter}是{@link AnnotationFilter.PLAIN}
			 * 则和repeatableContainers就没关系了。只和directOnly有关系
			 */
			if (annotationFilter == AnnotationFilter.PLAIN) {
				if (repeatableContainers == RepeatableContainers.none()) {
					return SHARED[directOnly ? 0 : 1];
				}
				if (repeatableContainers == RepeatableContainers.standardRepeatables()) {
					return SHARED[directOnly ? 2 : 3];
				}
			}
			return new IsPresent(repeatableContainers, annotationFilter, directOnly);
		}
	}


	/**
	 * {@link AnnotationsProcessor} that finds a single {@link MergedAnnotation}.
	 */
	private class MergedAnnotationFinder<A extends Annotation>
			implements AnnotationsProcessor<Object, MergedAnnotation<A>> {

		private final Object requiredType;

		@Nullable
		private final Predicate<? super MergedAnnotation<A>> predicate;

		private final MergedAnnotationSelector<A> selector;

		@Nullable
		private MergedAnnotation<A> result;

		/**
		 * {@link TypeMappedAnnotations#get(java.lang.String, java.util.function.Predicate, org.springframework.core.annotation.MergedAnnotationSelector)}
		 * 中调用
		 *
		 * @param requiredType 查询的注解的全类名
		 * @param predicate    null
		 * @param selector     {@link MergedAnnotationSelectors.FirstDirectlyDeclared}
		 */
		MergedAnnotationFinder(Object requiredType, @Nullable Predicate<? super MergedAnnotation<A>> predicate,
							   @Nullable MergedAnnotationSelector<A> selector) {

			this.requiredType = requiredType;
			this.predicate = predicate;
			this.selector = (selector != null ? selector : MergedAnnotationSelectors.nearest());
		}

		@Override
		@Nullable
		public MergedAnnotation<A> doWithAggregate(Object context, int aggregateIndex) {
			return this.result;
		}

		/**
		 * {@link AnnotationsScanner#processClassInheritedAnnotations(java.lang.Object, java.lang.Class, org.springframework.core.annotation.MergedAnnotations.SearchStrategy, org.springframework.core.annotation.AnnotationsProcessor)}
		 * 中被调用
		 *
		 * @param type           要查找的注解
		 * @param aggregateIndex 离子类的距离
		 * @param source         注解所在的class类
		 * @param annotations    该class上的注解
		 *                       {@code null} elements)
		 * @return
		 */
		@Override
		@Nullable
		public MergedAnnotation<A> doWithAnnotations(Object type, int aggregateIndex,
													 @Nullable Object source, Annotation[] annotations) {

			for (Annotation annotation : annotations) {
				if (annotation != null && !annotationFilter.matches(annotation)) {
					MergedAnnotation<A> result = process(type, aggregateIndex, source, annotation);
					if (result != null) {
						return result;
					}
				}
			}
			return null;
		}

		/**
		 * {@link MergedAnnotationFinder#doWithAnnotations(java.lang.Object, int, java.lang.Object, java.lang.annotation.Annotation[])}
		 * 中被调用
		 *
		 * @param type
		 * @param aggregateIndex
		 * @param source
		 * @param annotation
		 * @return
		 */
		@Nullable
		private MergedAnnotation<A> process(
				Object type, int aggregateIndex, @Nullable Object source, Annotation annotation) {

			Annotation[] repeatedAnnotations = repeatableContainers.findRepeatedAnnotations(annotation);
			if (repeatedAnnotations != null) {
				return doWithAnnotations(type, aggregateIndex, source, repeatedAnnotations);
			}
			AnnotationTypeMappings mappings = AnnotationTypeMappings.forAnnotationType(
					annotation.annotationType(), repeatableContainers, annotationFilter);
			for (int i = 0; i < mappings.size(); i++) {
				AnnotationTypeMapping mapping = mappings.get(i);
				if (isMappingForType(mapping, annotationFilter, this.requiredType)) {
					MergedAnnotation<A> candidate = TypeMappedAnnotation.createIfPossible(
							mapping, source, annotation, aggregateIndex, IntrospectionFailureLogger.INFO);
					if (candidate != null && (this.predicate == null || this.predicate.test(candidate))) {
						if (this.selector.isBestCandidate(candidate)) {
							return candidate;
						}
						updateLastResult(candidate);
					}
				}
			}
			return null;
		}

		private void updateLastResult(MergedAnnotation<A> candidate) {
			MergedAnnotation<A> lastResult = this.result;
			this.result = (lastResult != null ? this.selector.select(lastResult, candidate) : candidate);
		}

		@Override
		@Nullable
		public MergedAnnotation<A> finish(@Nullable MergedAnnotation<A> result) {
			return (result != null ? result : this.result);
		}
	}


	/**
	 * {@link AnnotationsProcessor} that collects {@link Aggregate} instances.
	 * 收集{@link Aggregate}的{@link AnnotationsProcessor}
	 */
	private class AggregatesCollector implements AnnotationsProcessor<Object, List<Aggregate>> {

		/**
		 * 记录收集的{@link Aggregate}
		 */
		private final List<Aggregate> aggregates = new ArrayList<>();

		/**
		 * <p>
		 * {@link AnnotationsScanner#processElement(java.lang.Object, java.lang.reflect.AnnotatedElement, org.springframework.core.annotation.AnnotationsProcessor)}
		 * 中被调用
		 * </p>
		 * 特别的注意,{@link AggregatesCollector#doWithAnnotations(java.lang.Object, int, java.lang.Object, java.lang.annotation.Annotation[])}
		 * 该实现,criteria参数无效了
		 *
		 * @param criteria       {@link TypeMappedAnnotations}
		 * @param aggregateIndex the aggregate index of the provided annotations,传的是0
		 * @param source         最初的class
		 * @param annotations    the annotations to process (this array may contain
		 *                       {@code null} elements)
		 *                       source上面的需要关注的注解
		 * @return
		 */
		@Override
		@Nullable
		public List<Aggregate> doWithAnnotations(Object criteria, int aggregateIndex,
												 @Nullable Object source, Annotation[] annotations) {

			this.aggregates.add(createAggregate(aggregateIndex, source, annotations));
			return null;
		}

		/**
		 * 把一个source和它上面的注解，封装成一个Aggregate对象
		 * <p>
		 * {@link AggregatesCollector#doWithAnnotations(java.lang.Object, int, java.lang.Object, java.lang.annotation.Annotation[])}
		 * 中被调用
		 * </p>
		 *
		 * @param aggregateIndex 传入的0
		 * @param source         最初的class
		 * @param annotations    source上的自身注解数组。不包括继承了父类的。每个注解的方法，参数长度都是0，返回都不是void
		 * @return
		 */
		private Aggregate createAggregate(int aggregateIndex, @Nullable Object source, Annotation[] annotations) {
			List<Annotation> aggregateAnnotations = getAggregateAnnotations(annotations);
			/**
			 * 把一个source和它上面的注解，封装成一个Aggregate对象
			 */
			return new Aggregate(aggregateIndex, source, aggregateAnnotations);
		}

		/**
		 * {@link AggregatesCollector#createAggregate(int, java.lang.Object, java.lang.annotation.Annotation[])}
		 * 中被调用
		 *
		 * @param annotations
		 * @return
		 */
		private List<Annotation> getAggregateAnnotations(Annotation[] annotations) {
			List<Annotation> result = new ArrayList<>(annotations.length);
			addAggregateAnnotations(result, annotations);
			return result;
		}

		/**
		 * 将annotations中的 注解，添加到aggregateAnnotations中
		 * {@link AggregatesCollector#getAggregateAnnotations(java.lang.annotation.Annotation[])}
		 * 中被调用
		 *
		 * @param aggregateAnnotations 空的list
		 * @param annotations          注解数组
		 */
		private void addAggregateAnnotations(List<Annotation> aggregateAnnotations, Annotation[] annotations) {
			for (Annotation annotation : annotations) {
				if (annotation != null && !annotationFilter.matches(annotation)) {
					// 去掉为空或者符合过滤的注解
					Annotation[] repeatedAnnotations = repeatableContainers.findRepeatedAnnotations(annotation);
					if (repeatedAnnotations != null) {
						addAggregateAnnotations(aggregateAnnotations, repeatedAnnotations);
					} else {
						// 走这个分支
						aggregateAnnotations.add(annotation);
					}
				}
			}
		}

		/**
		 * 特别注意这个方法,和传入的参数没关系
		 *
		 * @param processResult
		 * @return
		 */
		@Override
		public List<Aggregate> finish(@Nullable List<Aggregate> processResult) {
			return this.aggregates;
		}
	}


	/**
	 * 聚合
	 */
	private static class Aggregate {

		private final int aggregateIndex;

		@Nullable
		private final Object source;

		/**
		 * source上的自身注解集合,不包括继承了父类的注解
		 */
		private final List<Annotation> annotations;

		private final AnnotationTypeMappings[] mappings;

		/**
		 * {@link AggregatesCollector#createAggregate(int, java.lang.Object, java.lang.annotation.Annotation[])}
		 * 中调用
		 *
		 * @param aggregateIndex 在父子类中，记录当前类离最开始的子类的距离。
		 * @param source         原始的{@link AnnotatedElement}对象
		 * @param annotations    source上的自身注解,不包括继承了父类的
		 */
		Aggregate(int aggregateIndex, @Nullable Object source, List<Annotation> annotations) {
			this.aggregateIndex = aggregateIndex;
			this.source = source;
			this.annotations = annotations;
			this.mappings = new AnnotationTypeMappings[annotations.size()];
			for (int i = 0; i < annotations.size(); i++) {
				/**
				 * {@link AnnotationTypeMappings}
				 */
				this.mappings[i] = AnnotationTypeMappings.forAnnotationType(annotations.get(i).annotationType());
			}
		}

		int size() {
			return this.annotations.size();
		}

		/**
		 * {@link AggregatesSpliterator#getNextSuitableMapping(org.springframework.core.annotation.TypeMappedAnnotations.Aggregate, int)}
		 * 中调用
		 *
		 * @param annotationIndex
		 * @param mappingIndex
		 * @return
		 */
		@Nullable
		AnnotationTypeMapping getMapping(int annotationIndex, int mappingIndex) {
			AnnotationTypeMappings mappings = getMappings(annotationIndex);
			return (mappingIndex < mappings.size() ? mappings.get(mappingIndex) : null);
		}

		AnnotationTypeMappings getMappings(int annotationIndex) {
			return this.mappings[annotationIndex];
		}

		@Nullable
		<A extends Annotation> MergedAnnotation<A> createMergedAnnotationIfPossible(
				int annotationIndex, int mappingIndex, IntrospectionFailureLogger logger) {

			return TypeMappedAnnotation.createIfPossible(
					this.mappings[annotationIndex].get(mappingIndex), this.source,
					this.annotations.get(annotationIndex), this.aggregateIndex, logger);
		}
	}


	/**
	 * {@link Spliterator} used to consume merged annotations from the
	 * aggregates in distance fist order.
	 */
	private class AggregatesSpliterator<A extends Annotation> implements Spliterator<MergedAnnotation<A>> {

		@Nullable
		private final Object requiredType;

		private final List<Aggregate> aggregates;

		private int aggregateCursor;

		@Nullable
		private int[] mappingCursors;

		/**
		 * {@link TypeMappedAnnotations#spliterator(java.lang.Object)}中调用
		 *
		 * @param requiredType 空,查找的类型？
		 * @param aggregates   List<Aggregate>
		 */
		AggregatesSpliterator(@Nullable Object requiredType, List<Aggregate> aggregates) {
			this.requiredType = requiredType;
			this.aggregates = aggregates;
			// 从最底层的子类开始查找
			this.aggregateCursor = 0;
		}

		/**
		 * 顺序处理每个元素，类似Iterator，如果还有元素要处理，则返回true，否则返回false
		 *
		 * @param action
		 * @return
		 */
		@Override
		public boolean tryAdvance(Consumer<? super MergedAnnotation<A>> action) {
			while (this.aggregateCursor < this.aggregates.size()) {
				// aggregateCursor表示离最底层子类的距离。数越大，说明越是上层的父类
				Aggregate aggregate = this.aggregates.get(this.aggregateCursor);
				if (tryAdvance(aggregate, action)) {
					return true;
				}
				this.aggregateCursor++;
				// 每一次遍历aggregate，都置空
				this.mappingCursors = null;
			}
			return false;
		}

		/**
		 * {@link AggregatesSpliterator#tryAdvance(java.util.function.Consumer)}
		 * 中调用
		 *
		 * @param aggregate
		 * @param action
		 * @return
		 */
		private boolean tryAdvance(Aggregate aggregate, Consumer<? super MergedAnnotation<A>> action) {
			if (this.mappingCursors == null) {
				// aggregate的注解数量
				this.mappingCursors = new int[aggregate.size()];
			}
			int lowestDistance = Integer.MAX_VALUE;
			int annotationResult = -1;
			for (int annotationIndex = 0; annotationIndex < aggregate.size(); annotationIndex++) {
				AnnotationTypeMapping mapping = getNextSuitableMapping(aggregate, annotationIndex);
				if (mapping != null && mapping.getDistance() < lowestDistance) {
					annotationResult = annotationIndex;
					lowestDistance = mapping.getDistance();
				}
				if (lowestDistance == 0) {
					break;
				}
			}
			if (annotationResult != -1) {
				MergedAnnotation<A> mergedAnnotation = aggregate.createMergedAnnotationIfPossible(
						annotationResult, this.mappingCursors[annotationResult],
						this.requiredType != null ? IntrospectionFailureLogger.INFO : IntrospectionFailureLogger.DEBUG);
				this.mappingCursors[annotationResult]++;
				if (mergedAnnotation == null) {
					return tryAdvance(aggregate, action);
				}
				action.accept(mergedAnnotation);
				return true;
			}
			return false;
		}

		/**
		 * @param aggregate       aggregate
		 * @param annotationIndex aggregate 注解数组的从0到size
		 * @return
		 */
		@Nullable
		private AnnotationTypeMapping getNextSuitableMapping(Aggregate aggregate, int annotationIndex) {
			// 第一次进来，全是0啊
			int[] cursors = this.mappingCursors;
			if (cursors != null) {
				AnnotationTypeMapping mapping;
				do {
					mapping = aggregate.getMapping(annotationIndex, cursors[annotationIndex]);
					if (mapping != null && isMappingForType(mapping, annotationFilter, this.requiredType)) {
						return mapping;
					}
					cursors[annotationIndex]++;
				}
				while (mapping != null);
			}
			return null;
		}

		@Override
		@Nullable
		public Spliterator<MergedAnnotation<A>> trySplit() {
			return null;
		}

		@Override
		public long estimateSize() {
			int size = 0;
			for (int aggregateIndex = this.aggregateCursor;
				 aggregateIndex < this.aggregates.size(); aggregateIndex++) {
				Aggregate aggregate = this.aggregates.get(aggregateIndex);
				for (int annotationIndex = 0; annotationIndex < aggregate.size(); annotationIndex++) {
					AnnotationTypeMappings mappings = aggregate.getMappings(annotationIndex);
					int numberOfMappings = mappings.size();
					if (aggregateIndex == this.aggregateCursor && this.mappingCursors != null) {
						numberOfMappings -= Math.min(this.mappingCursors[annotationIndex], mappings.size());
					}
					size += numberOfMappings;
				}
			}
			return size;
		}

		@Override
		public int characteristics() {
			return NONNULL | IMMUTABLE;
		}
	}

}
