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

import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * {@link ClassMetadata} implementation that uses standard reflection
 * to introspect a given {@code Class}.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 2.5
 */
public class StandardClassMetadata implements ClassMetadata {

	/**
	 * {@link StandardClassMetadata#StandardClassMetadata(java.lang.Class)}
	 * 构造函数中赋值
	 * 要探测的bean的class
	 */
	private final Class<?> introspectedClass;


	/**
	 * Create a new StandardClassMetadata wrapper for the given Class.
	 * <p>
	 * {@link StandardAnnotationMetadata#StandardAnnotationMetadata(java.lang.Class, boolean)}
	 * 中调用
	 * </p>
	 *
	 * @param introspectedClass the Class to introspect
	 * @deprecated since 5.2 in favor of {@link StandardAnnotationMetadata}
	 */
	@Deprecated
	public StandardClassMetadata(Class<?> introspectedClass) {
		Assert.notNull(introspectedClass, "Class must not be null");
		this.introspectedClass = introspectedClass;
	}

	/**
	 * Return the underlying Class.
	 * <p>
	 * 返回根本的class
	 */
	public final Class<?> getIntrospectedClass() {
		return this.introspectedClass;
	}


	@Override
	public String getClassName() {
		return this.introspectedClass.getName();
	}

	/**
	 * 这个要学学，怎么判断一个class是否是接口
	 *
	 * @return
	 */
	@Override
	public boolean isInterface() {
		return this.introspectedClass.isInterface();
	}

	/**
	 * 要学学
	 *
	 * @return
	 */
	@Override
	public boolean isAnnotation() {
		return this.introspectedClass.isAnnotation();
	}

	/**
	 * 学习判断一个class是不是一个抽象类
	 *
	 * @return
	 */
	@Override
	public boolean isAbstract() {
		return Modifier.isAbstract(this.introspectedClass.getModifiers());
	}

	/**
	 * 判断class是否是一个final类
	 *
	 * @return
	 */
	@Override
	public boolean isFinal() {
		return Modifier.isFinal(this.introspectedClass.getModifiers());
	}

	/**
	 * 独立的？
	 *
	 * @return
	 */
	@Override
	public boolean isIndependent() {
		return (!hasEnclosingClass() ||
				(this.introspectedClass.getDeclaringClass() != null &&
						Modifier.isStatic(this.introspectedClass.getModifiers())));
	}

	@Override
	@Nullable
	public String getEnclosingClassName() {
		/**
		 * {@link Class#getEnclosingClass()}方法返回基础类的直接封闭类。
		 * 更通俗一点的说法就是,如果类A定义了一个内部类B,则B的该方法返回A。
		 * 注意:这个是直接封闭类。比如A内部定义了B，B内部定义了C，则C的该方法返回的是B
		 * <p>
		 * 方法里面也可以定义class……
		 * 如果class定义在方法里,{@link Class#getEnclosingClass()}会返回方法所属的类
		 * {@link Class#getDeclaringClass()}会返回null
		 */
		Class<?> enclosingClass = this.introspectedClass.getEnclosingClass();
		return (enclosingClass != null ? enclosingClass.getName() : null);
	}

	@Override
	@Nullable
	public String getSuperClassName() {
		Class<?> superClass = this.introspectedClass.getSuperclass();
		return (superClass != null ? superClass.getName() : null);
	}

	@Override
	public String[] getInterfaceNames() {
		Class<?>[] ifcs = this.introspectedClass.getInterfaces();
		String[] ifcNames = new String[ifcs.length];
		for (int i = 0; i < ifcs.length; i++) {
			ifcNames[i] = ifcs[i].getName();
		}
		return ifcNames;
	}

	@Override
	public String[] getMemberClassNames() {
		LinkedHashSet<String> memberClassNames = new LinkedHashSet<>(4);
		/**
		 * {@link Class#getDeclaredClasses()}方法返回的是
		 * 当前类定义的内部类数组
		 */
		for (Class<?> nestedClass : this.introspectedClass.getDeclaredClasses()) {
			memberClassNames.add(nestedClass.getName());
		}
		return StringUtils.toStringArray(memberClassNames);
	}

	@Override
	public boolean equals(@Nullable Object obj) {
		return ((this == obj) || ((obj instanceof StandardClassMetadata) &&
				getIntrospectedClass().equals(((StandardClassMetadata) obj).getIntrospectedClass())));
	}

	@Override
	public int hashCode() {
		return getIntrospectedClass().hashCode();
	}

	@Override
	public String toString() {
		return getClassName();
	}

}
