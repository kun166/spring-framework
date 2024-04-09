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

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.*;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.xml.BeanDefinitionParserDelegate;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 *
 * <p>Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 * @since 2.0
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * <p>
	 * {@link AbstractAutowireCapableBeanFactory#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
	 * 中调用
	 * </p>
	 *
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * <p>
	 * {@link AbstractAutowireCapableBeanFactory#autowireConstructor(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.reflect.Constructor[], java.lang.Object[])}
	 * 中调用
	 * </p>
	 *
	 * @param beanName     the name of the bean
	 * @param mbd          the merged bean definition for the bean
	 * @param chosenCtors  chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 *                     or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(String beanName,
										   RootBeanDefinition mbd,
										   @Nullable Constructor<?>[] chosenCtors,
										   @Nullable Object[] explicitArgs) {

		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		// 构造函数
		Constructor<?> constructorToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		// 构造函数传参？
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		} else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
			}
		}

		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			Constructor<?>[] candidates = chosenCtors;
			if (candidates == null) {
				Class<?> beanClass = mbd.getBeanClass();
				try {
					// 前者返回所有构造函数,或者返回public的构造函数
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				} catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
									"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				Constructor<?> uniqueCandidate = candidates[0];
				if (uniqueCandidate.getParameterCount() == 0) {
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			// Need to resolve the constructor.
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			ConstructorArgumentValues resolvedValues = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			} else {
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				resolvedValues = new ConstructorArgumentValues();
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			AutowireUtils.sortConstructors(candidates);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Constructor<?>> ambiguousConstructors = null;
			Deque<UnsatisfiedDependencyException> causes = null;

			for (Constructor<?> candidate : candidates) {
				int parameterCount = candidate.getParameterCount();

				if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					break;
				}
				if (parameterCount < minNrOfArgs) {
					continue;
				}

				ArgumentsHolder argsHolder;
				Class<?>[] paramTypes = candidate.getParameterTypes();
				if (resolvedValues != null) {
					try {
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
						if (paramNames == null) {
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					} catch (UnsatisfiedDependencyException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						if (causes == null) {
							causes = new ArrayDeque<>(1);
						}
						causes.add(ex);
						continue;
					}
				} else {
					// Explicit arguments given -> arguments length must match exactly.
					if (parameterCount != explicitArgs.length) {
						continue;
					}
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				if (typeDiffWeight < minTypeDiffWeight) {
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				} else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			if (constructorToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor on bean class [" + mbd.getBeanClassName() + "] " +
								"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			} else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found on bean class [" + mbd.getBeanClassName() + "] " +
								"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
								ambiguousConstructors);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	/**
	 * <p>
	 * {@link ConstructorResolver#autowireConstructor(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.reflect.Constructor[], java.lang.Object[])}
	 * 中调用
	 * </p>
	 *
	 * @param beanName
	 * @param mbd
	 * @param constructorToUse
	 * @param argsToUse
	 * @return
	 */
	private Object instantiate(String beanName,
							   RootBeanDefinition mbd,
							   Constructor<?> constructorToUse,
							   Object[] argsToUse) {

		try {
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
								strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse),
						this.beanFactory.getAccessControlContext());
			} else {
				return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}
		} catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via constructor failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * <p>
	 * {@link DefaultListableBeanFactory#isAutowireCandidate(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, org.springframework.beans.factory.config.DependencyDescriptor, org.springframework.beans.factory.support.AutowireCandidateResolver)}
	 * 中调用
	 * </p>
	 *
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		} else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				} else if (isParamMismatch(uniqueCandidate, candidate)) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		/**
		 * 如果此时uniqueCandidate!=null,说明只存在一个方法和{@link AbstractBeanDefinition#getFactoryMethodName()}相同
		 */
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	/**
	 * <p>
	 * {@link ConstructorResolver#resolveFactoryMethodIfPossible(org.springframework.beans.factory.support.RootBeanDefinition)}
	 * 中调用
	 * </p>
	 *
	 * @param uniqueCandidate
	 * @param candidate
	 * @return
	 */
	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 * <p>
	 * {@link ConstructorResolver#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
	 * 中调用
	 * </p>
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged((PrivilegedAction<Method[]>) () ->
					(mbd.isNonPublicAccessAllowed() ?
							ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods()));
		} else {
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * <p>
	 * {@link AbstractAutowireCapableBeanFactory#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
	 * 中调用
	 * </p>
	 * 这个方法挺复杂的，等有时间再看吧
	 * <p>
	 * {@link AbstractBeanDefinition#getFactoryMethodName()}不为null,走的这个分支
	 * <p>
	 * 从该方法的代码逻辑上来看,如果未有实际传参,
	 * 如果有多个候选方法,则形参和实参差距最小的那个会被选中。
	 * 更进一步，如果未通过xml中定义<constructor-arg>,则很大概率是参数最少的那个被选中,这个和构造器的选参方式不一致
	 *
	 * @param beanName     the name of the bean
	 * @param mbd          the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 *                     method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {

		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		/**
		 * 方法反射需要传入的第一个参数
		 */
		Object factoryBean;
		/**
		 * factory-method两种实现方式
		 * 1,通过静态方法(也就是factory-method配置的那个方法)返回一个对象作为bean
		 * 2,先声明一个普通的bean(factory-bean配置,姑且叫FactoryBean吧，不同点是不需要实现接口，需要指定方法。),
		 * 再指定一个方法(也就是factory-method配置的那个方法,一般就是普通方法了)返回一个对象作为bean
		 */
		Class<?> factoryClass;
		// 是否是静态方法？
		boolean isStatic;
		/**
		 * {@link AbstractBeanDefinition#getFactoryMethodName()}不为null，走到这个方法
		 * {@link BeanDefinitionParserDelegate#parseBeanDefinitionAttributes(org.w3c.dom.Element, java.lang.String, org.springframework.beans.factory.config.BeanDefinition, org.springframework.beans.factory.support.AbstractBeanDefinition)}
		 * https://blog.csdn.net/Kaiser__/article/details/136532780
		 * 这个factoryBeanName,定义的是FactoryBean的 bean name
		 */
		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			// 如果factoryBeanName和beanName相同,抛异常
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			// 先通过factoryBeanName，获取FactoryBean
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				// 如果该bean是单例模式，且已经创建了，则抛异常
				throw new ImplicitlyAppearedSingletonException();
			}
			// 建立factoryBean和当前bean的依赖关系。
			this.beanFactory.registerDependentBean(factoryBeanName, beanName);
			// 获取factoryBean的class
			factoryClass = factoryBean.getClass();
			isStatic = false;
		} else {
			// It's a static factory method on the bean class.
			// 通过静态方法实现的
			/**
			 * 通过自身静态方法创建bean
			 */
			if (!mbd.hasBeanClass()) {
				// 如果没有配置bean的class，则抛异常
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			factoryBean = null;
			// factoryClass是其自身
			factoryClass = mbd.getBeanClass();
			// 通过静态方法为true
			isStatic = true;
		}

		// 创建bean的method
		Method factoryMethodToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		// 参数?
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			// 如果传了参数
			argsToUse = explicitArgs;
		} else {
			// 否则，就得自己构造参数
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				// 先取缓存中的 method。
				// 如果缓存中有值了，说明不是第一次创建，这里的缓存值是通过下面的逻辑缓存的
				/**
				 * 下面这些缓存,是从这个方法的最后缓存的,是通过调用
				 * {@link ArgumentsHolder#storeCache(org.springframework.beans.factory.support.RootBeanDefinition, java.lang.reflect.Executable)}
				 * 方法缓存的
				 */
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					// 再取缓存中的 已经处理过的参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						// 否则取缓存中 准备使用的参数？
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				// 转换参数值
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
			}
		}

		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			// 第一次进来，缓存中未有缓存数据
			// 上面的缓存是从这里缓存的
			// 获取要使用的class,如果是CGLIB_CLASS,则获取父类
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// 方法候选者
			List<Method> candidates = null;
			if (mbd.isFactoryMethodUnique) {
				// FactoryMethod唯一？
				if (factoryMethodToUse == null) {
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				if (factoryMethodToUse != null) {
					candidates = Collections.singletonList(factoryMethodToUse);
				}
			}
			if (candidates == null) {
				// 需要初始化
				candidates = new ArrayList<>();
				/**
				 * 包括自身及父类的所有方法(公共的或者私有的)
				 * 外加 实现的接口中，定义的static或者default方法
				 */
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				for (Method candidate : rawCandidates) {
					if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
						/**
						 * 两个条件,
						 * 1,方法的static标识要和需要的一致
						 * 2,方法名称,要和需要的一致
						 */
						candidates.add(candidate);
					}
				}
			}

			if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				/**
				 * 1,候选方法只有一个
				 * 2,未传递方法参数
				 * 3,BeanDefinition未记录参数(即xml未定义<constructor-arg></constructor-arg>)
				 */
				Method uniqueCandidate = candidates.get(0);
				if (uniqueCandidate.getParameterCount() == 0) {
					/**
					 * 方法无参
					 */
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					/**
					 * 注意这个地方:
					 * 如果方法是静态的
					 * {@link Method#invoke(java.lang.Object, java.lang.Object...)}
					 * 则第一个参数，可以不传。即factoryBean可以是null
					 */
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			if (candidates.size() > 1) {  // explicitly skip immutable singletonList
				/**
				 * 1,先按方法是否是public排序:不是public的排在前面
				 * 2,再按方法的参数数量排序:少的排在前面,多的排在后面
				 */
				candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
			}

			ConstructorArgumentValues resolvedValues = null;
			/**
			 * 指定构造方法是{@link AutowireCapableBeanFactory#AUTOWIRE_CONSTRUCTOR}
			 */
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			// 模棱两可的,有歧义的
			Set<Method> ambiguousFactoryMethods = null;

			// 参数长度?
			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			} else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				//我们没有以编程方式传递的参数，因此我们需要解决
				//在bean定义中的构造函数参数中指定的参数。
				if (mbd.hasConstructorArgumentValues()) {
					/**
					 * 配置了{@link AbstractBeanDefinition#constructorArgumentValues}
					 * 即xml中定义了<constructor-arg>
					 */
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					/**
					 * 方法里面把cargs定义的参数，转成实际的值，放到了resolvedValues里
					 */
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				} else {
					/**
					 * xml中未定义<constructor-arg>
					 * 这种情况下,resolvedValues 为null
					 */
					minNrOfArgs = 0;
				}
			}

			/**
			 * 双向链表
			 */
			Deque<UnsatisfiedDependencyException> causes = null;

			for (Method candidate : candidates) {
				// 方法参数长度
				int parameterCount = candidate.getParameterCount();

				if (parameterCount >= minNrOfArgs) {
					// 方法参数长度不小于minNrOfArgs,小于的就被淘汰了
					ArgumentsHolder argsHolder;
					// 方法参数类型数组
					Class<?>[] paramTypes = candidate.getParameterTypes();
					if (explicitArgs != null) {
						// Explicit arguments given -> arguments length must match exactly.
						// 如果传递了参数,则方法参数续和传递的参数,长度匹配
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						argsHolder = new ArgumentsHolder(explicitArgs);
					} else {
						// 未传递参数
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							String[] paramNames = null;
							/**
							 * {@link org.springframework.core.DefaultParameterNameDiscoverer}
							 */
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								/**
								 * 获取方法参数的参数名称
								 */
								paramNames = pnd.getParameterNames(candidate);
							}
							/**
							 * 这个方法很重要
							 */
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						} catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							if (causes == null) {
								causes = new ArrayDeque<>(1);
							}
							causes.add(ex);
							continue;
						}
					}

					/**
					 * 计算该方法的实际传参和形参的类型差距
					 */
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					if (typeDiffWeight < minTypeDiffWeight) {
						/**
						 * 挑选差距最小的那个
						 */
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					else if (factoryMethodToUse != null
							&& typeDiffWeight == minTypeDiffWeight
							&& !mbd.isLenientConstructorResolution()
							&& paramTypes.length == factoryMethodToUse.getParameterCount()
							&& !Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						/**
						 * 两个候选方法的实际传参和形参差距一致
						 */
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						/**
						 * 把所有实际传参和形参差距最小,且相等的方法记录到ambiguousFactoryMethods里
						 */
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			if (factoryMethodToUse == null || argsToUse == null) {
				/**
				 * 说明没有找到候选方法
				 */
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				/**
				 * 说明也不是转换出错了,那就是candidates为空
				 */
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				if (explicitArgs != null) {
					/**
					 * 实际传参不为空
					 */
					for (Object arg : explicitArgs) {
						/**
						 * 遍历每个实际传参,如果不为null,就记录参数类型
						 */
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				} else if (resolvedValues != null) {
					/**
					 * xml中定义了<constructor-arg>
					 */
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					/**
					 * 添加所有定义的参数
					 */
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						/**
						 * 转换类型参数。
						 * 1,如果定义了类型,直接取类型
						 * 2,如果未定义类型,但是给予了值,则取值的类型
						 */
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				/**
				 * 组织字符串,抛异常
				 */
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found on class [" + factoryClass.getName() + "]: " +
								(mbd.getFactoryBeanName() != null ?
										"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
								"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
								"Check that a method with the specified name " +
								(minNrOfArgs > 0 ? "and arguments " : "") +
								"exists and that it is " +
								(isStatic ? "static" : "non-static") + ".");
			} else if (void.class == factoryMethodToUse.getReturnType()) {
				/**
				 * 找到了方法,但是方法返回值为void,抛异常
				 */
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() + "' on class [" +
								factoryClass.getName() + "]: needs to have a non-void return type!");
			} else if (ambiguousFactoryMethods != null) {
				/**
				 * 好吧，找到多个也抛异常了
				 */
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found on class [" + factoryClass.getName() + "] " +
								"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
								ambiguousFactoryMethods);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	/**
	 * <p>
	 * {@link ConstructorResolver#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
	 * 中调用
	 * </p>
	 *
	 * @param beanName
	 * @param mbd
	 * @param factoryBean
	 * @param factoryMethod
	 * @param args
	 * @return
	 */
	private Object instantiate(String beanName, RootBeanDefinition mbd,
							   @Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
								this.beanFactory.getInstantiationStrategy().instantiate(
										mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args),
						this.beanFactory.getAccessControlContext());
			} else {
				return this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
			}
		} catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via factory method failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * <p>This method is also used for handling invocations of static factory methods.
	 * <p>
	 * {@link ConstructorResolver#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
	 * 中调用
	 * </p>
	 *
	 * @param beanName       the name of the bean
	 * @param mbd            the merged bean definition for the bean
	 * @param bw
	 * @param cargs          {@link AbstractBeanDefinition#getConstructorArgumentValues()}
	 * @param resolvedValues
	 * @return
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
											ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		/**
		 * 取的是{@link ConstructorArgumentValues#indexedArgumentValues}数量+
		 * {@link ConstructorArgumentValues#genericArgumentValues}的数量
		 */
		int minNrOfArgs = cargs.getArgumentCount();

		/**
		 * {@link ConstructorArgumentValues#indexedArgumentValues}
		 * <constructor-arg>
		 * xml定义中,定义了index的参数
		 */
		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			int index = entry.getKey();
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			if (index + 1 > minNrOfArgs) {
				/**
				 * index从0开始,需要+1。
				 * 说明定义的index要大于上面的两个定义的参数之和了
				 */
				minNrOfArgs = index + 1;
			}
			/**
			 * 定义的参数类型
			 */
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			if (valueHolder.isConverted()) {
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			} else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		/**
		 * {@link ConstructorArgumentValues#genericArgumentValues}
		 * <constructor-arg>
		 * xml定义中,未定义index的参数
		 */
		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			if (valueHolder.isConverted()) {
				resolvedValues.addGenericArgumentValue(valueHolder);
			} else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 * 创建一个参数数组以通过反射调用构造函数或工厂方法，
	 * 给指定的构造方法,所需要的参数值数组
	 * <p>
	 * {@link ConstructorResolver#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
	 * 中调用
	 * </p>
	 *
	 * @param beanName       beanName
	 * @param mbd            megeredBeanDefinition
	 * @param resolvedValues
	 * @param bw
	 * @param paramTypes     executable的方法参数数组
	 * @param paramNames     方法参数名称数组
	 * @param executable     选定的方法
	 * @param autowiring     mbd的{@link AbstractBeanDefinition#autowireMode}是{@link AutowireCapableBeanFactory#AUTOWIRE_CONSTRUCTOR}
	 * @param fallback
	 * @return
	 * @throws UnsatisfiedDependencyException
	 */
	private ArgumentsHolder createArgumentArray(String beanName,
												RootBeanDefinition mbd,
												@Nullable ConstructorArgumentValues resolvedValues,
												BeanWrapper bw,
												Class<?>[] paramTypes,
												@Nullable String[] paramNames,
												Executable executable,
												boolean autowiring,
												boolean fallback) throws UnsatisfiedDependencyException {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		// 记录已经使用过了的ValueHolder
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		// 记录自动装配的bean name的set
		Set<String> allAutowiredBeanNames = new LinkedHashSet<>(paramTypes.length * 2);

		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			/**
			 * 遍历executable方法的方法参数数组
			 */
			Class<?> paramType = paramTypes[paramIndex];
			// 方法参数名称
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			if (resolvedValues != null) {
				/**
				 * 如果xml中配置了<constructor-arg>,则从配置项里查找
				 * 从带index的参数里面找
				 */
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					/**
					 * 从不带index的参数里面找
					 * 遍历不带index的每个参数,如果参数未被使用过且name为null且type为null,就会被选中
					 */
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			if (valueHolder != null) {
				/**
				 * 说明xml中配置了<constructor-arg>
				 */
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				usedValueHolders.add(valueHolder);
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				if (valueHolder.isConverted()) {
					convertedValue = valueHolder.getConvertedValue();
					args.preparedArguments[paramIndex] = convertedValue;
				} else {
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						/**
						 * {@link TypeConverterSupport#convertIfNecessary(java.lang.Object, java.lang.Class, org.springframework.core.MethodParameter)}
						 */
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					} catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
										ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
										"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					Object sourceHolder = valueHolder.getSource();
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
						Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
						args.resolveNecessary = true;
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			} else {
				/**
				 * 说明未定义<constructor-arg>,或者从<constructor-arg>里面没有获取到合适的配置项
				 */
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
									"] - did you specify the correct bean references as arguments?");
				}
				try {
					ConstructorDependencyDescriptor desc = new ConstructorDependencyDescriptor(methodParam, true);
					Set<String> autowiredBeanNames = new LinkedHashSet<>(2);
					Object arg = resolveAutowiredArgument(desc, paramType, beanName, autowiredBeanNames, converter, fallback);
					if (arg != null) {
						setShortcutIfPossible(desc, paramType, autowiredBeanNames);
					}
					allAutowiredBeanNames.addAll(autowiredBeanNames);
					args.rawArguments[paramIndex] = arg;
					args.arguments[paramIndex] = arg;
					args.preparedArguments[paramIndex] = desc;
					args.resolveNecessary = true;
				} catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}

		registerDependentBeans(executable, beanName, allAutowiredBeanNames);

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 * <p>
	 * {@link ConstructorResolver#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
	 * 中调用
	 * </p>
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
											  Executable executable, Object[] argsToResolve) {

		/**
		 * 先从beanFactory取TypeConverter,如果为空,就把BeanWrapper当做TypeConverter处理
		 */
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		// 获取方法的参数类型
		Class<?>[] paramTypes = executable.getParameterTypes();

		// 处理过的参数数组
		Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			// 第argIndex个参数位置的初始值
			Object argValue = argsToResolve[argIndex];
			// 第argIndex个参数位置的参数类型
			Class<?> paramType = paramTypes[argIndex];
			// 是否需要转换?
			boolean convertNecessary = false;
			if (argValue instanceof ConstructorDependencyDescriptor) {
				/**
				 * 参数类型是{@link ConstructorDependencyDescriptor}
				 */
				ConstructorDependencyDescriptor descriptor = (ConstructorDependencyDescriptor) argValue;
				try {
					argValue = resolveAutowiredArgument(descriptor, paramType, beanName,
							null, converter, true);
				} catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					Set<String> autowiredBeanNames = null;
					if (descriptor.hasShortcut()) {
						// Reset shortcut and try to re-resolve it in this thread...
						descriptor.setShortcut(null);
						autowiredBeanNames = new LinkedHashSet<>(2);
					}
					logger.debug("Failed to resolve cached argument", ex);
					argValue = resolveAutowiredArgument(descriptor, paramType, beanName,
							autowiredBeanNames, converter, true);
					if (autowiredBeanNames != null && !descriptor.hasShortcut()) {
						// We encountered as stale shortcut before, and the shortcut has
						// not been re-resolved by another thread in the meantime...
						if (argValue != null) {
							setShortcutIfPossible(descriptor, paramType, autowiredBeanNames);
						}
						registerDependentBeans(executable, beanName, autowiredBeanNames);
					}
				}
			} else if (argValue instanceof BeanMetadataElement) {
				/**
				 * 这个非常重要
				 */
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
				convertNecessary = true;
			} else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
				convertNecessary = true;
			}
			if (convertNecessary) {
				MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
				try {
					argValue = converter.convertIfNecessary(argValue, paramType, methodParam);
				} catch (TypeMismatchException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
									"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
				}
			}
			resolvedArgs[argIndex] = argValue;
		}
		return resolvedArgs;
	}

	private Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			} catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Resolve the specified argument which is supposed to be autowired.
	 * <p>
	 * {@link ConstructorResolver#resolvePreparedArguments(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, org.springframework.beans.BeanWrapper, java.lang.reflect.Executable, java.lang.Object[])}
	 * 中调用
	 * {@link ConstructorResolver#createArgumentArray(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, org.springframework.beans.factory.config.ConstructorArgumentValues, org.springframework.beans.BeanWrapper, java.lang.Class[], java.lang.String[], java.lang.reflect.Executable, boolean, boolean)}
	 * 中调用
	 * </p>
	 */
	@Nullable
	Object resolveAutowiredArgument(DependencyDescriptor descriptor,
									Class<?> paramType,
									String beanName,
									@Nullable Set<String> autowiredBeanNames,
									TypeConverter typeConverter,
									boolean fallback) {

		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + descriptor);
			}
			return injectionPoint;
		}

		try {
			/**
			 * 传递的参数是{@link DependencyDescriptor}
			 * 但是方法要求的参数不是{@link InjectionPoint}
			 */
			return this.beanFactory.resolveDependency(descriptor, beanName, autowiredBeanNames, typeConverter);
		} catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		} catch (NoSuchBeanDefinitionException ex) {
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				if (paramType.isArray()) {
					return Array.newInstance(paramType.getComponentType(), 0);
				} else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				} else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			throw ex;
		}
	}

	/**
	 * <p>
	 * {@link ConstructorResolver#createArgumentArray(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, org.springframework.beans.factory.config.ConstructorArgumentValues, org.springframework.beans.BeanWrapper, java.lang.Class[], java.lang.String[], java.lang.reflect.Executable, boolean, boolean)}
	 * 中调用
	 * </p>
	 *
	 * @param descriptor
	 * @param paramType
	 * @param autowiredBeanNames
	 */
	private void setShortcutIfPossible(ConstructorDependencyDescriptor descriptor,
									   Class<?> paramType,
									   Set<String> autowiredBeanNames) {

		if (autowiredBeanNames.size() == 1) {
			String autowiredBeanName = autowiredBeanNames.iterator().next();
			if (this.beanFactory.containsBean(autowiredBeanName) &&
					this.beanFactory.isTypeMatch(autowiredBeanName, paramType)) {
				descriptor.setShortcut(autowiredBeanName);
			}
		}
	}

	/**
	 * <p>
	 * {@link ConstructorResolver#createArgumentArray(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, org.springframework.beans.factory.config.ConstructorArgumentValues, org.springframework.beans.BeanWrapper, java.lang.Class[], java.lang.String[], java.lang.reflect.Executable, boolean, boolean)}
	 * 中调用
	 * </p>
	 *
	 * @param executable
	 * @param beanName
	 * @param autowiredBeanNames
	 */
	private void registerDependentBeans(
			Executable executable, String beanName, Set<String> autowiredBeanNames) {

		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName + "' via " +
						(executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}
	}

	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		} else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		/**
		 * 未经加工的,原始的参数列表
		 */
		public final Object[] rawArguments;

		/**
		 * 转化后的参数列表
		 */
		public final Object[] arguments;

		/**
		 * {@link ValueHolder#source}不为null，且为{@link ValueHolder}
		 * 从{@link ValueHolder#source}里获取的参数
		 */
		public final Object[] preparedArguments;

		/**
		 * {@link ValueHolder#source}不为null，且为{@link ValueHolder}
		 * 则设置为true
		 * {@link ArgumentsHolder#arguments}被转换,设置为true
		 */
		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		/**
		 * {@link ConstructorResolver#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
		 * 中调用
		 *
		 * @param args
		 */
		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		/**
		 * <p>
		 * {@link ConstructorResolver#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
		 * 中调用
		 * </p>
		 *
		 * @param paramTypes
		 * @return
		 */
		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			/**
			 * 计算转换后的实际参数和定义参数之间的差距
			 */
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			/**
			 * 计算转换前的实际参数和定义参数之间的差距
			 */
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			/**
			 * 从这里可以看出来,{@link ArgumentsHolder#rawArguments}也参与了比较
			 * 也就是说,如果转换前后的实际参数一致,则差距更小
			 */
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		/**
		 * <p>
		 * {@link ConstructorResolver#instantiateUsingFactoryMethod(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, java.lang.Object[])}
		 * 中调用
		 * </p>
		 *
		 * @param mbd
		 * @param constructorOrFactoryMethod
		 */
		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					mbd.preparedConstructorArguments = this.preparedArguments;
				} else {
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			} else {
				return null;
			}
		}
	}


	/**
	 * DependencyDescriptor marker for constructor arguments,
	 * for differentiating between a provided DependencyDescriptor instance
	 * and an internally built DependencyDescriptor for autowiring purposes.
	 */
	@SuppressWarnings("serial")
	private static class ConstructorDependencyDescriptor extends DependencyDescriptor {

		/**
		 * {@link ConstructorResolver#setShortcutIfPossible(org.springframework.beans.factory.support.ConstructorResolver.ConstructorDependencyDescriptor, java.lang.Class, java.util.Set)}
		 * 中赋值
		 */
		@Nullable
		private volatile String shortcut;

		/**
		 * <p>
		 * {@link ConstructorResolver#createArgumentArray(java.lang.String, org.springframework.beans.factory.support.RootBeanDefinition, org.springframework.beans.factory.config.ConstructorArgumentValues, org.springframework.beans.BeanWrapper, java.lang.Class[], java.lang.String[], java.lang.reflect.Executable, boolean, boolean)}
		 * 中调用
		 * </p>
		 *
		 * @param methodParameter
		 * @param required
		 */
		public ConstructorDependencyDescriptor(MethodParameter methodParameter, boolean required) {
			super(methodParameter, required);
		}

		/**
		 * <p>
		 * {@link ConstructorResolver#setShortcutIfPossible(org.springframework.beans.factory.support.ConstructorResolver.ConstructorDependencyDescriptor, java.lang.Class, java.util.Set)}
		 * 中调用
		 * </p>
		 *
		 * @param shortcut
		 */
		public void setShortcut(@Nullable String shortcut) {
			this.shortcut = shortcut;
		}

		public boolean hasShortcut() {
			return (this.shortcut != null);
		}

		/**
		 * <p>
		 * {@link DefaultListableBeanFactory#doResolveDependency(org.springframework.beans.factory.config.DependencyDescriptor, java.lang.String, java.util.Set, org.springframework.beans.TypeConverter)}
		 * 中调用
		 * </p>
		 *
		 * @param beanFactory the associated factory
		 * @return
		 */
		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			String shortcut = this.shortcut;
			return (shortcut != null ? beanFactory.getBean(shortcut, getDependencyType()) : null);
		}
	}

}
