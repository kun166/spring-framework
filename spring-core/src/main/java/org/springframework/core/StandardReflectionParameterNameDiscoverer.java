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

package org.springframework.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.springframework.lang.Nullable;

/**
 * {@link ParameterNameDiscoverer} implementation which uses JDK 8's reflection facilities
 * for introspecting parameter names (based on the "-parameters" compiler flag).
 *
 * <p>This is a key element of {@link DefaultParameterNameDiscoverer} where it is being
 * combined with {@link KotlinReflectionParameterNameDiscoverer} if Kotlin is present.
 *
 * @author Juergen Hoeller
 * @see java.lang.reflect.Method#getParameters()
 * @see java.lang.reflect.Parameter#getName()
 * @see KotlinReflectionParameterNameDiscoverer
 * @see DefaultParameterNameDiscoverer
 * @since 4.0
 */
public class StandardReflectionParameterNameDiscoverer implements ParameterNameDiscoverer {

	/**
	 * <p>
	 * {@link PrioritizedParameterNameDiscoverer#getParameterNames(java.lang.reflect.Method)}
	 * 中调用
	 * </p>
	 *
	 * @param method the method to find parameter names for
	 * @return
	 */
	@Override
	@Nullable
	public String[] getParameterNames(Method method) {
		return getParameterNames(method.getParameters());
	}

	@Override
	@Nullable
	public String[] getParameterNames(Constructor<?> ctor) {
		return getParameterNames(ctor.getParameters());
	}

	/**
	 * <p>
	 * {@link StandardReflectionParameterNameDiscoverer#getParameterNames(java.lang.reflect.Method)}
	 * 中调用
	 * </p>
	 * 自java8开始，可以通过反射得到方法的参数名，不过这有个条件：你必须手动在编译时开启-parameters 参数。
	 * 以IDEA为例，你需要在Preferences->Build,Execution,Deployment->Compiler->java Compiler 页面添加该编译选项。
	 * （具体可以查阅其他博主的文章，因为基本不用，这里就不啰嗦了）。
	 * https://www.cnblogs.com/chenss15060100790/p/16822266.html
	 *
	 * @param parameters
	 * @return
	 */
	@Nullable
	private String[] getParameterNames(Parameter[] parameters) {
		String[] parameterNames = new String[parameters.length];
		for (int i = 0; i < parameters.length; i++) {
			Parameter param = parameters[i];
			if (!param.isNamePresent()) {
				return null;
			}
			parameterNames[i] = param.getName();
		}
		return parameterNames;
	}

}
