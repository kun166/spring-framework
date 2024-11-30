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

package org.springframework.context.annotation;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.Location;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.context.annotation.DeferredImportSelector.Group;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.*;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.DefaultPropertySourceFactory;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReader;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.*;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Parses a {@link Configuration} class definition, populating a collection of
 * {@link ConfigurationClass} objects (parsing a single Configuration class may result in
 * any number of ConfigurationClass objects because one Configuration class may import
 * another using the {@link Import} annotation).
 *
 * <p>This class helps separate the concern of parsing the structure of a Configuration
 * class from the concern of registering BeanDefinition objects based on the content of
 * that model (with the exception of {@code @ComponentScan} annotations which need to be
 * registered immediately).
 *
 * <p>This ASM-based implementation avoids reflection and eager class loading in order to
 * interoperate effectively with lazy class loading in a Spring ApplicationContext.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @see ConfigurationClassBeanDefinitionReader
 * @since 3.0
 */
class ConfigurationClassParser {

	private static final PropertySourceFactory DEFAULT_PROPERTY_SOURCE_FACTORY = new DefaultPropertySourceFactory();

	/**
	 * 以"org.springframework.stereotype."开头的注解:
	 * {@link Component}
	 * {@link Controller}
	 * {@link Indexed}
	 * {@link Repository}
	 * {@link Service}
	 */
	private static final Predicate<String> DEFAULT_EXCLUSION_FILTER = className ->
			(className.startsWith("java.lang.annotation.") || className.startsWith("org.springframework.stereotype."));

	private static final Comparator<DeferredImportSelectorHolder> DEFERRED_IMPORT_COMPARATOR =
			(o1, o2) -> AnnotationAwareOrderComparator.INSTANCE.compare(o1.getImportSelector(), o2.getImportSelector());


	private final Log logger = LogFactory.getLog(getClass());

	/**
	 * {@link CachingMetadataReaderFactory}
	 */
	private final MetadataReaderFactory metadataReaderFactory;

	/**
	 * {@link FailFastProblemReporter}
	 */
	private final ProblemReporter problemReporter;

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	private final BeanDefinitionRegistry registry;

	private final ComponentScanAnnotationParser componentScanParser;

	private final ConditionEvaluator conditionEvaluator;

	/**
	 * {@link ConfigurationClassParser#processConfigurationClass(org.springframework.context.annotation.ConfigurationClass, java.util.function.Predicate)}
	 * 中添加数据
	 */
	private final Map<ConfigurationClass, ConfigurationClass> configurationClasses = new LinkedHashMap<>();

	private final Map<String, ConfigurationClass> knownSuperclasses = new HashMap<>();

	private final List<String> propertySourceNames = new ArrayList<>();

	private final ImportStack importStack = new ImportStack();

	private final DeferredImportSelectorHandler deferredImportSelectorHandler = new DeferredImportSelectorHandler();

	private final SourceClass objectSourceClass = new SourceClass(Object.class);


	/**
	 * Create a new {@link ConfigurationClassParser} instance that will be used
	 * to populate the set of configuration classes.
	 * {@link ConfigurationClassPostProcessor#processConfigBeanDefinitions(org.springframework.beans.factory.support.BeanDefinitionRegistry)}
	 * 中被调用
	 */
	public ConfigurationClassParser(MetadataReaderFactory metadataReaderFactory,
									ProblemReporter problemReporter, Environment environment, ResourceLoader resourceLoader,
									BeanNameGenerator componentScanBeanNameGenerator, BeanDefinitionRegistry registry) {

		this.metadataReaderFactory = metadataReaderFactory;
		this.problemReporter = problemReporter;
		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.registry = registry;
		/**
		 * componentScanBeanNameGenerator传递的是{@link AnnotationBeanNameGenerator}
		 */
		this.componentScanParser = new ComponentScanAnnotationParser(
				environment, resourceLoader, componentScanBeanNameGenerator, registry);
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}


	/**
	 * {@link ConfigurationClassPostProcessor#processConfigBeanDefinitions(org.springframework.beans.factory.support.BeanDefinitionRegistry)}
	 * 中被调用
	 *
	 * @param configCandidates 候选者{@link BeanDefinitionHolder}集合。
	 *                         有{@link ConfigurationClassUtils#candidateIndicators}注解，或者是方法上有{@link Bean}注解
	 */
	public void parse(Set<BeanDefinitionHolder> configCandidates) {
		for (BeanDefinitionHolder holder : configCandidates) {
			// 遍历候选者
			BeanDefinition bd = holder.getBeanDefinition();
			try {
				if (bd instanceof AnnotatedBeanDefinition) {
					// 走这个分支
					parse(((AnnotatedBeanDefinition) bd).getMetadata(), holder.getBeanName());
				} else if (bd instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) bd).hasBeanClass()) {
					parse(((AbstractBeanDefinition) bd).getBeanClass(), holder.getBeanName());
				} else {
					parse(bd.getBeanClassName(), holder.getBeanName());
				}
			} catch (BeanDefinitionStoreException ex) {
				throw ex;
			} catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to parse configuration class [" + bd.getBeanClassName() + "]", ex);
			}
		}

		this.deferredImportSelectorHandler.process();
	}

	/**
	 * {@link ConfigurationClassParser#parse(java.util.Set)}中调用
	 *
	 * @param className
	 * @param beanName
	 * @throws IOException
	 */
	protected final void parse(@Nullable String className, String beanName) throws IOException {
		Assert.notNull(className, "No bean class name for configuration class bean definition");
		MetadataReader reader = this.metadataReaderFactory.getMetadataReader(className);
		processConfigurationClass(new ConfigurationClass(reader, beanName), DEFAULT_EXCLUSION_FILTER);
	}

	/**
	 * {@link ConfigurationClassParser#parse(java.util.Set)}中调用
	 *
	 * @param clazz
	 * @param beanName
	 * @throws IOException
	 */
	protected final void parse(Class<?> clazz, String beanName) throws IOException {
		processConfigurationClass(new ConfigurationClass(clazz, beanName), DEFAULT_EXCLUSION_FILTER);
	}

	/**
	 * {@link ConfigurationClassParser#parse(java.util.Set)}中调用
	 * 处理{@link AnnotatedBeanDefinition}
	 *
	 * @param metadata
	 * @param beanName
	 * @throws IOException
	 */
	protected final void parse(AnnotationMetadata metadata, String beanName) throws IOException {
		processConfigurationClass(new ConfigurationClass(metadata, beanName), DEFAULT_EXCLUSION_FILTER);
	}

	/**
	 * Validate each {@link ConfigurationClass} object.
	 *
	 * @see ConfigurationClass#validate
	 */
	public void validate() {
		for (ConfigurationClass configClass : this.configurationClasses.keySet()) {
			configClass.validate(this.problemReporter);
		}
	}

	/**
	 * {@link ConfigurationClassPostProcessor#processConfigBeanDefinitions(org.springframework.beans.factory.support.BeanDefinitionRegistry)}
	 * 中调用
	 *
	 * @return
	 */
	public Set<ConfigurationClass> getConfigurationClasses() {
		return this.configurationClasses.keySet();
	}


	/**
	 * {@link ConfigurationClassParser#parse(org.springframework.core.type.AnnotationMetadata, java.lang.String)}
	 * 中调用
	 * {@link ConfigurationClassParser#processImports(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.Collection, java.util.function.Predicate, boolean)}
	 * 中调用
	 * {@link ConfigurationClassParser#processMemberClasses(ConfigurationClass, SourceClass, Predicate)}
	 *
	 * @param configClass
	 * @param filter      {@link ConfigurationClassParser#DEFAULT_EXCLUSION_FILTER}
	 * @throws IOException
	 */
	protected void processConfigurationClass(ConfigurationClass configClass, Predicate<String> filter) throws IOException {
		if (this.conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.PARSE_CONFIGURATION)) {
			/**
			 * 判断是否有{@link Conditional}注解,且不符合{@link Condition}条件，需要跳过
			 */
			return;
		}

		/**
		 * 又是一个缓存。
		 * 注意
		 * {@link ConfigurationClass#hashCode()}
		 * {@link ConfigurationClass#equals(Object)}
		 * 这个是根据className比较的
		 *
		 */
		ConfigurationClass existingClass = this.configurationClasses.get(configClass);
		if (existingClass != null) {
			/**
			 * 通过{@link Import}导入的，{@link ConfigurationClass#isImported()}返回true
			 * 最终处理的是{@link configurationClasses}保存的
			 */
			if (configClass.isImported()) {
				if (existingClass.isImported()) {
					existingClass.mergeImportedBy(configClass);
				}
				// Otherwise ignore new imported config class; existing non-imported class overrides it.
				// 否则，忽略新导入的配置类；现有的非导入类将覆盖它。
				return;
			} else {
				// Explicit bean definition found, probably replacing an import.
				// Let's remove the old one and go with the new one.
				/**
				 * 不是通过{@link Import}导入的，这种bean更重要
				 */
				this.configurationClasses.remove(configClass);
				this.knownSuperclasses.values().removeIf(configClass::equals);
			}
		}

		// Recursively process the configuration class and its superclass hierarchy.
		// 递归处理配置类及其超类层次结构。
		/**
		 * configClass 不变，sourceClass有可能是父类或者父类的父类，或者继续往上找
		 * A父类是B，B是Object，没有父类
		 * 则A进来的时候，在此会循环一次B
		 *
		 * 这里有一个例外,{@link ConfigurationClassParser#processMemberClasses(ConfigurationClass, SourceClass, Predicate)}
		 * 过来的,configClass变了。
		 */
		SourceClass sourceClass = asSourceClass(configClass, filter);
		do {
			sourceClass = doProcessConfigurationClass(configClass, sourceClass, filter);
		}
		while (sourceClass != null);

		/**
		 * 经过这个方法之后，每一个{@link ConfigurationClass}都会被添加到{@link ConfigurationClassParser#configurationClasses}中来。
		 * 然后被{@link ConfigurationClassPostProcessor#processConfigBeanDefinitions(org.springframework.beans.factory.support.BeanDefinitionRegistry)}
		 * 方法处理
		 */
		this.configurationClasses.put(configClass, configClass);
	}

	/**
	 * Apply processing and build a complete {@link ConfigurationClass} by reading the
	 * annotations, members and methods from the source class. This method can be called
	 * multiple times as relevant sources are discovered.
	 * 通过读取注释、成员和方法。可以调用此方法多次发现相关来源。
	 * 处理的地方有如下：
	 * 1,内部类
	 * 2,{@link org.springframework.context.annotation.PropertySource}注解
	 * 3,{@link ComponentScan}
	 * 4,{@link Import}注解
	 * 5,{@link ImportResource}
	 * 6,{@link Bean}
	 * <p>
	 * {@link ConfigurationClassParser#processConfigurationClass(org.springframework.context.annotation.ConfigurationClass, java.util.function.Predicate)}
	 * 中调用
	 * </p>
	 *
	 * @param configClass the configuration class being build。
	 *                    通过{@link ConfigurationClass#importedResources},{@link ConfigurationClass#beanMethods}等加载类
	 * @param sourceClass a source class,有可能和configClass表示相同的className,有可能是configClass表示的class的父类，父类的父类，依次向上
	 * @return the superclass, or {@code null} if none found or previously processed.父类，如果没有父类或者以前处理过，返回null
	 */
	@Nullable
	protected final SourceClass doProcessConfigurationClass(ConfigurationClass configClass,
															SourceClass sourceClass,
															Predicate<String> filter) throws IOException {

		if (configClass.getMetadata().isAnnotated(Component.class.getName())) {
			// Recursively process any member (nested) classes first
			/**
			 * 如果命中了{@link Component}
			 * 这个方法处理内部类，如果没有内部类，就不处理了
			 * 如果有内部类，然后递归再走到这个方法
			 */
			processMemberClasses(configClass, sourceClass, filter);
		}

		// Process any @PropertySource annotations
		/**
		 * 注意下面这两个注解的不同点:
		 * org.springframework.context.annotation.PropertySources
		 * org.springframework.context.annotation.PropertySource
		 * 参考:https://blog.csdn.net/qq_40837310/article/details/106587158
		 * 如果有{@link org.springframework.context.annotation.PropertySource}注解,
		 * 则转成{@link PropertySource}(注意不是注解了，已经解析成具体的类了)加入到{@link ConfigurationClassParser#environment}中
		 * 这个{@link org.springframework.context.annotation.PropertySource}注解,只是读取properties配置文件
		 */
		for (AnnotationAttributes propertySource : AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), PropertySources.class,
				org.springframework.context.annotation.PropertySource.class)) {
			if (this.environment instanceof ConfigurableEnvironment) {
				processPropertySource(propertySource);
			} else {
				logger.info("Ignoring @PropertySource annotation on [" + sourceClass.getMetadata().getClassName() +
						"]. Reason: Environment must implement ConfigurableEnvironment");
			}
		}

		// Process any @ComponentScan annotations
		/**
		 * 处理{@link ComponentScan}
		 * 注意：这个{@link ComponentScan}定义的bean，
		 * 已经通过{@link ClassPathBeanDefinitionScanner#registerBeanDefinition(org.springframework.beans.factory.config.BeanDefinitionHolder, org.springframework.beans.factory.support.BeanDefinitionRegistry)}
		 * 处理过了
		 */
		Set<AnnotationAttributes> componentScans = AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), ComponentScans.class, ComponentScan.class);
		if (!componentScans.isEmpty() &&
				!this.conditionEvaluator.shouldSkip(sourceClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN)) {
			for (AnnotationAttributes componentScan : componentScans) {
				// The config class is annotated with @ComponentScan -> perform the scan immediately
				/**
				 * 有{@link ComponentScan}标签，处理
				 */
				Set<BeanDefinitionHolder> scannedBeanDefinitions =
						this.componentScanParser.parse(componentScan, sourceClass.getMetadata().getClassName());
				// Check the set of scanned definitions for any further config classes and parse recursively if needed
				for (BeanDefinitionHolder holder : scannedBeanDefinitions) {
					BeanDefinition bdCand = holder.getBeanDefinition().getOriginatingBeanDefinition();
					if (bdCand == null) {
						bdCand = holder.getBeanDefinition();
					}
					if (ConfigurationClassUtils.checkConfigurationClassCandidate(bdCand, this.metadataReaderFactory)) {
						/**
						 * 继续递归寻找加载的{@link BeanDefinition}上标有{@link ComponentScan},然后解析
						 */
						parse(bdCand.getBeanClassName(), holder.getBeanName());
					}
				}
			}
		}

		// Process any @Import annotations
		/**
		 * 处理{@link Import}注解
		 * 可以参考:https://zhuanlan.zhihu.com/p/618418058
		 * 通过{@link Import}导入的BeanDefinition，也是一个新的ConfigurationClass
		 */
		processImports(configClass, sourceClass, getImports(sourceClass), filter, true);

		// Process any @ImportResource annotations
		/**
		 * 处理{@link ImportResource}
		 * 可以参考:https://zhuanlan.zhihu.com/p/660928548
		 * 添加到{@link ConfigurationClass#importedResources}供外层处理
		 */
		AnnotationAttributes importResource =
				AnnotationConfigUtils.attributesFor(sourceClass.getMetadata(), ImportResource.class);
		if (importResource != null) {
			String[] resources = importResource.getStringArray("locations");
			Class<? extends BeanDefinitionReader> readerClass = importResource.getClass("reader");
			for (String resource : resources) {
				// 如果有占位符，获取替换后的resource
				String resolvedResource = this.environment.resolveRequiredPlaceholders(resource);
				configClass.addImportedResource(resolvedResource, readerClass);
			}
		}

		// Process individual @Bean methods
		/**
		 * 处理{@link Bean}注解
		 * 添加到{@link ConfigurationClass#beanMethods}上，供外层处理
		 *
		 */
		Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(sourceClass);
		for (MethodMetadata methodMetadata : beanMethods) {
			configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
		}

		// Process default methods on interfaces
		processInterfaces(configClass, sourceClass);

		// Process superclass, if any
		if (sourceClass.getMetadata().hasSuperClass()) {
			String superclass = sourceClass.getMetadata().getSuperClassName();
			if (superclass != null && !superclass.startsWith("java") &&
					!this.knownSuperclasses.containsKey(superclass)) {
				this.knownSuperclasses.put(superclass, configClass);
				// Superclass found, return its annotation metadata and recurse
				return sourceClass.getSuperClass();
			}
		}

		// No superclass -> processing is complete
		return null;
	}

	/**
	 * Register member (nested) classes that happen to be configuration classes themselves.
	 * <p>
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 * </p>
	 */
	private void processMemberClasses(ConfigurationClass configClass, SourceClass sourceClass,
									  Predicate<String> filter) throws IOException {

		/**
		 * 返回sourceClass表示的Class，拥有的内部类
		 */
		Collection<SourceClass> memberClasses = sourceClass.getMemberClasses();
		if (!memberClasses.isEmpty()) {
			// 如果没有内部类，直接返回了
			List<SourceClass> candidates = new ArrayList<>(memberClasses.size());
			for (SourceClass memberClass : memberClasses) {
				if (ConfigurationClassUtils.isConfigurationCandidate(memberClass.getMetadata()) &&
						!memberClass.getMetadata().getClassName().equals(configClass.getMetadata().getClassName())) {
					candidates.add(memberClass);
				}
			}
			OrderComparator.sort(candidates);
			for (SourceClass candidate : candidates) {
				// 遍历符合条件的
				if (this.importStack.contains(configClass)) {
					this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
				} else {
					// 双端队列，防止循环
					this.importStack.push(configClass);
					try {
						processConfigurationClass(candidate.asConfigClass(configClass), filter);
					} finally {
						this.importStack.pop();
					}
				}
			}
		}
	}

	/**
	 * Register default methods on interfaces implemented by the configuration class.
	 * 在配置类实现的接口上,查找标注了{@link Bean}注解的default方法。
	 * <p>
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 * </p>
	 */
	private void processInterfaces(ConfigurationClass configClass, SourceClass sourceClass) throws IOException {
		/**
		 * 获取实现的所有接口
		 */
		for (SourceClass ifc : sourceClass.getInterfaces()) {

			Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(ifc);
			for (MethodMetadata methodMetadata : beanMethods) {
				if (!methodMetadata.isAbstract()) {
					// A default method or other concrete method on a Java 8+ interface...
					configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
				}
			}
			processInterfaces(configClass, ifc);
		}
	}

	/**
	 * Retrieve the metadata for all <code>@Bean</code> methods.
	 * 检索所有<code>@Bean</code>方法的元数据。
	 * 注意，返回的Set是有序的，重新进行了排序
	 * <p>
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 * </p>
	 */
	private Set<MethodMetadata> retrieveBeanMethodMetadata(SourceClass sourceClass) {
		AnnotationMetadata original = sourceClass.getMetadata();
		/**
		 * 获取有{@link Bean}注解的方法
		 */
		Set<MethodMetadata> beanMethods = original.getAnnotatedMethods(Bean.class.getName());
		if (beanMethods.size() > 1 && original instanceof StandardAnnotationMetadata) {
			// Try reading the class file via ASM for deterministic declaration order...
			// Unfortunately, the JVM's standard reflection returns methods in arbitrary
			// order, even between different runs of the same application on the same JVM.
			// 尝试通过ASM读取类文件以获得确定性声明顺序。。。
			// 不幸的是，JVM的标准反射以任意方式返回方法顺序，甚至在同一JVM上的同一应用程序的不同运行之间也是如此。
			try {
				AnnotationMetadata asm =
						this.metadataReaderFactory.getMetadataReader(original.getClassName()).getAnnotationMetadata();
				Set<MethodMetadata> asmMethods = asm.getAnnotatedMethods(Bean.class.getName());
				if (asmMethods.size() >= beanMethods.size()) {
					/**
					 * 用beanMethods初始化一个新的LinkedHashSet
					 */
					Set<MethodMetadata> candidateMethods = new LinkedHashSet<>(beanMethods);
					Set<MethodMetadata> selectedMethods = new LinkedHashSet<>(asmMethods.size());
					// 两层for循环
					for (MethodMetadata asmMethod : asmMethods) {
						for (Iterator<MethodMetadata> it = candidateMethods.iterator(); it.hasNext(); ) {
							MethodMetadata beanMethod = it.next();
							if (beanMethod.getMethodName().equals(asmMethod.getMethodName())) {
								selectedMethods.add(beanMethod);
								it.remove();
								break;
							}
						}
					}
					if (selectedMethods.size() == beanMethods.size()) {
						// All reflection-detected methods found in ASM method set -> proceed
						beanMethods = selectedMethods;
					}
				}
			} catch (IOException ex) {
				logger.debug("Failed to read class file via ASM for determining @Bean method order", ex);
				// No worries, let's continue with the reflection metadata we started with...
			}
		}
		return beanMethods;
	}


	/**
	 * Process the given <code>@PropertySource</code> annotation metadata.
	 * 处理{@link org.springframework.context.annotation.PropertySource}注解
	 * 将该{@link PropertySource}(注意不是注解了，已经解析成具体的类了)加入到{@link ConfigurationClassParser#environment}中
	 * <p>
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 * </p>
	 *
	 * @param propertySource metadata for the <code>@PropertySource</code> annotation found
	 * @throws IOException if loading a property source failed
	 */
	private void processPropertySource(AnnotationAttributes propertySource) throws IOException {
		// 注解的name
		String name = propertySource.getString("name");
		if (!StringUtils.hasLength(name)) {
			name = null;
		}
		// 注解的编码
		String encoding = propertySource.getString("encoding");
		if (!StringUtils.hasLength(encoding)) {
			encoding = null;
		}
		// 注解的value
		String[] locations = propertySource.getStringArray("value");
		Assert.isTrue(locations.length > 0, "At least one @PropertySource(value) location is required");
		boolean ignoreResourceNotFound = propertySource.getBoolean("ignoreResourceNotFound");

		Class<? extends PropertySourceFactory> factoryClass = propertySource.getClass("factory");
		PropertySourceFactory factory = (factoryClass == PropertySourceFactory.class ?
				DEFAULT_PROPERTY_SOURCE_FACTORY : BeanUtils.instantiateClass(factoryClass));

		for (String location : locations) {
			// 这是配置文件路径
			try {
				// 去掉占位符，获取真正的配置文件路径
				String resolvedLocation = this.environment.resolveRequiredPlaceholders(location);
				// 获取路径资源
				Resource resource = this.resourceLoader.getResource(resolvedLocation);
				/**
				 * 将该{@link PropertySource}(注意不是注解了，已经解析成具体的类了)加入到{@link ConfigurationClassParser#environment}中
				 */
				addPropertySource(factory.createPropertySource(name, new EncodedResource(resource, encoding)));
			} catch (IllegalArgumentException | FileNotFoundException | UnknownHostException | SocketException ex) {
				// Placeholders not resolvable or resource not found when trying to open it
				if (ignoreResourceNotFound) {
					if (logger.isInfoEnabled()) {
						logger.info("Properties location [" + location + "] not resolvable: " + ex.getMessage());
					}
				} else {
					throw ex;
				}
			}
		}
	}

	/**
	 * 把传入的propertySource添加到{@link ConfigurationClassParser#environment}的{@link AbstractEnvironment#propertySources}里。
	 * 根据{@link PropertySource#name},如果是否有重复添加，封装成{@link ResourcePropertySource}或者{@link CompositePropertySource}
	 * <p>
	 * {@link ConfigurationClassParser#processPropertySource(org.springframework.core.annotation.AnnotationAttributes)}
	 * 中调用
	 * </p>
	 *
	 * @param propertySource
	 */
	private void addPropertySource(PropertySource<?> propertySource) {
		String name = propertySource.getName();
		MutablePropertySources propertySources = ((ConfigurableEnvironment) this.environment).getPropertySources();

		if (this.propertySourceNames.contains(name)) {
			// 已经添加过了
			// We've already added a version, we need to extend it
			PropertySource<?> existing = propertySources.get(name);
			if (existing != null) {
				PropertySource<?> newSource = (propertySource instanceof ResourcePropertySource ?
						((ResourcePropertySource) propertySource).withResourceName() : propertySource);
				if (existing instanceof CompositePropertySource) {
					// 如果是合成的，也就是多个了，直接添加
					((CompositePropertySource) existing).addFirstPropertySource(newSource);
				} else {
					if (existing instanceof ResourcePropertySource) {
						existing = ((ResourcePropertySource) existing).withResourceName();
					}
					// 还是一个，则转成合成的，把两个都添加上去
					CompositePropertySource composite = new CompositePropertySource(name);
					composite.addPropertySource(newSource);
					composite.addPropertySource(existing);
					propertySources.replace(name, composite);
				}
				return;
			}
		}

		if (this.propertySourceNames.isEmpty()) {
			propertySources.addLast(propertySource);
		} else {
			String firstProcessed = this.propertySourceNames.get(this.propertySourceNames.size() - 1);
			propertySources.addBefore(firstProcessed, propertySource);
		}
		this.propertySourceNames.add(name);
	}


	/**
	 * Returns {@code @Import} class, considering all meta-annotations.
	 * 收集本sourceClass上的所有{@link Import}注解的{@link Import#value()}。并递归收集sourceClass上的所有注解标注的{@link Import}注解的{@link Import#value()}。
	 * <p>
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 * </p>
	 * 获取参数sourceClass上的注解{@link Import},{@link Import#value()}指定的class封装成的{@link SourceClass}集合
	 */
	private Set<SourceClass> getImports(SourceClass sourceClass) throws IOException {
		Set<SourceClass> imports = new LinkedHashSet<>();
		Set<SourceClass> visited = new LinkedHashSet<>();
		collectImports(sourceClass, imports, visited);
		return imports;
	}

	/**
	 * Recursively collect all declared {@code @Import} values. Unlike most
	 * meta-annotations it is valid to have several {@code @Import}s declared with
	 * different values; the usual process of returning values from the first
	 * meta-annotation on a class is not sufficient.
	 * <p>For example, it is common for a {@code @Configuration} class to declare direct
	 * {@code @Import}s in addition to meta-imports originating from an {@code @Enable}
	 * annotation.
	 * 收集本sourceClass上的所有{@link Import}注解的{@link Import#value()}。并递归收集sourceClass上的所有注解标注的{@link Import}注解的{@link Import#value()}。
	 * <p>
	 * {@link ConfigurationClassParser#getImports(org.springframework.context.annotation.ConfigurationClassParser.SourceClass)}
	 * 中调用
	 * </p>
	 *
	 * @param sourceClass the class to search
	 * @param imports     the imports collected so far
	 * @param visited     used to track visited classes to prevent infinite recursion
	 * @throws IOException if there is any problem reading metadata from the named class
	 */
	private void collectImports(SourceClass sourceClass, Set<SourceClass> imports, Set<SourceClass> visited)
			throws IOException {

		if (visited.add(sourceClass)) {
			// 为了避免循环加载，加载过的放入到visited中
			for (SourceClass annotation : sourceClass.getAnnotations()) {
				//  遍历sourceClass上的所有注解
				String annName = annotation.getMetadata().getClassName();
				if (!annName.equals(Import.class.getName())) {
					// 如果该注解名字不是org.springframework.context.annotation.Import
					// 继续收集父注解(即当前注解上的注解)，标注的所有注解。递归寻找
					collectImports(annotation, imports, visited);
				}
			}
			/**
			 * 收集本sourceClass上的所有{@link Import}注解的{@link Import#value()}。并递归收集sourceClass上的所有注解标注的{@link Import}注解的{@link Import#value()}。
			 */
			imports.addAll(sourceClass.getAnnotationAttributes(Import.class.getName(), "value"));
		}
	}

	/**
	 * {@link ConfigurationClassParser#doProcessConfigurationClass(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
	 * 中调用
	 *
	 * @param configClass
	 * @param currentSourceClass
	 * @param importCandidates        第二个参数currentSourceClass上标注{@link Import}的候选者
	 * @param exclusionFilter
	 * @param checkForCircularImports
	 */
	private void processImports(ConfigurationClass configClass,
								SourceClass currentSourceClass,
								Collection<SourceClass> importCandidates,
								Predicate<String> exclusionFilter,
								boolean checkForCircularImports) {

		if (importCandidates.isEmpty()) {
			return;
		}

		if (checkForCircularImports && isChainedImportOnStack(configClass)) {
			this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
		} else {
			// 将configClass添加到importStack中
			this.importStack.push(configClass);
			try {
				for (SourceClass candidate : importCandidates) {
					/**
					 * 遍历收集的所有{@link Import}注解的value
					 */
					if (candidate.isAssignable(ImportSelector.class)) {
						/**
						 * 如果{@link Import}注解的value是{@link ImportSelector}接口的实现类集合
						 */
						// Candidate class is an ImportSelector -> delegate to it to determine imports
						Class<?> candidateClass = candidate.loadClass();
						ImportSelector selector = ParserStrategyUtils.instantiateClass(candidateClass, ImportSelector.class,
								this.environment, this.resourceLoader, this.registry);
						/**
						 * 排除规则
						 */
						Predicate<String> selectorFilter = selector.getExclusionFilter();
						if (selectorFilter != null) {
							exclusionFilter = exclusionFilter.or(selectorFilter);
						}
						if (selector instanceof DeferredImportSelector) {
							this.deferredImportSelectorHandler.handle(configClass, (DeferredImportSelector) selector);
						} else {
							String[] importClassNames = selector.selectImports(currentSourceClass.getMetadata());
							Collection<SourceClass> importSourceClasses = asSourceClasses(importClassNames, exclusionFilter);
							/**
							 * 递归处理
							 */
							processImports(configClass, currentSourceClass, importSourceClasses, exclusionFilter, false);
						}
					} else if (candidate.isAssignable(ImportBeanDefinitionRegistrar.class)) {
						// Candidate class is an ImportBeanDefinitionRegistrar ->
						// delegate to it to register additional bean definitions
						/**
						 * 是{@link ImportBeanDefinitionRegistrar}
						 */
						Class<?> candidateClass = candidate.loadClass();
						ImportBeanDefinitionRegistrar registrar =
								ParserStrategyUtils.instantiateClass(candidateClass, ImportBeanDefinitionRegistrar.class,
										this.environment, this.resourceLoader, this.registry);
						configClass.addImportBeanDefinitionRegistrar(registrar, currentSourceClass.getMetadata());
					} else {
						// Candidate class not an ImportSelector or ImportBeanDefinitionRegistrar ->
						// process it as an @Configuration class
						this.importStack.registerImport(
								currentSourceClass.getMetadata(), candidate.getMetadata().getClassName());
						/**
						 * 如果是普通的class[],递归外层处理,即这是一个spring的{@link BeanDefinition},
						 * 需要像外层一样处理各种注解
						 */
						processConfigurationClass(candidate.asConfigClass(configClass), exclusionFilter);
					}
				}
			} catch (BeanDefinitionStoreException ex) {
				throw ex;
			} catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to process import candidates for configuration class [" +
								configClass.getMetadata().getClassName() + "]", ex);
			} finally {
				this.importStack.pop();
			}
		}
	}

	/**
	 * 校验传入的configClass是否存在循环依赖，已经加载过
	 * <p>
	 * {@link ConfigurationClassParser#processImports(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.Collection, java.util.function.Predicate, boolean)}
	 * 中调用
	 * </p>
	 *
	 * @param configClass
	 * @return
	 */
	private boolean isChainedImportOnStack(ConfigurationClass configClass) {
		if (this.importStack.contains(configClass)) {
			String configClassName = configClass.getMetadata().getClassName();
			AnnotationMetadata importingClass = this.importStack.getImportingClassFor(configClassName);
			while (importingClass != null) {
				if (configClassName.equals(importingClass.getClassName())) {
					return true;
				}
				importingClass = this.importStack.getImportingClassFor(importingClass.getClassName());
			}
		}
		return false;
	}

	ImportRegistry getImportRegistry() {
		return this.importStack;
	}


	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link ConfigurationClass}.
	 * <p>
	 * {@link ConfigurationClassParser#processConfigurationClass(org.springframework.context.annotation.ConfigurationClass, java.util.function.Predicate)}
	 * 中调用
	 * </p>
	 */
	private SourceClass asSourceClass(ConfigurationClass configurationClass, Predicate<String> filter) throws IOException {
		// 取出来AnnotationMetadata
		AnnotationMetadata metadata = configurationClass.getMetadata();
		if (metadata instanceof StandardAnnotationMetadata) {
			/**
			 *{@link AnnotatedBeanDefinition}的分支
			 */
			return asSourceClass(((StandardAnnotationMetadata) metadata).getIntrospectedClass(), filter);
		}
		/**
		 *其它分支
		 */
		return asSourceClass(metadata.getClassName(), filter);
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link Class}.
	 * <p>
	 * {@link ConfigurationClassParser#asSourceClass(org.springframework.context.annotation.ConfigurationClass, java.util.function.Predicate)}
	 * 中调用
	 * {@link SourceClass#getAnnotations()}中调用
	 * </p>
	 */
	SourceClass asSourceClass(@Nullable Class<?> classType, Predicate<String> filter) throws IOException {
		if (classType == null || filter.test(classType.getName())) {
			return this.objectSourceClass;
		}
		try {
			// Sanity test that we can reflectively read annotations,
			// including Class attributes; if not -> fall back to ASM
			for (Annotation ann : classType.getDeclaredAnnotations()) {
				AnnotationUtils.validateAnnotation(ann);
			}
			return new SourceClass(classType);
		} catch (Throwable ex) {
			// Enforce ASM via class name resolution
			return asSourceClass(classType.getName(), filter);
		}
	}

	/**
	 * Factory method to obtain a {@link SourceClass} collection from class names.
	 */
	private Collection<SourceClass> asSourceClasses(String[] classNames, Predicate<String> filter) throws IOException {
		List<SourceClass> annotatedClasses = new ArrayList<>(classNames.length);
		for (String className : classNames) {
			annotatedClasses.add(asSourceClass(className, filter));
		}
		return annotatedClasses;
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a class name.
	 * 通过传入的className构建一个{@link SourceClass}
	 * 如果是以"java"开头的系统类,则{@link SourceClass#metadata}通过{@link AnnotationMetadata#introspect(java.lang.Class)}创建
	 * 否则{@link SourceClass#metadata}通过ASM创建
	 * <p>
	 * {@link ConfigurationClassParser#asSourceClass(org.springframework.context.annotation.ConfigurationClass, java.util.function.Predicate)}
	 * 中调用
	 * </p>
	 *
	 * @param className 类名
	 * @param filter    {@link ConfigurationClassParser#DEFAULT_EXCLUSION_FILTER}
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings("deprecation")
	SourceClass asSourceClass(@Nullable String className, Predicate<String> filter) throws IOException {
		if (className == null || filter.test(className)) {
			return this.objectSourceClass;
		}
		if (className.startsWith("java")) {
			// Never use ASM for core java types
			try {
				// 如果是以java开头的，说明是系统的类
				return new SourceClass(ClassUtils.forName(className, this.resourceLoader.getClassLoader()));
			} catch (ClassNotFoundException ex) {
				throw new org.springframework.core.NestedIOException("Failed to load class [" + className + "]", ex);
			}
		}
		// asm解析
		return new SourceClass(this.metadataReaderFactory.getMetadataReader(className));
	}


	@SuppressWarnings("serial")
	private static class ImportStack extends ArrayDeque<ConfigurationClass> implements ImportRegistry {

		private final MultiValueMap<String, AnnotationMetadata> imports = new LinkedMultiValueMap<>();

		public void registerImport(AnnotationMetadata importingClass, String importedClass) {
			this.imports.add(importedClass, importingClass);
		}

		@Override
		@Nullable
		public AnnotationMetadata getImportingClassFor(String importedClass) {
			return CollectionUtils.lastElement(this.imports.get(importedClass));
		}

		@Override
		public void removeImportingClass(String importingClass) {
			for (List<AnnotationMetadata> list : this.imports.values()) {
				for (Iterator<AnnotationMetadata> iterator = list.iterator(); iterator.hasNext(); ) {
					if (iterator.next().getClassName().equals(importingClass)) {
						iterator.remove();
						break;
					}
				}
			}
		}

		/**
		 * Given a stack containing (in order)
		 * <ul>
		 * <li>com.acme.Foo</li>
		 * <li>com.acme.Bar</li>
		 * <li>com.acme.Baz</li>
		 * </ul>
		 * return "[Foo->Bar->Baz]".
		 */
		@Override
		public String toString() {
			StringJoiner joiner = new StringJoiner("->", "[", "]");
			for (ConfigurationClass configurationClass : this) {
				joiner.add(configurationClass.getSimpleName());
			}
			return joiner.toString();
		}
	}


	private class DeferredImportSelectorHandler {

		@Nullable
		private List<DeferredImportSelectorHolder> deferredImportSelectors = new ArrayList<>();

		/**
		 * Handle the specified {@link DeferredImportSelector}. If deferred import
		 * selectors are being collected, this registers this instance to the list. If
		 * they are being processed, the {@link DeferredImportSelector} is also processed
		 * immediately according to its {@link DeferredImportSelector.Group}.
		 *
		 * @param configClass    the source configuration class
		 * @param importSelector the selector to handle
		 */
		public void handle(ConfigurationClass configClass, DeferredImportSelector importSelector) {
			DeferredImportSelectorHolder holder = new DeferredImportSelectorHolder(configClass, importSelector);
			if (this.deferredImportSelectors == null) {
				DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
				handler.register(holder);
				handler.processGroupImports();
			} else {
				this.deferredImportSelectors.add(holder);
			}
		}

		public void process() {
			List<DeferredImportSelectorHolder> deferredImports = this.deferredImportSelectors;
			this.deferredImportSelectors = null;
			try {
				if (deferredImports != null) {
					DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
					deferredImports.sort(DEFERRED_IMPORT_COMPARATOR);
					deferredImports.forEach(handler::register);
					handler.processGroupImports();
				}
			} finally {
				this.deferredImportSelectors = new ArrayList<>();
			}
		}
	}


	private class DeferredImportSelectorGroupingHandler {

		private final Map<Object, DeferredImportSelectorGrouping> groupings = new LinkedHashMap<>();

		private final Map<AnnotationMetadata, ConfigurationClass> configurationClasses = new HashMap<>();

		public void register(DeferredImportSelectorHolder deferredImport) {
			Class<? extends Group> group = deferredImport.getImportSelector().getImportGroup();
			DeferredImportSelectorGrouping grouping = this.groupings.computeIfAbsent(
					(group != null ? group : deferredImport),
					key -> new DeferredImportSelectorGrouping(createGroup(group)));
			grouping.add(deferredImport);
			this.configurationClasses.put(deferredImport.getConfigurationClass().getMetadata(),
					deferredImport.getConfigurationClass());
		}

		public void processGroupImports() {
			for (DeferredImportSelectorGrouping grouping : this.groupings.values()) {
				Predicate<String> exclusionFilter = grouping.getCandidateFilter();
				grouping.getImports().forEach(entry -> {
					ConfigurationClass configurationClass = this.configurationClasses.get(entry.getMetadata());
					try {
						processImports(configurationClass, asSourceClass(configurationClass, exclusionFilter),
								Collections.singleton(asSourceClass(entry.getImportClassName(), exclusionFilter)),
								exclusionFilter, false);
					} catch (BeanDefinitionStoreException ex) {
						throw ex;
					} catch (Throwable ex) {
						throw new BeanDefinitionStoreException(
								"Failed to process import candidates for configuration class [" +
										configurationClass.getMetadata().getClassName() + "]", ex);
					}
				});
			}
		}

		private Group createGroup(@Nullable Class<? extends Group> type) {
			Class<? extends Group> effectiveType = (type != null ? type : DefaultDeferredImportSelectorGroup.class);
			return ParserStrategyUtils.instantiateClass(effectiveType, Group.class,
					ConfigurationClassParser.this.environment,
					ConfigurationClassParser.this.resourceLoader,
					ConfigurationClassParser.this.registry);
		}
	}


	private static class DeferredImportSelectorHolder {

		private final ConfigurationClass configurationClass;

		private final DeferredImportSelector importSelector;

		public DeferredImportSelectorHolder(ConfigurationClass configClass, DeferredImportSelector selector) {
			this.configurationClass = configClass;
			this.importSelector = selector;
		}

		public ConfigurationClass getConfigurationClass() {
			return this.configurationClass;
		}

		public DeferredImportSelector getImportSelector() {
			return this.importSelector;
		}
	}


	private static class DeferredImportSelectorGrouping {

		private final DeferredImportSelector.Group group;

		private final List<DeferredImportSelectorHolder> deferredImports = new ArrayList<>();

		DeferredImportSelectorGrouping(Group group) {
			this.group = group;
		}

		public void add(DeferredImportSelectorHolder deferredImport) {
			this.deferredImports.add(deferredImport);
		}

		/**
		 * Return the imports defined by the group.
		 *
		 * @return each import with its associated configuration class
		 */
		public Iterable<Group.Entry> getImports() {
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				this.group.process(deferredImport.getConfigurationClass().getMetadata(),
						deferredImport.getImportSelector());
			}
			return this.group.selectImports();
		}

		public Predicate<String> getCandidateFilter() {
			Predicate<String> mergedFilter = DEFAULT_EXCLUSION_FILTER;
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				Predicate<String> selectorFilter = deferredImport.getImportSelector().getExclusionFilter();
				if (selectorFilter != null) {
					mergedFilter = mergedFilter.or(selectorFilter);
				}
			}
			return mergedFilter;
		}
	}


	private static class DefaultDeferredImportSelectorGroup implements Group {

		private final List<Entry> imports = new ArrayList<>();

		@Override
		public void process(AnnotationMetadata metadata, DeferredImportSelector selector) {
			for (String importClassName : selector.selectImports(metadata)) {
				this.imports.add(new Entry(metadata, importClassName));
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			return this.imports;
		}
	}


	/**
	 * Simple wrapper that allows annotated source classes to be dealt with
	 * in a uniform manner, regardless of how they are loaded.
	 * <p>
	 * 允许处理带注释的源类的简单包装器
	 * 以统一的方式，不管它们是如何加载的。
	 */
	private class SourceClass implements Ordered {

		private final Object source;  // Class or MetadataReader

		private final AnnotationMetadata metadata;

		public SourceClass(Object source) {
			this.source = source;
			if (source instanceof Class) {
				this.metadata = AnnotationMetadata.introspect((Class<?>) source);
			} else {
				this.metadata = ((MetadataReader) source).getAnnotationMetadata();
			}
		}

		public final AnnotationMetadata getMetadata() {
			return this.metadata;
		}

		@Override
		public int getOrder() {
			Integer order = ConfigurationClassUtils.getOrder(this.metadata);
			return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
		}

		public Class<?> loadClass() throws ClassNotFoundException {
			if (this.source instanceof Class) {
				return (Class<?>) this.source;
			}
			String className = ((MetadataReader) this.source).getClassMetadata().getClassName();
			return ClassUtils.forName(className, resourceLoader.getClassLoader());
		}

		public boolean isAssignable(Class<?> clazz) throws IOException {
			if (this.source instanceof Class) {
				return clazz.isAssignableFrom((Class<?>) this.source);
			}
			return new AssignableTypeFilter(clazz).match((MetadataReader) this.source, metadataReaderFactory);
		}

		/**
		 * {@link ConfigurationClassParser#processImports(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.Collection, java.util.function.Predicate, boolean)}
		 * 中调用
		 *
		 * @param importedBy 标注{@link Import}注解的原始类？
		 * @return
		 */
		public ConfigurationClass asConfigClass(ConfigurationClass importedBy) {
			if (this.source instanceof Class) {
				return new ConfigurationClass((Class<?>) this.source, importedBy);
			}
			return new ConfigurationClass((MetadataReader) this.source, importedBy);
		}

		/**
		 * 返回当前{@link SourceClass#source}定义的Class，
		 * 包含的内部类。
		 * 不包括自身。
		 * <p>
		 * {@link ConfigurationClassParser#processMemberClasses(org.springframework.context.annotation.ConfigurationClass, org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.function.Predicate)}
		 * 中调用
		 * </p>
		 *
		 * @return
		 * @throws IOException
		 */
		public Collection<SourceClass> getMemberClasses() throws IOException {
			Object sourceToProcess = this.source;
			if (sourceToProcess instanceof Class) {
				/**
				 * 如果{@link SourceClass#source}是一个Class。
				 * 强转成一个Class
				 */
				Class<?> sourceClass = (Class<?>) sourceToProcess;
				try {
					/**
					 * getDeclaredClasses()方法用于返回反映对象定义的私有，受保护，公共和默认值的Class对象数组，但不包括子类或接口。
					 * 这个方法不返回本身，也不返回自身定义{}之外的Class,哪怕在同一个文件里。
					 * 注意:虽然在同一个文件里,如果class的定义在当前class的类定义{}之外，也不会返回
					 */
					Class<?>[] declaredClasses = sourceClass.getDeclaredClasses();
					List<SourceClass> members = new ArrayList<>(declaredClasses.length);
					for (Class<?> declaredClass : declaredClasses) {
						members.add(asSourceClass(declaredClass, DEFAULT_EXCLUSION_FILTER));
					}
					return members;
				} catch (NoClassDefFoundError err) {
					// getDeclaredClasses() failed because of non-resolvable dependencies
					// -> fall back to ASM below
					sourceToProcess = metadataReaderFactory.getMetadataReader(sourceClass.getName());
				}
			}

			// ASM-based resolution - safe for non-resolvable classes as well
			/**
			 * 如果不是Class，那就是一个{@link MetadataReader}。确切的说是一个{@link SimpleMetadataReader}
			 */
			MetadataReader sourceReader = (MetadataReader) sourceToProcess;
			String[] memberClassNames = sourceReader.getClassMetadata().getMemberClassNames();
			List<SourceClass> members = new ArrayList<>(memberClassNames.length);
			for (String memberClassName : memberClassNames) {
				try {
					members.add(asSourceClass(memberClassName, DEFAULT_EXCLUSION_FILTER));
				} catch (IOException ex) {
					// Let's skip it if it's not resolvable - we're just looking for candidates
					if (logger.isDebugEnabled()) {
						logger.debug("Failed to resolve member class [" + memberClassName +
								"] - not considering it as a configuration class candidate");
					}
				}
			}
			return members;
		}

		public SourceClass getSuperClass() throws IOException {
			if (this.source instanceof Class) {
				return asSourceClass(((Class<?>) this.source).getSuperclass(), DEFAULT_EXCLUSION_FILTER);
			}
			return asSourceClass(
					((MetadataReader) this.source).getClassMetadata().getSuperClassName(), DEFAULT_EXCLUSION_FILTER);
		}

		public Set<SourceClass> getInterfaces() throws IOException {
			Set<SourceClass> result = new LinkedHashSet<>();
			if (this.source instanceof Class) {
				Class<?> sourceClass = (Class<?>) this.source;
				for (Class<?> ifcClass : sourceClass.getInterfaces()) {
					result.add(asSourceClass(ifcClass, DEFAULT_EXCLUSION_FILTER));
				}
			} else {
				for (String className : this.metadata.getInterfaceNames()) {
					result.add(asSourceClass(className, DEFAULT_EXCLUSION_FILTER));
				}
			}
			return result;
		}

		/**
		 * 获取所有注解，每个注解被封装成{@link SourceClass}对象
		 * <p>
		 * {@link ConfigurationClassParser#collectImports(org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.Set, java.util.Set)}
		 * 中调用
		 * </p>
		 *
		 * @return
		 */
		public Set<SourceClass> getAnnotations() {
			Set<SourceClass> result = new LinkedHashSet<>();
			if (this.source instanceof Class) {
				/**
				 * 如果是class
				 */
				Class<?> sourceClass = (Class<?>) this.source;
				for (Annotation ann : sourceClass.getDeclaredAnnotations()) {
					// 遍历该source上的所有注解
					// 获取每个注解的类型
					Class<?> annType = ann.annotationType();
					if (!annType.getName().startsWith("java")) {
						// 如果注解不是以"java"开头，即不是jdk的注解，则加入
						try {
							result.add(asSourceClass(annType, DEFAULT_EXCLUSION_FILTER));
						} catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			} else {
				/**
				 * 构造函数传递的是{@link MetadataReader}
				 */
				for (String className : this.metadata.getAnnotationTypes()) {
					// 遍历的是所有注解的className
					if (!className.startsWith("java")) {
						try {
							result.add(getRelated(className));
						} catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			}
			return result;
		}

		/**
		 * 获取本{@link SourceClass}标注的annType注解的attribute属性返回的值集合
		 * <p>
		 * {@link ConfigurationClassParser#collectImports(org.springframework.context.annotation.ConfigurationClassParser.SourceClass, java.util.Set, java.util.Set)}
		 * 中调用
		 * </p>
		 *
		 * @param annType
		 * @param attribute
		 * @return
		 * @throws IOException
		 */
		public Collection<SourceClass> getAnnotationAttributes(String annType, String attribute) throws IOException {
			Map<String, Object> annotationAttributes = this.metadata.getAnnotationAttributes(annType, true);
			if (annotationAttributes == null || !annotationAttributes.containsKey(attribute)) {
				return Collections.emptySet();
			}
			String[] classNames = (String[]) annotationAttributes.get(attribute);
			Set<SourceClass> result = new LinkedHashSet<>();
			for (String className : classNames) {
				result.add(getRelated(className));
			}
			return result;
		}

		/**
		 * {@link SourceClass#getAnnotations()}中调用
		 *
		 * @param className
		 * @return
		 * @throws IOException
		 */
		@SuppressWarnings("deprecation")
		private SourceClass getRelated(String className) throws IOException {
			if (this.source instanceof Class) {
				try {
					Class<?> clazz = ClassUtils.forName(className, ((Class<?>) this.source).getClassLoader());
					return asSourceClass(clazz, DEFAULT_EXCLUSION_FILTER);
				} catch (ClassNotFoundException ex) {
					// Ignore -> fall back to ASM next, except for core java types.
					if (className.startsWith("java")) {
						throw new org.springframework.core.NestedIOException("Failed to load class [" + className + "]", ex);
					}
					return new SourceClass(metadataReaderFactory.getMetadataReader(className));
				}
			}
			return asSourceClass(className, DEFAULT_EXCLUSION_FILTER);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return (this == other || (other instanceof SourceClass &&
					this.metadata.getClassName().equals(((SourceClass) other).metadata.getClassName())));
		}

		@Override
		public int hashCode() {
			return this.metadata.getClassName().hashCode();
		}

		@Override
		public String toString() {
			return this.metadata.getClassName();
		}
	}


	/**
	 * {@link Problem} registered upon detection of a circular {@link Import}.
	 */
	private static class CircularImportProblem extends Problem {

		public CircularImportProblem(ConfigurationClass attemptedImport, Deque<ConfigurationClass> importStack) {
			super(String.format("A circular @Import has been detected: " +
									"Illegal attempt by @Configuration class '%s' to import class '%s' as '%s' is " +
									"already present in the current import stack %s", importStack.element().getSimpleName(),
							attemptedImport.getSimpleName(), attemptedImport.getSimpleName(), importStack),
					new Location(importStack.element().getResource(), attemptedImport.getMetadata()));
		}
	}

}
