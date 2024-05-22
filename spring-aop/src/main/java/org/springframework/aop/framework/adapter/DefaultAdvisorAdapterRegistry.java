/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.framework.adapter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.AfterReturningAdvice;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.ThrowsAdvice;
import org.springframework.aop.framework.DefaultAdvisorChainFactory;
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/**
 * Default implementation of the {@link AdvisorAdapterRegistry} interface.
 * Supports {@link org.aopalliance.intercept.MethodInterceptor},
 * {@link org.springframework.aop.MethodBeforeAdvice},
 * {@link org.springframework.aop.AfterReturningAdvice},
 * {@link org.springframework.aop.ThrowsAdvice}.
 *
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Juergen Hoeller
 */
@SuppressWarnings("serial")
public class DefaultAdvisorAdapterRegistry implements AdvisorAdapterRegistry, Serializable {

	/**
	 * 构造函数里初始化的:
	 * {@link MethodBeforeAdviceAdapter}
	 * {@link AfterReturningAdviceAdapter}
	 * {@link ThrowsAdviceAdapter}
	 */
	private final List<AdvisorAdapter> adapters = new ArrayList<>(3);


	/**
	 * Create a new DefaultAdvisorAdapterRegistry, registering well-known adapters.
	 */
	public DefaultAdvisorAdapterRegistry() {
		registerAdvisorAdapter(new MethodBeforeAdviceAdapter());
		registerAdvisorAdapter(new AfterReturningAdviceAdapter());
		registerAdvisorAdapter(new ThrowsAdviceAdapter());
	}


	/**
	 * <p>
	 * {@link AbstractAutoProxyCreator#buildAdvisors(java.lang.String, java.lang.Object[])}
	 * 中调用
	 * </p>
	 *
	 * @param adviceObject
	 * @return
	 * @throws UnknownAdviceTypeException
	 */
	@Override
	public Advisor wrap(Object adviceObject) throws UnknownAdviceTypeException {
		if (adviceObject instanceof Advisor) {
			return (Advisor) adviceObject;
		}
		if (!(adviceObject instanceof Advice)) {
			throw new UnknownAdviceTypeException(adviceObject);
		}
		Advice advice = (Advice) adviceObject;
		if (advice instanceof MethodInterceptor) {
			// So well-known it doesn't even need an adapter.
			return new DefaultPointcutAdvisor(advice);
		}
		for (AdvisorAdapter adapter : this.adapters) {
			// Check that it is supported.
			if (adapter.supportsAdvice(advice)) {
				return new DefaultPointcutAdvisor(advice);
			}
		}
		throw new UnknownAdviceTypeException(advice);
	}

	/**
	 * <p>
	 * {@link DefaultAdvisorChainFactory#getInterceptorsAndDynamicInterceptionAdvice(org.springframework.aop.framework.Advised, java.lang.reflect.Method, java.lang.Class)}
	 * 中调用
	 * </p>
	 * 根据传入对象advisor的{@link Advisor#getAdvice()},包装成{@link MethodInterceptor}数组。
	 * 一个{@link Advice}对象,可能实现了多个接口,每一个接口都会包装成{@link MethodInterceptor}。
	 * 实现了{@link MethodInterceptor}接口,则不需要包装,直接使用
	 * 实现了{@link AfterReturningAdvice},则适配包装成{@link AfterReturningAdviceInterceptor}
	 * 实现了{@link MethodBeforeAdvice},则适配包装成{@link MethodBeforeAdviceInterceptor}
	 * 实现了{@link ThrowsAdvice},则适配包装成{@link ThrowsAdviceInterceptor}
	 *
	 * @param advisor the Advisor to find an interceptor for
	 * @return
	 * @throws UnknownAdviceTypeException
	 */
	@Override
	public MethodInterceptor[] getInterceptors(Advisor advisor) throws UnknownAdviceTypeException {
		List<MethodInterceptor> interceptors = new ArrayList<>(3);
		Advice advice = advisor.getAdvice();
		/**
		 * 一个对象可能实现了{@link Advice}的多个接口
		 */
		if (advice instanceof MethodInterceptor) {
			/**
			 * 实现了{@link MethodInterceptor}接口,添加
			 */
			interceptors.add((MethodInterceptor) advice);
		}
		for (AdvisorAdapter adapter : this.adapters) {
			if (adapter.supportsAdvice(advice)) {
				/**
				 * 适配器模式:
				 * 如果是{@link AfterReturningAdvice},则适配{@link AfterReturningAdviceInterceptor}
				 * 如果是{@link MethodBeforeAdvice},则适配{@link MethodBeforeAdviceInterceptor}
				 * 如果是{@link ThrowsAdvice},则适配{@link ThrowsAdviceInterceptor}
				 */
				interceptors.add(adapter.getInterceptor(advisor));
			}
		}
		if (interceptors.isEmpty()) {
			throw new UnknownAdviceTypeException(advisor.getAdvice());
		}
		return interceptors.toArray(new MethodInterceptor[0]);
	}

	@Override
	public void registerAdvisorAdapter(AdvisorAdapter adapter) {
		this.adapters.add(adapter);
	}

}
