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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.annotation.AnnotationTypeMapping.MirrorSets.MirrorSet;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Provides mapping information for a single annotation (or meta-annotation) in
 * the context of a root annotation type.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @see AnnotationTypeMappings
 * @since 5.2
 */
final class AnnotationTypeMapping {

	private static final MirrorSet[] EMPTY_MIRROR_SETS = new MirrorSet[0];


	/**
	 * 参数annotationType这个注解归属的注解。也可以理解为父注解。如果传入的参数为空，则说明该注解是根注解。
	 */
	@Nullable
	private final AnnotationTypeMapping source;

	/**
	 * 根注解。即最开始的那个注解
	 */
	private final AnnotationTypeMapping root;

	/**
	 * 当前注解和根注解的距离
	 */
	private final int distance;

	/**
	 * 这个是本类最重要的属性，所有的其它属性围绕它产生。可以认为是数据库中的主键
	 */
	private final Class<? extends Annotation> annotationType;

	/**
	 * 这个是有序的,第一个是根注解的class,最后一个是当前注解的class。
	 * 中间的是串联起根注解的class和当前注解的class的注解class。
	 * 这个List串联起来根注解class和当前注解class的整个路径
	 */
	private final List<Class<? extends Annotation>> metaTypes;

	@Nullable
	private final Annotation annotation;

	/**
	 * 注解{@link AnnotationTypeMapping#annotationType}上的属性方法，即参数长度为0，且返回不为void的方法集合
	 */
	private final AttributeMethods attributes;

	private final MirrorSets mirrorSets;

	private final int[] aliasMappings;

	/**
	 * 记录的是当前mapping中属性方法名字和{@link AnnotationTypeMapping#root}属性方法名字相同……
	 * 其中下标是当前mapping{@link AnnotationTypeMapping#attributes}的下标，
	 * 值是{@link AnnotationTypeMapping#root}中{@link AnnotationTypeMapping#attributes}的下标
	 */
	private final int[] conventionMappings;

	private final int[] annotationValueMappings;

	private final AnnotationTypeMapping[] annotationValueSource;

	/**
	 * 注解{@link AnnotationTypeMapping#annotationType}上属性方法上，如果标注了{@link AliasFor}注解的对应关系
	 * 注意key是转换后的方法,value是原方法
	 * <p>
	 * 遍历{@link AnnotationTypeMapping#attributes}的每一个方法,
	 * 如果方法上标有{@link AliasFor}注解,
	 * 则获取{@link AliasFor}注解指定的方法为key,{@link AnnotationTypeMapping#attributes}记录的方法组成的List为value
	 */
	private final Map<Method, List<Method>> aliasedBy;

	private final boolean synthesizable;

	/**
	 * 通过{@link AliasFor}指向当前mapping的那些方法。这些方法可能属于当前mapping，也可能属于父mapping
	 * 比如当前mapping有一个方法A,还有一个方法B通过{@link AliasFor}指向A。
	 * 比如当前mapping有一个方法C,还有一个方法D通过{@link AliasFor}指向C。
	 * 然后A,B,C,D都会被记录下来
	 */
	private final Set<Method> claimedAliases = new HashSet<>();


	/**
	 * <p>
	 * {@link AnnotationTypeMappings#addIfPossible(java.util.Deque, org.springframework.core.annotation.AnnotationTypeMapping, java.lang.Class, java.lang.annotation.Annotation, java.util.Set)}
	 * 中调用
	 * </p>
	 * {@link AnnotationTypeMapping} 有层级关系。
	 * 解决了注解之上有注解的问题。
	 * 本类的初始属性就是传入的参数annotationType，由它产生了其它的属性。
	 *
	 * @param source
	 * @param annotationType         Annotation调用{@link Annotation#annotationType()}获取的class。这个是本类最重要的属性
	 * @param annotation
	 * @param visitedAnnotationTypes 访问过的AnnotationType
	 */
	AnnotationTypeMapping(@Nullable AnnotationTypeMapping source,
						  Class<? extends Annotation> annotationType,
						  @Nullable Annotation annotation,
						  Set<Class<? extends Annotation>> visitedAnnotationTypes) {
		/**
		 * 参数annotationType这个注解归属的注解。也可以理解为父注解。如果传入的参数为空，则说明该注解是根注解。
		 */
		this.source = source;
		/**
		 * 根注解
		 */
		this.root = (source != null ? source.getRoot() : this);
		/**
		 * 当前注解和根注解的距离
		 */
		this.distance = (source == null ? 0 : source.getDistance() + 1);
		/**
		 * 这个是本类最重要的属性，所有的其它属性围绕它产生。可以认为是数据库中的主键
		 */
		this.annotationType = annotationType;
		/**
		 * 这个是有序的,第一个是根注解的class,最后一个是当前注解的class。
		 * 中间的是串联起根注解的class和当前注解的class的注解class。
		 * 这个List串联起来根注解class和当前注解class的整个路径
		 */
		this.metaTypes = merge(
				source != null ? source.getMetaTypes() : null,
				annotationType);
		/**
		 * 注解本身
		 * {@link AnnotationTypeMapping#annotationType}记录的是注解的class
		 */
		this.annotation = annotation;
		/**
		 * 注解annotationType上的属性方法，即参数长度为0，且返回不为void的方法集合
		 */
		this.attributes = AttributeMethods.forAnnotationType(annotationType);
		this.mirrorSets = new MirrorSets();
		/**
		 * 创建一个int数组,数组长度为传入参数size，元素默认值都为-1。
		 */
		this.aliasMappings = filledIntArray(this.attributes.size());
		this.conventionMappings = filledIntArray(this.attributes.size());
		this.annotationValueMappings = filledIntArray(this.attributes.size());
		/**
		 * 这个数组待看
		 */
		this.annotationValueSource = new AnnotationTypeMapping[this.attributes.size()];
		/**
		 * 注解{@link AnnotationTypeMapping#annotationType}上属性方法上，如果标注了{@link AliasFor}注解的对应关系
		 * 注意key是转换后的方法,value是原方法
		 */
		this.aliasedBy = resolveAliasedForTargets();
		/**
		 * 代码太复杂了， 有时间再看吧
		 */
		processAliases();
		addConventionMappings();
		addConventionAnnotationValues();
		this.synthesizable = computeSynthesizableFlag(visitedAnnotationTypes);
	}


	/**
	 * {@link AnnotationTypeMapping#AnnotationTypeMapping(org.springframework.core.annotation.AnnotationTypeMapping, java.lang.Class, java.lang.annotation.Annotation, java.util.Set)}
	 * 中调用。
	 * 将existing和element合并到一个新的List里
	 *
	 * @param existing 已经存在的list
	 * @param element
	 * @param <T>
	 * @return
	 */
	private static <T> List<T> merge(@Nullable List<T> existing, T element) {
		if (existing == null) {
			/**
			 * 根注解初始化。
			 * 将根注解element加入其中
			 */
			return Collections.singletonList(element);
		}
		List<T> merged = new ArrayList<>(existing.size() + 1);
		merged.addAll(existing);
		merged.add(element);
		return Collections.unmodifiableList(merged);
	}

	/**
	 * <p>
	 * {@link AnnotationTypeMapping#AnnotationTypeMapping(org.springframework.core.annotation.AnnotationTypeMapping, java.lang.Class, java.lang.annotation.Annotation, java.util.Set)}
	 * 中调用
	 * </p>
	 * 遍历{@link AnnotationTypeMapping#attributes}的每一个方法,
	 * 如果方法上标有{@link AliasFor}注解,
	 * 则获取{@link AliasFor}注解指定的方法为key,{@link AnnotationTypeMapping#attributes}记录的方法组成的List为value
	 *
	 * @return
	 */
	private Map<Method, List<Method>> resolveAliasedForTargets() {
		Map<Method, List<Method>> aliasedBy = new HashMap<>();
		for (int i = 0; i < this.attributes.size(); i++) {
			// 这个注解上的所有属性方法
			Method attribute = this.attributes.get(i);
			/**
			 * 获取属性方法上的{@link AliasFor}注解
			 * 参考:
			 * {@link org.springframework.stereotype.Service}
			 */
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			if (aliasFor != null) {
				/**
				 * 说明该属性方法上有{@link AliasFor}注解
				 */
				Method target = resolveAliasTarget(attribute, aliasFor);
				// 注意这个key是转换后的方法，value是原方法
				aliasedBy.computeIfAbsent(target, key -> new ArrayList<>()).add(attribute);
			}
		}
		return Collections.unmodifiableMap(aliasedBy);
	}


	/**
	 * {@link AnnotationTypeMapping#resolveAliasedForTargets()}中调用
	 *
	 * @param attribute
	 * @param aliasFor
	 * @return
	 */
	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor) {
		return resolveAliasTarget(attribute, aliasFor, true);
	}

	/**
	 * {@link AnnotationTypeMapping#resolveAliasTarget(java.lang.reflect.Method, org.springframework.core.annotation.AliasFor)}
	 * 中调用
	 *
	 * @param attribute      注解的属性方法
	 * @param aliasFor       该属性方法上的{@link AliasFor}注解
	 * @param checkAliasPair 传递了true。这个校验两个参数是否配对，即aliasFor是否标注在attribute上
	 * @return
	 */
	private Method resolveAliasTarget(Method attribute, AliasFor aliasFor, boolean checkAliasPair) {
		/**
		 * 关于{@link AliasFor}
		 * 可以参考下:https://blog.csdn.net/weixin_43888891/article/details/126962698
		 * https://blog.csdn.net/qq_41378597/article/details/129376891
		 *{@link AliasFor}的value和attribute是同一个意思，不能都有值
		 */
		if (StringUtils.hasText(aliasFor.value()) && StringUtils.hasText(aliasFor.attribute())) {
			throw new AnnotationConfigurationException(String.format(
					"In @AliasFor declared on %s, attribute 'attribute' and its alias 'value' " +
							"are present with values of '%s' and '%s', but only one is permitted.",
					AttributeMethods.describe(attribute), aliasFor.attribute(),
					aliasFor.value()));
		}
		Class<? extends Annotation> targetAnnotation = aliasFor.annotation();
		if (targetAnnotation == Annotation.class) {
			/**
			 * {@link AliasFor#annotation()}默认值。说明是和当前注解的其它属性方法互为别名,
			 * 走这个分支，说明方法属性返回的就是{@link AnnotationTypeMapping#annotationType}它本身
			 * 可以参考{@link AliasFor}它自身
			 */
			targetAnnotation = this.annotationType;
		}
		String targetAttributeName = aliasFor.attribute();
		if (!StringUtils.hasLength(targetAttributeName)) {
			// 如果attribute无值，就取value
			targetAttributeName = aliasFor.value();
		}
		if (!StringUtils.hasLength(targetAttributeName)) {
			/**
			 * 这个地方要特别特别的注意:
			 * 1,先取{@link AliasFor#attribute()}
			 * 2,再取{@link AliasFor#value()}
			 * 3,最后取{@link Method#getName()}
			 * 例子:{@link org.springframework.stereotype.Service}
			 * {@link org.springframework.stereotype.Service}和{@link org.springframework.stereotype.Component}
			 * 属性方法名称一样
			 *
			 * 下面的方法名称,不是类名称……
			 */
			targetAttributeName = attribute.getName();
		}

		/**
		 * 目的地注解上的方法
		 */
		Method target = AttributeMethods.forAnnotationType(targetAnnotation).get(targetAttributeName);
		if (target == null) {
			/**
			 * 这个地方可以参考{@link org.springframework.stereotype.Service}
			 */
			if (targetAnnotation == this.annotationType) {
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for '%s' which is not present.",
						AttributeMethods.describe(attribute), targetAttributeName));
			}
			throw new AnnotationConfigurationException(String.format(
					"%s is declared as an @AliasFor nonexistent %s.",
					StringUtils.capitalize(AttributeMethods.describe(attribute)),
					AttributeMethods.describe(targetAnnotation, targetAttributeName)));
		}
		if (target.equals(attribute)) {
			/**
			 * 两个方法为同一个方法……
			 */
			throw new AnnotationConfigurationException(String.format(
					"@AliasFor declaration on %s points to itself. " +
							"Specify 'annotation' to point to a same-named attribute on a meta-annotation.",
					AttributeMethods.describe(attribute)));
		}
		if (!isCompatibleReturnType(attribute.getReturnType(), target.getReturnType())) {
			// 两个方法的返回对象类型不一致
			throw new AnnotationConfigurationException(String.format(
					"Misconfigured aliases: %s and %s must declare the same return type.",
					AttributeMethods.describe(attribute),
					AttributeMethods.describe(target)));
		}
		if (isAliasPair(target) && checkAliasPair) {
			AliasFor targetAliasFor = target.getAnnotation(AliasFor.class);
			if (targetAliasFor != null) {
				Method mirror = resolveAliasTarget(target, targetAliasFor, false);
				if (!mirror.equals(attribute)) {
					/**
					 * 目的方法上也有{@link AliasFor}注解,
					 * 且该注解指定的方法却不是当前方法
					 */
					throw new AnnotationConfigurationException(String.format(
							"%s must be declared as an @AliasFor %s, not %s.",
							StringUtils.capitalize(AttributeMethods.describe(target)),
							AttributeMethods.describe(attribute), AttributeMethods.describe(mirror)));
				}
			}
		}
		return target;
	}

	/**
	 * {@link AnnotationTypeMapping#resolveAliasTarget(java.lang.reflect.Method, org.springframework.core.annotation.AliasFor, boolean)}
	 * 中调用
	 * <p>
	 * 返回目标方法的类归属,是否是本{@link AnnotationTypeMapping#annotationType}
	 * </p>
	 *
	 * @param target 目标方法
	 * @return
	 */
	private boolean isAliasPair(Method target) {
		return (this.annotationType == target.getDeclaringClass());
	}

	private boolean isCompatibleReturnType(Class<?> attributeType, Class<?> targetType) {
		/**
		 * {@link Class#getComponentType()}
		 * 说明：返回数组中元素的Class对象，如果不是Class对象那么返回null
		 */
		return (attributeType == targetType || attributeType == targetType.getComponentType());
	}

	/**
	 * <p>
	 * {@link AnnotationTypeMapping#AnnotationTypeMapping(org.springframework.core.annotation.AnnotationTypeMapping, java.lang.Class, java.lang.annotation.Annotation, java.util.Set)}
	 * 构造函数中调用
	 * </p>
	 */
	private void processAliases() {
		List<Method> aliases = new ArrayList<>();
		for (int i = 0; i < this.attributes.size(); i++) {
			/**
			 * 遍历该注解上的属性方法
			 * <p>
			 * aliases只记录当前方法属性
			 * 先清空aliases
			 */
			aliases.clear();
			// 把当前方法放到aliases里
			aliases.add(this.attributes.get(i));
			collectAliases(aliases);
			if (aliases.size() > 1) {
				/**
				 * 说明当前方法被标注了{@link AliasFor}注解的自身注解的其它方法指向,
				 * 或者是被标注了{@link AliasFor}注解的父注解的方法指向
				 */
				processAliases(i, aliases);
			}
		}
	}

	/**
	 * <p>
	 * {@link AnnotationTypeMapping#processAliases()}中调用
	 * </p>
	 * aliases的第一个元素可以称之为元方法。即后面的所有方法都通过{@link AliasFor}注解直接指向它,或者是间接执行它。
	 * 比如方法D通过{@link AliasFor}指向方法C，而方法C又通过{@link AliasFor}指向B。则D是间接指向B
	 *
	 * @param aliases
	 */
	private void collectAliases(List<Method> aliases) {
		// mapping刚开始设置为自己
		AnnotationTypeMapping mapping = this;
		while (mapping != null) {
			int size = aliases.size();
			for (int j = 0; j < size; j++) {
				/**
				 * {@link AnnotationTypeMapping#aliasedBy}记录的key是标注了{@link AliasFor}转化之后的Method
				 * 像{@link org.springframework.stereotype.Service},则下面的additional肯定就为空了
				 * 但是像{@link AliasFor}自身标注的这样，则转换后的方法也是本身的方法，就有值了
				 */
				List<Method> additional = mapping.aliasedBy.get(aliases.get(j));
				if (additional != null) {
					/**
					 * 走到这个分支,有两种情况:
					 * 1,{@link AliasFor}转换后的方法还是自身注解拥有的方法，当然并不表明当前方法有{@link AliasFor}注解。
					 * 2,{@link AliasFor}转换后的方法是子注解的方法,当下面代码遍历{@link AnnotationTypeMapping#source}的时候命中。
					 */
					aliases.addAll(additional);
				}
			}
			/**
			 * 从自身向父注解查找
			 */
			mapping = mapping.source;
		}
	}

	/**
	 * {@link AnnotationTypeMapping#processAliases()}中调用
	 *
	 * @param attributeIndex {@link AnnotationTypeMapping#attributes}中的方法下标
	 * @param aliases        aliases中记录的第一个方法,为attributeIndex下标指向的{@link AnnotationTypeMapping#attributes}方法,可以称之为元方法。
	 *                       剩余方法都通过{@link AliasFor}指向元方法。
	 */
	private void processAliases(int attributeIndex, List<Method> aliases) {
		/**
		 * 寻找根注解中第一个在aliases中的属性方法。有可能是元方法，有可能不是
		 */
		int rootAttributeIndex = getFirstRootAttributeIndex(aliases);
		AnnotationTypeMapping mapping = this;
		while (mapping != null) {
			if (rootAttributeIndex != -1 && mapping != this.root) {
				for (int i = 0; i < mapping.attributes.size(); i++) {
					/**
					 * {@link AnnotationTypeMapping#root}中有被{@link AliasFor}指向的方法,
					 * 且当前mapping不是{@link AnnotationTypeMapping#root}
					 */
					if (aliases.contains(mapping.attributes.get(i))) {
						/**
						 * 1,在{@link AnnotationTypeMapping#root}找到了别名方法。
						 * 2,当前mapping不是{@link AnnotationTypeMapping#root}
						 * 设置当前mapping的当前方法(即下标为i的属性方法)别名为{@link AnnotationTypeMapping#root}的下标为rootAttributeIndex的方法
						 */
						mapping.aliasMappings[i] = rootAttributeIndex;
					}
				}
			}
			mapping.mirrorSets.updateFrom(aliases);
			mapping.claimedAliases.addAll(aliases);
			if (mapping.annotation != null) {
				int[] resolvedMirrors = mapping.mirrorSets.resolve(null,
						mapping.annotation, AnnotationUtils::invokeAnnotationMethod);
				for (int i = 0; i < mapping.attributes.size(); i++) {
					if (aliases.contains(mapping.attributes.get(i))) {
						this.annotationValueMappings[attributeIndex] = resolvedMirrors[i];
						this.annotationValueSource[attributeIndex] = mapping;
					}
				}
			}
			/**
			 * 向父注解逐级查找
			 */
			mapping = mapping.source;
		}
	}

	/**
	 * <p>
	 * {@link AnnotationTypeMapping#processAliases(int, java.util.List)}中调用
	 * </p>
	 * <p>
	 * 寻找根注解中第一个包含在aliases中的方法。
	 * 有可能是aliases中的元方法,即第一个方法;也有可能不是。
	 * 如果不是的话，则一定标有{@link AliasFor}注解
	 *
	 * @param aliases
	 * @return
	 */
	private int getFirstRootAttributeIndex(Collection<Method> aliases) {
		/**
		 * 从根注解开始查找
		 */
		AttributeMethods rootAttributes = this.root.getAttributes();
		for (int i = 0; i < rootAttributes.size(); i++) {
			if (aliases.contains(rootAttributes.get(i))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * <p>
	 * {@link AnnotationTypeMapping#AnnotationTypeMapping(org.springframework.core.annotation.AnnotationTypeMapping, java.lang.Class, java.lang.annotation.Annotation, java.util.Set)}
	 * 构造函数中调用
	 * </p>
	 */
	private void addConventionMappings() {
		if (this.distance == 0) {
			/**
			 * 如果当前注解是根注解,则退出
			 */
			return;
		}
		AttributeMethods rootAttributes = this.root.getAttributes();
		int[] mappings = this.conventionMappings;
		for (int i = 0; i < mappings.length; i++) {
			/**
			 * 当前属性方法的名字
			 */
			String name = this.attributes.get(i).getName();
			/**
			 * 根据名字去root属性方法中查找
			 */
			int mapped = rootAttributes.indexOf(name);
			if (!MergedAnnotation.VALUE.equals(name) && mapped != -1) {
				/**
				 * 方法名称不是"value",且在root属性方法中找到了……
				 */
				mappings[i] = mapped;
				MirrorSet mirrors = getMirrorSets().getAssigned(i);
				if (mirrors != null) {
					for (int j = 0; j < mirrors.size(); j++) {
						mappings[mirrors.getAttributeIndex(j)] = mapped;
					}
				}
			}
		}
	}

	/**
	 * <p>
	 * {@link AnnotationTypeMapping#AnnotationTypeMapping(org.springframework.core.annotation.AnnotationTypeMapping, java.lang.Class, java.lang.annotation.Annotation, java.util.Set)}
	 * 中调用
	 * </p>
	 */
	private void addConventionAnnotationValues() {
		for (int i = 0; i < this.attributes.size(); i++) {
			Method attribute = this.attributes.get(i);
			boolean isValueAttribute = MergedAnnotation.VALUE.equals(attribute.getName());
			AnnotationTypeMapping mapping = this;
			while (mapping != null && mapping.distance > 0) {
				int mapped = mapping.getAttributes().indexOf(attribute.getName());
				if (mapped != -1 && isBetterConventionAnnotationValue(i, isValueAttribute, mapping)) {
					this.annotationValueMappings[i] = mapped;
					this.annotationValueSource[i] = mapping;
				}
				mapping = mapping.source;
			}
		}
	}

	private boolean isBetterConventionAnnotationValue(int index, boolean isValueAttribute,
													  AnnotationTypeMapping mapping) {

		if (this.annotationValueMappings[index] == -1) {
			return true;
		}
		int existingDistance = this.annotationValueSource[index].distance;
		return !isValueAttribute && existingDistance > mapping.distance;
	}

	@SuppressWarnings("unchecked")
	private boolean computeSynthesizableFlag(Set<Class<? extends Annotation>> visitedAnnotationTypes) {
		// Track that we have visited the current annotation type.
		visitedAnnotationTypes.add(this.annotationType);

		// Uses @AliasFor for local aliases?
		for (int index : this.aliasMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Uses @AliasFor for attribute overrides in meta-annotations?
		if (!this.aliasedBy.isEmpty()) {
			return true;
		}

		// Uses convention-based attribute overrides in meta-annotations?
		for (int index : this.conventionMappings) {
			if (index != -1) {
				return true;
			}
		}

		// Has nested annotations or arrays of annotations that are synthesizable?
		if (getAttributes().hasNestedAnnotation()) {
			AttributeMethods attributeMethods = getAttributes();
			for (int i = 0; i < attributeMethods.size(); i++) {
				Method method = attributeMethods.get(i);
				Class<?> type = method.getReturnType();
				if (type.isAnnotation() || (type.isArray() && type.getComponentType().isAnnotation())) {
					Class<? extends Annotation> annotationType =
							(Class<? extends Annotation>) (type.isAnnotation() ? type : type.getComponentType());
					// Ensure we have not yet visited the current nested annotation type, in order
					// to avoid infinite recursion for JVM languages other than Java that support
					// recursive annotation definitions.
					if (visitedAnnotationTypes.add(annotationType)) {
						AnnotationTypeMapping mapping =
								AnnotationTypeMappings.forAnnotationType(annotationType, visitedAnnotationTypes).get(0);
						if (mapping.isSynthesizable()) {
							return true;
						}
					}
				}
			}
		}

		return false;
	}

	/**
	 * <p>
	 * {@link AnnotationTypeMappings#AnnotationTypeMappings(org.springframework.core.annotation.RepeatableContainers, org.springframework.core.annotation.AnnotationFilter, java.lang.Class, java.util.Set)}
	 * 中调用
	 * </p>
	 * Method called after all mappings have been set. At this point no further
	 * lookups from child mappings will occur.
	 */
	void afterAllMappingsSet() {
		validateAllAliasesClaimed();
		for (int i = 0; i < this.mirrorSets.size(); i++) {
			validateMirrorSet(this.mirrorSets.get(i));
		}
		this.claimedAliases.clear();
	}

	/**
	 *
	 */
	private void validateAllAliasesClaimed() {
		for (int i = 0; i < this.attributes.size(); i++) {
			// 遍历该注解的所有方法
			Method attribute = this.attributes.get(i);
			/**
			 * 获取该方法上的{@link AliasFor}注解
			 */
			AliasFor aliasFor = AnnotationsScanner.getDeclaredAnnotation(attribute, AliasFor.class);
			if (aliasFor != null && !this.claimedAliases.contains(attribute)) {
				Method target = resolveAliasTarget(attribute, aliasFor);
				throw new AnnotationConfigurationException(String.format(
						"@AliasFor declaration on %s declares an alias for %s which is not meta-present.",
						AttributeMethods.describe(attribute), AttributeMethods.describe(target)));
			}
		}
	}

	private void validateMirrorSet(MirrorSet mirrorSet) {
		Method firstAttribute = mirrorSet.get(0);
		Object firstDefaultValue = firstAttribute.getDefaultValue();
		for (int i = 1; i <= mirrorSet.size() - 1; i++) {
			Method mirrorAttribute = mirrorSet.get(i);
			Object mirrorDefaultValue = mirrorAttribute.getDefaultValue();
			if (firstDefaultValue == null || mirrorDefaultValue == null) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare default values.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
			if (!ObjectUtils.nullSafeEquals(firstDefaultValue, mirrorDefaultValue)) {
				throw new AnnotationConfigurationException(String.format(
						"Misconfigured aliases: %s and %s must declare the same default value.",
						AttributeMethods.describe(firstAttribute), AttributeMethods.describe(mirrorAttribute)));
			}
		}
	}

	/**
	 * Get the root mapping.
	 *
	 * @return the root mapping
	 */
	AnnotationTypeMapping getRoot() {
		return this.root;
	}

	/**
	 * Get the source of the mapping or {@code null}.
	 *
	 * @return the source of the mapping
	 */
	@Nullable
	AnnotationTypeMapping getSource() {
		return this.source;
	}

	/**
	 * Get the distance of this mapping.
	 *
	 * @return the distance of the mapping
	 */
	int getDistance() {
		return this.distance;
	}

	/**
	 * Get the type of the mapped annotation.
	 *
	 * @return the annotation type
	 */
	Class<? extends Annotation> getAnnotationType() {
		return this.annotationType;
	}

	List<Class<? extends Annotation>> getMetaTypes() {
		return this.metaTypes;
	}

	/**
	 * Get the source annotation for this mapping. This will be the
	 * meta-annotation, or {@code null} if this is the root mapping.
	 *
	 * @return the source annotation of the mapping
	 */
	@Nullable
	Annotation getAnnotation() {
		return this.annotation;
	}

	/**
	 * Get the annotation attributes for the mapping annotation type.
	 *
	 * @return the attribute methods
	 */
	AttributeMethods getAttributes() {
		return this.attributes;
	}

	/**
	 * Get the related index of an alias mapped attribute, or {@code -1} if
	 * there is no mapping. The resulting value is the index of the attribute on
	 * the root annotation that can be invoked in order to obtain the actual
	 * value.
	 *
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getAliasMapping(int attributeIndex) {
		return this.aliasMappings[attributeIndex];
	}

	/**
	 * Get the related index of a convention mapped attribute, or {@code -1}
	 * if there is no mapping. The resulting value is the index of the attribute
	 * on the root annotation that can be invoked in order to obtain the actual
	 * value.
	 *
	 * @param attributeIndex the attribute index of the source attribute
	 * @return the mapped attribute index or {@code -1}
	 */
	int getConventionMapping(int attributeIndex) {
		return this.conventionMappings[attributeIndex];
	}

	/**
	 * Get a mapped attribute value from the most suitable
	 * {@link #getAnnotation() meta-annotation}.
	 * <p>The resulting value is obtained from the closest meta-annotation,
	 * taking into consideration both convention and alias based mapping rules.
	 * For root mappings, this method will always return {@code null}.
	 *
	 * @param attributeIndex      the attribute index of the source attribute
	 * @param metaAnnotationsOnly if only meta annotations should be considered.
	 *                            If this parameter is {@code false} then aliases within the annotation will
	 *                            also be considered.
	 * @return the mapped annotation value, or {@code null}
	 */
	@Nullable
	Object getMappedAnnotationValue(int attributeIndex, boolean metaAnnotationsOnly) {
		int mappedIndex = this.annotationValueMappings[attributeIndex];
		if (mappedIndex == -1) {
			return null;
		}
		AnnotationTypeMapping source = this.annotationValueSource[attributeIndex];
		if (source == this && metaAnnotationsOnly) {
			return null;
		}
		return AnnotationUtils.invokeAnnotationMethod(source.attributes.get(mappedIndex), source.annotation);
	}

	/**
	 * Determine if the specified value is equivalent to the default value of the
	 * attribute at the given index.
	 *
	 * @param attributeIndex the attribute index of the source attribute
	 * @param value          the value to check
	 * @param valueExtractor the value extractor used to extract values from any
	 *                       nested annotations
	 * @return {@code true} if the value is equivalent to the default value
	 */
	boolean isEquivalentToDefaultValue(int attributeIndex, Object value, ValueExtractor valueExtractor) {
		Method attribute = this.attributes.get(attributeIndex);
		return isEquivalentToDefaultValue(attribute, value, valueExtractor);
	}

	/**
	 * Get the mirror sets for this type mapping.
	 *
	 * @return the attribute mirror sets
	 */
	MirrorSets getMirrorSets() {
		return this.mirrorSets;
	}

	/**
	 * Determine if the mapped annotation is <em>synthesizable</em>.
	 * <p>Consult the documentation for {@link MergedAnnotation#synthesize()}
	 * for an explanation of what is considered synthesizable.
	 *
	 * @return {@code true} if the mapped annotation is synthesizable
	 * @since 5.2.6
	 */
	boolean isSynthesizable() {
		return this.synthesizable;
	}


	/**
	 * 创建一个int数组,数组长度为传入参数size，元素默认值都为-1。
	 *
	 * @param size
	 * @return
	 */
	private static int[] filledIntArray(int size) {
		int[] array = new int[size];
		Arrays.fill(array, -1);
		return array;
	}

	private static boolean isEquivalentToDefaultValue(Method attribute, Object value,
													  ValueExtractor valueExtractor) {

		return areEquivalent(attribute.getDefaultValue(), value, valueExtractor);
	}

	private static boolean areEquivalent(@Nullable Object value, @Nullable Object extractedValue,
										 ValueExtractor valueExtractor) {

		if (ObjectUtils.nullSafeEquals(value, extractedValue)) {
			return true;
		}
		if (value instanceof Class && extractedValue instanceof String) {
			return areEquivalent((Class<?>) value, (String) extractedValue);
		}
		if (value instanceof Class[] && extractedValue instanceof String[]) {
			return areEquivalent((Class[]) value, (String[]) extractedValue);
		}
		if (value instanceof Annotation) {
			return areEquivalent((Annotation) value, extractedValue, valueExtractor);
		}
		return false;
	}

	private static boolean areEquivalent(Class<?>[] value, String[] extractedValue) {
		if (value.length != extractedValue.length) {
			return false;
		}
		for (int i = 0; i < value.length; i++) {
			if (!areEquivalent(value[i], extractedValue[i])) {
				return false;
			}
		}
		return true;
	}

	private static boolean areEquivalent(Class<?> value, String extractedValue) {
		return value.getName().equals(extractedValue);
	}

	private static boolean areEquivalent(Annotation annotation, @Nullable Object extractedValue,
										 ValueExtractor valueExtractor) {

		AttributeMethods attributes = AttributeMethods.forAnnotationType(annotation.annotationType());
		for (int i = 0; i < attributes.size(); i++) {
			Method attribute = attributes.get(i);
			Object value1 = AnnotationUtils.invokeAnnotationMethod(attribute, annotation);
			Object value2;
			if (extractedValue instanceof TypeMappedAnnotation) {
				value2 = ((TypeMappedAnnotation<?>) extractedValue).getValue(attribute.getName()).orElse(null);
			} else {
				value2 = valueExtractor.extract(attribute, extractedValue);
			}
			if (!areEquivalent(value1, value2, valueExtractor)) {
				return false;
			}
		}
		return true;
	}


	/**
	 * A collection of {@link MirrorSet} instances that provides details of all
	 * defined mirrors.
	 */
	class MirrorSets {

		/**
		 * 那些最少两个以上互为别名的方法，创建的MirrorSet数组
		 */
		private MirrorSet[] mirrorSets;

		/**
		 * 如果assigned[i]有值,说明{@link AnnotationTypeMapping#attributes}的第i个坐标是互为别名的方法
		 */
		private final MirrorSet[] assigned;

		MirrorSets() {
			this.assigned = new MirrorSet[attributes.size()];
			this.mirrorSets = EMPTY_MIRROR_SETS;
		}

		/**
		 * <p>
		 * {@link AnnotationTypeMapping#processAliases(int, java.util.List)}中调用
		 * </p>
		 *
		 * @param aliases
		 */
		void updateFrom(Collection<Method> aliases) {
			MirrorSet mirrorSet = null;
			int size = 0;
			int last = -1;
			for (int i = 0; i < attributes.size(); i++) {
				Method attribute = attributes.get(i);
				if (aliases.contains(attribute)) {
					size++;
					if (size > 1) {
						/**
						 * 注意,进这个分支的条件是size>1,
						 * 初始值是0,得++两次才能进来
						 */
						if (mirrorSet == null) {
							mirrorSet = new MirrorSet();
							/**
							 * 这last记录的仅仅是第一次碰到的那个坐标
							 */
							this.assigned[last] = mirrorSet;
						}
						/**
						 * 这个是最后的坐标
						 */
						this.assigned[i] = mirrorSet;
					}
					last = i;
				}
			}
			if (mirrorSet != null) {
				/**
				 * 说明当前mapping最少两个方法互为别名
				 */
				mirrorSet.update();
				Set<MirrorSet> unique = new LinkedHashSet<>(Arrays.asList(this.assigned));
				unique.remove(null);
				this.mirrorSets = unique.toArray(EMPTY_MIRROR_SETS);
			}
		}

		int size() {
			return this.mirrorSets.length;
		}

		MirrorSet get(int index) {
			return this.mirrorSets[index];
		}

		@Nullable
		MirrorSet getAssigned(int attributeIndex) {
			return this.assigned[attributeIndex];
		}

		int[] resolve(@Nullable Object source, @Nullable Object annotation, ValueExtractor valueExtractor) {
			int[] result = new int[attributes.size()];
			for (int i = 0; i < result.length; i++) {
				result[i] = i;
			}
			for (int i = 0; i < size(); i++) {
				MirrorSet mirrorSet = get(i);
				int resolved = mirrorSet.resolve(source, annotation, valueExtractor);
				for (int j = 0; j < mirrorSet.size; j++) {
					result[mirrorSet.indexes[j]] = resolved;
				}
			}
			return result;
		}


		/**
		 * A single set of mirror attributes.
		 */
		class MirrorSet {

			/**
			 * 当前MirrorSet对应的那个方法,在当前mapping中通过{@link AliasFor}互为别名的数量
			 */
			private int size;

			/**
			 * 下标从0往下顺延到{@link MirrorSet#size}
			 * 值为{@link MirrorSets#assigned}中等于当前对象this的下标
			 */
			private final int[] indexes = new int[attributes.size()];

			/**
			 * <p>
			 * {@link MirrorSets#updateFrom(java.util.Collection)}中调用
			 * </p>
			 */
			void update() {
				this.size = 0;
				Arrays.fill(this.indexes, -1);
				for (int i = 0; i < MirrorSets.this.assigned.length; i++) {
					if (MirrorSets.this.assigned[i] == this) {
						this.indexes[this.size] = i;
						this.size++;
					}
				}
			}

			<A> int resolve(@Nullable Object source, @Nullable A annotation, ValueExtractor valueExtractor) {
				int result = -1;
				Object lastValue = null;
				for (int i = 0; i < this.size; i++) {
					Method attribute = attributes.get(this.indexes[i]);
					Object value = valueExtractor.extract(attribute, annotation);
					boolean isDefaultValue = (value == null ||
							isEquivalentToDefaultValue(attribute, value, valueExtractor));
					if (isDefaultValue || ObjectUtils.nullSafeEquals(lastValue, value)) {
						if (result == -1) {
							result = this.indexes[i];
						}
						continue;
					}
					if (lastValue != null && !ObjectUtils.nullSafeEquals(lastValue, value)) {
						String on = (source != null) ? " declared on " + source : "";
						throw new AnnotationConfigurationException(String.format(
								"Different @AliasFor mirror values for annotation [%s]%s; attribute '%s' " +
										"and its alias '%s' are declared with values of [%s] and [%s].",
								getAnnotationType().getName(), on,
								attributes.get(result).getName(),
								attribute.getName(),
								ObjectUtils.nullSafeToString(lastValue),
								ObjectUtils.nullSafeToString(value)));
					}
					result = this.indexes[i];
					lastValue = value;
				}
				return result;
			}

			int size() {
				return this.size;
			}

			Method get(int index) {
				int attributeIndex = this.indexes[index];
				return attributes.get(attributeIndex);
			}

			int getAttributeIndex(int index) {
				return this.indexes[index];
			}
		}
	}

}
