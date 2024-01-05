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
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Scanner to search for relevant annotations in the annotation hierarchy of an
 * {@link AnnotatedElement}.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @see AnnotationsProcessor
 * @since 5.2
 */
abstract class AnnotationsScanner {

	private static final Annotation[] NO_ANNOTATIONS = {};

	private static final Method[] NO_METHODS = {};


	private static final Map<AnnotatedElement, Annotation[]> declaredAnnotationCache =
			new ConcurrentReferenceHashMap<>(256);

	private static final Map<Class<?>, Method[]> baseTypeMethodsCache =
			new ConcurrentReferenceHashMap<>(256);


	private AnnotationsScanner() {
	}


	/**
	 * Scan the hierarchy of the specified element for relevant annotations and
	 * call the processor as required.
	 *
	 * <p>
	 * {@link TypeMappedAnnotations#scan(java.lang.Object, org.springframework.core.annotation.AnnotationsProcessor)}
	 * 中调用
	 * </p>
	 *
	 * @param context        {@link TypeMappedAnnotations}
	 * @param source         the source element to scan
	 * @param searchStrategy {@link SearchStrategy#INHERITED_ANNOTATIONS}
	 * @param processor      {@link TypeMappedAnnotations.AggregatesCollector}
	 * @return the result of {@link AnnotationsProcessor#finish(Object)}
	 */
	@Nullable
	static <C, R> R scan(C context, AnnotatedElement source, SearchStrategy searchStrategy,
						 AnnotationsProcessor<C, R> processor) {

		R result = process(context, source, searchStrategy, processor);
		return processor.finish(result);
	}

	/**
	 * {@link AnnotationsScanner#scan(java.lang.Object, java.lang.reflect.AnnotatedElement, org.springframework.core.annotation.MergedAnnotations.SearchStrategy, org.springframework.core.annotation.AnnotationsProcessor)}
	 * 中调用
	 *
	 * @param context        {@link TypeMappedAnnotations}
	 * @param source
	 * @param searchStrategy {@link SearchStrategy#INHERITED_ANNOTATIONS}
	 * @param processor      {@link TypeMappedAnnotations.AggregatesCollector}
	 * @param <C>
	 * @param <R>
	 * @return
	 */
	@Nullable
	private static <C, R> R process(C context, AnnotatedElement source,
									SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		if (source instanceof Class) {
			return processClass(context, (Class<?>) source, searchStrategy, processor);
		}
		if (source instanceof Method) {
			return processMethod(context, (Method) source, searchStrategy, processor);
		}
		return processElement(context, source, processor);
	}

	/**
	 * {@link AnnotationsScanner#process(java.lang.Object, java.lang.reflect.AnnotatedElement, org.springframework.core.annotation.MergedAnnotations.SearchStrategy, org.springframework.core.annotation.AnnotationsProcessor)}
	 * 中调用
	 *
	 * @param context        {@link TypeMappedAnnotations}
	 * @param source
	 * @param searchStrategy {@link SearchStrategy#INHERITED_ANNOTATIONS}
	 * @param processor      {@link TypeMappedAnnotations.AggregatesCollector}
	 * @param <C>
	 * @param <R>
	 * @return
	 */
	@Nullable
	private static <C, R> R processClass(C context, Class<?> source,
										 SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		switch (searchStrategy) {
			case DIRECT:
				return processElement(context, source, processor);
			case INHERITED_ANNOTATIONS:
				return processClassInheritedAnnotations(context, source, searchStrategy, processor);
			case SUPERCLASS:
				return processClassHierarchy(context, source, processor, false, false);
			case TYPE_HIERARCHY:
				return processClassHierarchy(context, source, processor, true, false);
			case TYPE_HIERARCHY_AND_ENCLOSING_CLASSES:
				return processClassHierarchy(context, source, processor, true, true);
		}
		throw new IllegalStateException("Unsupported search strategy " + searchStrategy);
	}

	/**
	 * {@link AnnotationsScanner#processClass(java.lang.Object, java.lang.Class, org.springframework.core.annotation.MergedAnnotations.SearchStrategy, org.springframework.core.annotation.AnnotationsProcessor)}
	 * 中被调用
	 * {@link SearchStrategy#INHERITED_ANNOTATIONS}
	 *
	 * @param context        {@link TypeMappedAnnotations}
	 * @param source
	 * @param searchStrategy {@link SearchStrategy#INHERITED_ANNOTATIONS}
	 * @param processor      {@link TypeMappedAnnotations.AggregatesCollector}
	 * @param <C>
	 * @param <R>
	 * @return
	 */
	@Nullable
	private static <C, R> R processClassInheritedAnnotations(C context, Class<?> source,
															 SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		try {
			if (isWithoutHierarchy(source, searchStrategy)) {
				return processElement(context, source, processor);
			}
			Annotation[] relevant = null;
			int remaining = Integer.MAX_VALUE;
			int aggregateIndex = 0;
			Class<?> root = source;
			while (source != null && source != Object.class && remaining > 0 &&
					!hasPlainJavaAnnotationsOnly(source)) {
				R result = processor.doWithAggregate(context, aggregateIndex);
				if (result != null) {
					return result;
				}
				Annotation[] declaredAnnotations = getDeclaredAnnotations(source, true);
				if (relevant == null && declaredAnnotations.length > 0) {
					relevant = root.getAnnotations();
					remaining = relevant.length;
				}
				for (int i = 0; i < declaredAnnotations.length; i++) {
					if (declaredAnnotations[i] != null) {
						boolean isRelevant = false;
						for (int relevantIndex = 0; relevantIndex < relevant.length; relevantIndex++) {
							if (relevant[relevantIndex] != null &&
									declaredAnnotations[i].annotationType() == relevant[relevantIndex].annotationType()) {
								isRelevant = true;
								relevant[relevantIndex] = null;
								remaining--;
								break;
							}
						}
						if (!isRelevant) {
							declaredAnnotations[i] = null;
						}
					}
				}
				result = processor.doWithAnnotations(context, aggregateIndex, source, declaredAnnotations);
				if (result != null) {
					return result;
				}
				source = source.getSuperclass();
				aggregateIndex++;
			}
		} catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processClassHierarchy(C context, Class<?> source,
												  AnnotationsProcessor<C, R> processor, boolean includeInterfaces, boolean includeEnclosing) {

		return processClassHierarchy(context, new int[]{0}, source, processor,
				includeInterfaces, includeEnclosing);
	}

	@Nullable
	private static <C, R> R processClassHierarchy(C context, int[] aggregateIndex, Class<?> source,
												  AnnotationsProcessor<C, R> processor, boolean includeInterfaces, boolean includeEnclosing) {

		try {
			R result = processor.doWithAggregate(context, aggregateIndex[0]);
			if (result != null) {
				return result;
			}
			if (hasPlainJavaAnnotationsOnly(source)) {
				return null;
			}
			Annotation[] annotations = getDeclaredAnnotations(source, false);
			result = processor.doWithAnnotations(context, aggregateIndex[0], source, annotations);
			if (result != null) {
				return result;
			}
			aggregateIndex[0]++;
			if (includeInterfaces) {
				for (Class<?> interfaceType : source.getInterfaces()) {
					R interfacesResult = processClassHierarchy(context, aggregateIndex,
							interfaceType, processor, true, includeEnclosing);
					if (interfacesResult != null) {
						return interfacesResult;
					}
				}
			}
			Class<?> superclass = source.getSuperclass();
			if (superclass != Object.class && superclass != null) {
				R superclassResult = processClassHierarchy(context, aggregateIndex,
						superclass, processor, includeInterfaces, includeEnclosing);
				if (superclassResult != null) {
					return superclassResult;
				}
			}
			if (includeEnclosing) {
				// Since merely attempting to load the enclosing class may result in
				// automatic loading of sibling nested classes that in turn results
				// in an exception such as NoClassDefFoundError, we wrap the following
				// in its own dedicated try-catch block in order not to preemptively
				// halt the annotation scanning process.
				try {
					Class<?> enclosingClass = source.getEnclosingClass();
					if (enclosingClass != null) {
						R enclosingResult = processClassHierarchy(context, aggregateIndex,
								enclosingClass, processor, includeInterfaces, true);
						if (enclosingResult != null) {
							return enclosingResult;
						}
					}
				} catch (Throwable ex) {
					AnnotationUtils.handleIntrospectionFailure(source, ex);
				}
			}
		} catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processMethod(C context, Method source,
										  SearchStrategy searchStrategy, AnnotationsProcessor<C, R> processor) {

		switch (searchStrategy) {
			case DIRECT:
			case INHERITED_ANNOTATIONS:
				return processMethodInheritedAnnotations(context, source, processor);
			case SUPERCLASS:
				return processMethodHierarchy(context, new int[]{0}, source.getDeclaringClass(),
						processor, source, false);
			case TYPE_HIERARCHY:
			case TYPE_HIERARCHY_AND_ENCLOSING_CLASSES:
				return processMethodHierarchy(context, new int[]{0}, source.getDeclaringClass(),
						processor, source, true);
		}
		throw new IllegalStateException("Unsupported search strategy " + searchStrategy);
	}

	@Nullable
	private static <C, R> R processMethodInheritedAnnotations(C context, Method source,
															  AnnotationsProcessor<C, R> processor) {

		try {
			R result = processor.doWithAggregate(context, 0);
			return (result != null ? result :
					processMethodAnnotations(context, 0, source, processor));
		} catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@Nullable
	private static <C, R> R processMethodHierarchy(C context, int[] aggregateIndex,
												   Class<?> sourceClass, AnnotationsProcessor<C, R> processor, Method rootMethod,
												   boolean includeInterfaces) {

		try {
			R result = processor.doWithAggregate(context, aggregateIndex[0]);
			if (result != null) {
				return result;
			}
			if (hasPlainJavaAnnotationsOnly(sourceClass)) {
				return null;
			}
			boolean calledProcessor = false;
			if (sourceClass == rootMethod.getDeclaringClass()) {
				result = processMethodAnnotations(context, aggregateIndex[0],
						rootMethod, processor);
				calledProcessor = true;
				if (result != null) {
					return result;
				}
			} else {
				for (Method candidateMethod : getBaseTypeMethods(context, sourceClass)) {
					if (candidateMethod != null && isOverride(rootMethod, candidateMethod)) {
						result = processMethodAnnotations(context, aggregateIndex[0],
								candidateMethod, processor);
						calledProcessor = true;
						if (result != null) {
							return result;
						}
					}
				}
			}
			if (Modifier.isPrivate(rootMethod.getModifiers())) {
				return null;
			}
			if (calledProcessor) {
				aggregateIndex[0]++;
			}
			if (includeInterfaces) {
				for (Class<?> interfaceType : sourceClass.getInterfaces()) {
					R interfacesResult = processMethodHierarchy(context, aggregateIndex,
							interfaceType, processor, rootMethod, true);
					if (interfacesResult != null) {
						return interfacesResult;
					}
				}
			}
			Class<?> superclass = sourceClass.getSuperclass();
			if (superclass != Object.class && superclass != null) {
				R superclassResult = processMethodHierarchy(context, aggregateIndex,
						superclass, processor, rootMethod, includeInterfaces);
				if (superclassResult != null) {
					return superclassResult;
				}
			}
		} catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(rootMethod, ex);
		}
		return null;
	}

	private static <C> Method[] getBaseTypeMethods(C context, Class<?> baseType) {
		if (baseType == Object.class || hasPlainJavaAnnotationsOnly(baseType)) {
			return NO_METHODS;
		}

		Method[] methods = baseTypeMethodsCache.get(baseType);
		if (methods == null) {
			methods = ReflectionUtils.getDeclaredMethods(baseType);
			int cleared = 0;
			for (int i = 0; i < methods.length; i++) {
				if (Modifier.isPrivate(methods[i].getModifiers()) ||
						hasPlainJavaAnnotationsOnly(methods[i]) ||
						getDeclaredAnnotations(methods[i], false).length == 0) {
					methods[i] = null;
					cleared++;
				}
			}
			if (cleared == methods.length) {
				methods = NO_METHODS;
			}
			baseTypeMethodsCache.put(baseType, methods);
		}
		return methods;
	}

	private static boolean isOverride(Method rootMethod, Method candidateMethod) {
		return (!Modifier.isPrivate(candidateMethod.getModifiers()) &&
				candidateMethod.getName().equals(rootMethod.getName()) &&
				hasSameParameterTypes(rootMethod, candidateMethod));
	}

	private static boolean hasSameParameterTypes(Method rootMethod, Method candidateMethod) {
		if (candidateMethod.getParameterCount() != rootMethod.getParameterCount()) {
			return false;
		}
		Class<?>[] rootParameterTypes = rootMethod.getParameterTypes();
		Class<?>[] candidateParameterTypes = candidateMethod.getParameterTypes();
		if (Arrays.equals(candidateParameterTypes, rootParameterTypes)) {
			return true;
		}
		return hasSameGenericTypeParameters(rootMethod, candidateMethod,
				rootParameterTypes);
	}

	private static boolean hasSameGenericTypeParameters(
			Method rootMethod, Method candidateMethod, Class<?>[] rootParameterTypes) {

		Class<?> sourceDeclaringClass = rootMethod.getDeclaringClass();
		Class<?> candidateDeclaringClass = candidateMethod.getDeclaringClass();
		if (!candidateDeclaringClass.isAssignableFrom(sourceDeclaringClass)) {
			return false;
		}
		for (int i = 0; i < rootParameterTypes.length; i++) {
			Class<?> resolvedParameterType = ResolvableType.forMethodParameter(
					candidateMethod, i, sourceDeclaringClass).resolve();
			if (rootParameterTypes[i] != resolvedParameterType) {
				return false;
			}
		}
		return true;
	}

	@Nullable
	private static <C, R> R processMethodAnnotations(C context, int aggregateIndex, Method source,
													 AnnotationsProcessor<C, R> processor) {

		Annotation[] annotations = getDeclaredAnnotations(source, false);
		R result = processor.doWithAnnotations(context, aggregateIndex, source, annotations);
		if (result != null) {
			return result;
		}
		Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(source);
		if (bridgedMethod != source) {
			Annotation[] bridgedAnnotations = getDeclaredAnnotations(bridgedMethod, true);
			for (int i = 0; i < bridgedAnnotations.length; i++) {
				if (ObjectUtils.containsElement(annotations, bridgedAnnotations[i])) {
					bridgedAnnotations[i] = null;
				}
			}
			return processor.doWithAnnotations(context, aggregateIndex, source, bridgedAnnotations);
		}
		return null;
	}

	/**
	 * {@link SearchStrategy#DIRECT}
	 * <p>
	 * {@link AnnotationsScanner#processClass(java.lang.Object, java.lang.Class, org.springframework.core.annotation.MergedAnnotations.SearchStrategy, org.springframework.core.annotation.AnnotationsProcessor)}
	 * 中调用
	 *
	 * @param context   {@link TypeMappedAnnotations}
	 * @param source
	 * @param processor {@link TypeMappedAnnotations.AggregatesCollector}
	 * @param <C>
	 * @param <R>
	 * @return
	 */
	@Nullable
	private static <C, R> R processElement(C context, AnnotatedElement source,
										   AnnotationsProcessor<C, R> processor) {

		try {
			R result = processor.doWithAggregate(context, 0);
			return (result != null ? result : processor.doWithAnnotations(
					context, 0, source, getDeclaredAnnotations(source, false)));
		} catch (Throwable ex) {
			AnnotationUtils.handleIntrospectionFailure(source, ex);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	@Nullable
	static <A extends Annotation> A getDeclaredAnnotation(AnnotatedElement source, Class<A> annotationType) {
		Annotation[] annotations = getDeclaredAnnotations(source, false);
		for (Annotation annotation : annotations) {
			if (annotation != null && annotationType == annotation.annotationType()) {
				return (A) annotation;
			}
		}
		return null;
	}

	/**
	 * {@link AnnotationsScanner#processElement(java.lang.Object, java.lang.reflect.AnnotatedElement, org.springframework.core.annotation.AnnotationsProcessor)}
	 * 中被调用
	 *
	 * @param source
	 * @param defensive false
	 * @return
	 */
	static Annotation[] getDeclaredAnnotations(AnnotatedElement source, boolean defensive) {
		boolean cached = false;
		// 去缓存中找
		Annotation[] annotations = declaredAnnotationCache.get(source);
		if (annotations != null) {
			cached = true;
		} else {
			/**
			 * https://blog.csdn.net/qq_38350925/article/details/105820151
			 * getDeclaredAnnotations方法只会返回当前类的注解，不会返回父类的注解
			 */
			annotations = source.getDeclaredAnnotations();
			if (annotations.length != 0) {
				boolean allIgnored = true;
				for (int i = 0; i < annotations.length; i++) {
					Annotation annotation = annotations[i];
					/**
					 * annotationType方法会返回该annotation的class
					 */
					if (isIgnorable(annotation.annotationType()) ||
							!AttributeMethods.forAnnotationType(annotation.annotationType()).isValid(annotation)) {
						annotations[i] = null;
					} else {
						allIgnored = false;
					}
				}
				annotations = (allIgnored ? NO_ANNOTATIONS : annotations);
				if (source instanceof Class || source instanceof Member) {
					declaredAnnotationCache.put(source, annotations);
					cached = true;
				}
			}
		}
		if (!defensive || annotations.length == 0 || !cached) {
			return annotations;
		}
		return annotations.clone();
	}

	/**
	 * 传入的annotationType所在的包名，
	 * 是否以"java.lang", "org.springframework.lang"等开头
	 * {@link AnnotationsScanner#getDeclaredAnnotations(java.lang.reflect.AnnotatedElement, boolean)}
	 * 中调用
	 *
	 * @param annotationType
	 * @return
	 */
	private static boolean isIgnorable(Class<?> annotationType) {
		/**
		 * {@link PackagesAnnotationFilter#matches(java.lang.String)}
		 */
		return AnnotationFilter.PLAIN.matches(annotationType);
	}

	/**
	 * {@link TypeMappedAnnotations#from(java.lang.reflect.AnnotatedElement, org.springframework.core.annotation.MergedAnnotations.SearchStrategy, org.springframework.core.annotation.RepeatableContainers, org.springframework.core.annotation.AnnotationFilter)}
	 * 中调用
	 *
	 * @param source
	 * @param searchStrategy {@link SearchStrategy#INHERITED_ANNOTATIONS}
	 * @return
	 */
	static boolean isKnownEmpty(AnnotatedElement source, SearchStrategy searchStrategy) {
		if (hasPlainJavaAnnotationsOnly(source)) {
			return true;
		}
		if (searchStrategy == SearchStrategy.DIRECT || isWithoutHierarchy(source, searchStrategy)) {
			/**
			 * {@link Method#isBridge()}可以参考
			 * https://vimsky.com/examples/usage/method-class-isbridge-method-in-java.html
			 */
			if (source instanceof Method && ((Method) source).isBridge()) {
				return false;
			}
			return getDeclaredAnnotations(source, false).length == 0;
		}
		return false;
	}

	/**
	 * 判断传入的class是否以包名java开头,或者自身是{@link Ordered}
	 * 如果传入的是{@link Member},则看归属的class是否符合上述规则
	 * 其它返回false
	 * {@link AnnotationsScanner#isKnownEmpty(java.lang.reflect.AnnotatedElement, org.springframework.core.annotation.MergedAnnotations.SearchStrategy)}
	 * 中调用
	 *
	 * @param annotatedElement
	 * @return
	 */
	static boolean hasPlainJavaAnnotationsOnly(@Nullable Object annotatedElement) {
		if (annotatedElement instanceof Class) {
			return hasPlainJavaAnnotationsOnly((Class<?>) annotatedElement);
		} else if (annotatedElement instanceof Member) {
			/**
			 * 关于Member的资料:https://blog.csdn.net/yaomingyang/article/details/81412180
			 * 获取该方法所归属的类
			 */
			return hasPlainJavaAnnotationsOnly(((Member) annotatedElement).getDeclaringClass());
		} else {
			return false;
		}
	}

	/**
	 * 判断是否在java下面的包里，或者自身是{@link Ordered}
	 * {@link AnnotationsScanner#hasPlainJavaAnnotationsOnly(java.lang.Object)}
	 * 中调用
	 *
	 * @param type
	 * @return
	 */
	static boolean hasPlainJavaAnnotationsOnly(Class<?> type) {
		return (type.getName().startsWith("java.") || type == Ordered.class);
	}

	/**
	 * 无等级的
	 * {@link AnnotationsScanner#isKnownEmpty(java.lang.reflect.AnnotatedElement, org.springframework.core.annotation.MergedAnnotations.SearchStrategy)}
	 * 中被调用
	 *
	 * @param source
	 * @param searchStrategy
	 * @return
	 */
	private static boolean isWithoutHierarchy(AnnotatedElement source, SearchStrategy searchStrategy) {
		if (source == Object.class) {
			// 如果传入的对象是Object.class返回true
			return true;
		}
		if (source instanceof Class) {
			Class<?> sourceClass = (Class<?>) source;
			// 父class是Object.class,且没有实现任何接口
			boolean noSuperTypes = (sourceClass.getSuperclass() == Object.class &&
					sourceClass.getInterfaces().length == 0);
			return (searchStrategy == SearchStrategy.TYPE_HIERARCHY_AND_ENCLOSING_CLASSES ? noSuperTypes &&
					sourceClass.getEnclosingClass() == null : noSuperTypes);
		}
		if (source instanceof Method) {
			Method sourceMethod = (Method) source;
			return (Modifier.isPrivate(sourceMethod.getModifiers()) ||
					isWithoutHierarchy(sourceMethod.getDeclaringClass(), searchStrategy));
		}
		return true;
	}

	static void clearCache() {
		declaredAnnotationCache.clear();
		baseTypeMethodsCache.clear();
	}

}
