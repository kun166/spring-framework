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
import java.lang.reflect.Method;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ObjectUtils;

/**
 * Strategy used to determine annotations that act as containers for other
 * annotations. The {@link #standardRepeatables()} method provides a default
 * strategy that respects Java's {@link Repeatable @Repeatable} support and
 * should be suitable for most situations.
 *
 * <p>The {@link #of} method can be used to register relationships for
 * annotations that do not wish to use {@link Repeatable @Repeatable}.
 * <p>
 * 这应该是标注了{@link Repeatable}注解的容器？
 *
 * <p>To completely disable repeatable support use {@link #none()}.
 *
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 5.2
 */
public abstract class RepeatableContainers {

	static final Map<Class<? extends Annotation>, Object> cache = new ConcurrentReferenceHashMap<>();

	@Nullable
	private final RepeatableContainers parent;


	private RepeatableContainers(@Nullable RepeatableContainers parent) {
		this.parent = parent;
	}


	/**
	 * Add an additional explicit relationship between a contained and
	 * repeatable annotation.
	 *
	 * @param container  the container type
	 * @param repeatable the contained repeatable type
	 * @return a new {@link RepeatableContainers} instance
	 */
	public RepeatableContainers and(Class<? extends Annotation> container,
									Class<? extends Annotation> repeatable) {

		return new ExplicitRepeatableContainer(this, repeatable, container);
	}

	/**
	 * 整个{@link RepeatableContainers}应该就这一个方法有用了。
	 * 默认实现就是委托给父容器查找
	 * {@link StandardRepeatableContainers}重写了该方法
	 *
	 * @param annotation
	 * @return
	 */
	@Nullable
	Annotation[] findRepeatedAnnotations(Annotation annotation) {
		if (this.parent == null) {
			return null;
		}
		return this.parent.findRepeatedAnnotations(annotation);
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (other == this) {
			return true;
		}
		if (other == null || getClass() != other.getClass()) {
			return false;
		}
		return ObjectUtils.nullSafeEquals(this.parent, ((RepeatableContainers) other).parent);
	}

	@Override
	public int hashCode() {
		return ObjectUtils.nullSafeHashCode(this.parent);
	}


	/**
	 * Create a {@link RepeatableContainers} instance that searches using Java's
	 * {@link Repeatable @Repeatable} annotation.
	 *
	 * @return a {@link RepeatableContainers} instance
	 */
	public static RepeatableContainers standardRepeatables() {
		return StandardRepeatableContainers.INSTANCE;
	}

	/**
	 * Create a {@link RepeatableContainers} instance that uses predefined
	 * repeatable and container types.
	 *
	 * @param repeatable the contained repeatable annotation type
	 * @param container  the container annotation type or {@code null}. If specified,
	 *                   this annotation must declare a {@code value} attribute returning an array
	 *                   of repeatable annotations. If not specified, the container will be
	 *                   deduced by inspecting the {@code @Repeatable} annotation on
	 *                   {@code repeatable}.
	 * @return a {@link RepeatableContainers} instance
	 * @throws IllegalArgumentException         if the supplied container type is
	 *                                          {@code null} and the annotation type is not a repeatable annotation
	 * @throws AnnotationConfigurationException if the supplied container type
	 *                                          is not a properly configured container for a repeatable annotation
	 */
	public static RepeatableContainers of(Class<? extends Annotation> repeatable,
										  @Nullable Class<? extends Annotation> container) {

		return new ExplicitRepeatableContainer(null, repeatable, container);
	}

	/**
	 * Create a {@link RepeatableContainers} instance that does not expand any
	 * repeatable annotations.
	 *
	 * @return a {@link RepeatableContainers} instance
	 */
	public static RepeatableContainers none() {
		return NoRepeatableContainers.INSTANCE;
	}


	/**
	 * Standard {@link RepeatableContainers} implementation that searches using
	 * Java's {@link Repeatable @Repeatable} annotation.
	 */
	private static class StandardRepeatableContainers extends RepeatableContainers {

		private static final Object NONE = new Object();

		private static StandardRepeatableContainers INSTANCE = new StandardRepeatableContainers();

		StandardRepeatableContainers() {
			super(null);
		}

		/**
		 * <p>
		 * {@link AnnotationTypeMappings#addMetaAnnotationsToQueue(java.util.Deque, org.springframework.core.annotation.AnnotationTypeMapping)}
		 * * 中调用
		 * </p>
		 * <p>
		 * https://blog.csdn.net/a232884c/article/details/124827155
		 * 获取有注解{@link Repeatable}的方法返回注解数组
		 *
		 * @param annotation
		 * @return
		 */
		@Override
		@Nullable
		Annotation[] findRepeatedAnnotations(Annotation annotation) {
			Method method = getRepeatedAnnotationsMethod(annotation.annotationType());
			if (method != null) {
				/**
				 * 如果一个注解A上标注了{@link Repeatable},
				 * 则{@link Repeatable#value()}一定也是一个注解,我们暂计为B。
				 * 且该注解B一定有一个方法,该方法名字为value,无参,且返回值为A的数组。
				 * 参考:
				 * {@link org.springframework.context.annotation.ComponentScan}
				 * {@link org.springframework.context.annotation.ComponentScans}
				 *
				 * 可以参考:https://blog.csdn.net/a232884c/article/details/124827155
				 *
				 * 这个其实就是用了方法反射,从类似于{@link org.springframework.context.annotation.ComponentScans#value()}
				 * 上获取到{@link org.springframework.context.annotation.ComponentScan}数组
				 */
				return (Annotation[]) AnnotationUtils.invokeAnnotationMethod(method, annotation);
			}
			return super.findRepeatedAnnotations(annotation);
		}

		/**
		 * <p>
		 * {@link StandardRepeatableContainers#findRepeatedAnnotations(java.lang.annotation.Annotation)}
		 * 中调用
		 * </p>
		 *
		 * <p>
		 * https://blog.csdn.net/a232884c/article/details/124827155
		 * 如果annotationType注解如例子中的RepeaDemos注解
		 * 则返回该注解的value方法
		 * </p>
		 *
		 * @param annotationType 注解的class
		 * @return
		 */
		@Nullable
		private static Method getRepeatedAnnotationsMethod(Class<? extends Annotation> annotationType) {
			/**
			 *从缓存中取,如果缓存中没有,则通过{@link StandardRepeatableContainers#computeRepeatedAnnotationsMethod(java.lang.Class)}
			 * 创建
			 */
			Object result = cache.computeIfAbsent(annotationType,
					StandardRepeatableContainers::computeRepeatedAnnotationsMethod);
			return (result != NONE ? (Method) result : null);
		}

		/**
		 * <p>
		 * {@link StandardRepeatableContainers#getRepeatedAnnotationsMethod(java.lang.Class)}
		 * 中调用
		 * </p>
		 * https://blog.csdn.net/a232884c/article/details/124827155
		 * 如果annotationType注解如例子中的RepeaDemos注解
		 * 则返回该注解的value方法
		 * </p>
		 *
		 * @param annotationType
		 * @return
		 */
		private static Object computeRepeatedAnnotationsMethod(Class<? extends Annotation> annotationType) {
			/**
			 * 获取annotationType的所有参数长度为0，且返回值不为void的方法
			 */
			AttributeMethods methods = AttributeMethods.forAnnotationType(annotationType);
			// 获取名称为"value"的方法
			Method method = methods.get(MergedAnnotation.VALUE);
			if (method != null) {
				// 获取返回类型
				Class<?> returnType = method.getReturnType();
				if (returnType.isArray()) {
					/**
					 * 如果一个注解A上标注了{@link Repeatable},
					 * 则{@link Repeatable#value()}一定也是一个注解,我们暂计为B。
					 * 且该注解B一定有一个方法,该方法名字为value,无参,且返回值为A的数组。
					 * 参考:
					 * {@link org.springframework.context.annotation.ComponentScan}
					 * {@link org.springframework.context.annotation.ComponentScans}
					 *
					 * 可以参考:https://blog.csdn.net/a232884c/article/details/124827155
					 */
					Class<?> componentType = returnType.getComponentType();
					if (Annotation.class.isAssignableFrom(componentType) &&
							componentType.isAnnotationPresent(Repeatable.class)) {
						return method;
					}
				}
			}
			return NONE;
		}
	}


	/**
	 * A single explicit mapping.
	 * 清晰，明确的
	 */
	private static class ExplicitRepeatableContainer extends RepeatableContainers {

		private final Class<? extends Annotation> repeatable;

		private final Class<? extends Annotation> container;

		private final Method valueMethod;

		ExplicitRepeatableContainer(@Nullable RepeatableContainers parent,
									Class<? extends Annotation> repeatable, @Nullable Class<? extends Annotation> container) {

			super(parent);
			Assert.notNull(repeatable, "Repeatable must not be null");
			if (container == null) {
				container = deduceContainer(repeatable);
			}
			Method valueMethod = AttributeMethods.forAnnotationType(container).get(MergedAnnotation.VALUE);
			try {
				if (valueMethod == null) {
					throw new NoSuchMethodException("No value method found");
				}
				Class<?> returnType = valueMethod.getReturnType();
				if (!returnType.isArray() || returnType.getComponentType() != repeatable) {
					throw new AnnotationConfigurationException("Container type [" +
							container.getName() +
							"] must declare a 'value' attribute for an array of type [" +
							repeatable.getName() + "]");
				}
			} catch (AnnotationConfigurationException ex) {
				throw ex;
			} catch (Throwable ex) {
				throw new AnnotationConfigurationException(
						"Invalid declaration of container type [" + container.getName() +
								"] for repeatable annotation [" + repeatable.getName() + "]",
						ex);
			}
			this.repeatable = repeatable;
			this.container = container;
			this.valueMethod = valueMethod;
		}

		private Class<? extends Annotation> deduceContainer(Class<? extends Annotation> repeatable) {
			Repeatable annotation = repeatable.getAnnotation(Repeatable.class);
			Assert.notNull(annotation, () -> "Annotation type must be a repeatable annotation: " +
					"failed to resolve container type for " + repeatable.getName());
			return annotation.value();
		}

		@Override
		@Nullable
		Annotation[] findRepeatedAnnotations(Annotation annotation) {
			if (this.container.isAssignableFrom(annotation.annotationType())) {
				return (Annotation[]) AnnotationUtils.invokeAnnotationMethod(this.valueMethod, annotation);
			}
			return super.findRepeatedAnnotations(annotation);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (!super.equals(other)) {
				return false;
			}
			ExplicitRepeatableContainer otherErc = (ExplicitRepeatableContainer) other;
			return (this.container.equals(otherErc.container) && this.repeatable.equals(otherErc.repeatable));
		}

		@Override
		public int hashCode() {
			int hashCode = super.hashCode();
			hashCode = 31 * hashCode + this.container.hashCode();
			hashCode = 31 * hashCode + this.repeatable.hashCode();
			return hashCode;
		}
	}


	/**
	 * No repeatable containers.
	 */
	private static class NoRepeatableContainers extends RepeatableContainers {

		private static NoRepeatableContainers INSTANCE = new NoRepeatableContainers();

		NoRepeatableContainers() {
			super(null);
		}
	}

}
