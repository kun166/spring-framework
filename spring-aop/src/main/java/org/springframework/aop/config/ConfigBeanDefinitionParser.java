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

package org.springframework.aop.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.aspectj.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.aop.support.DefaultBeanFactoryPointcutAdvisor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanReference;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanNameReference;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.parsing.ParseState;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;

/**
 * {@link BeanDefinitionParser} for the {@code <aop:config>} tag.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Adrian Colyer
 * @author Mark Fisher
 * @author Ramnivas Laddad
 * @since 2.0
 */
class ConfigBeanDefinitionParser implements BeanDefinitionParser {

	private static final String ASPECT = "aspect";
	private static final String EXPRESSION = "expression";
	private static final String ID = "id";
	private static final String POINTCUT = "pointcut";
	private static final String ADVICE_BEAN_NAME = "adviceBeanName";
	private static final String ADVISOR = "advisor";
	private static final String ADVICE_REF = "advice-ref";
	private static final String POINTCUT_REF = "pointcut-ref";
	private static final String REF = "ref";
	private static final String BEFORE = "before";
	private static final String DECLARE_PARENTS = "declare-parents";
	private static final String TYPE_PATTERN = "types-matching";
	private static final String DEFAULT_IMPL = "default-impl";
	private static final String DELEGATE_REF = "delegate-ref";
	private static final String IMPLEMENT_INTERFACE = "implement-interface";
	private static final String AFTER = "after";
	private static final String AFTER_RETURNING_ELEMENT = "after-returning";
	private static final String AFTER_THROWING_ELEMENT = "after-throwing";
	private static final String AROUND = "around";
	private static final String RETURNING = "returning";
	private static final String RETURNING_PROPERTY = "returningName";
	private static final String THROWING = "throwing";
	private static final String THROWING_PROPERTY = "throwingName";
	private static final String ARG_NAMES = "arg-names";
	private static final String ARG_NAMES_PROPERTY = "argumentNames";
	private static final String ASPECT_NAME_PROPERTY = "aspectName";
	private static final String DECLARATION_ORDER_PROPERTY = "declarationOrder";
	private static final String ORDER_PROPERTY = "order";
	private static final int METHOD_INDEX = 0;
	private static final int POINTCUT_INDEX = 1;
	private static final int ASPECT_INSTANCE_FACTORY_INDEX = 2;

	private ParseState parseState = new ParseState();


	/**
	 * <p>
	 * 关于aop的配置,可以参考:https://blog.csdn.net/Wdasdasda/article/details/132171740
	 * https://zhuanlan.zhihu.com/p/465724373?utm_id=0
	 * </p>
	 * <aop:config>的配置,
	 *
	 * @param element       the element that is to be parsed into one or more {@link BeanDefinition BeanDefinitions}
	 * @param parserContext the object encapsulating the current state of the parsing process;
	 *                      provides access to a {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
	 * @return
	 */
	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		/**
		 * 英/ˈkɒmpəzɪt/ 合成的，复合的
		 */
		CompositeComponentDefinition compositeDef =
				new CompositeComponentDefinition(element.getTagName(), parserContext.extractSource(element));
		/**
		 * 注意:可以有多个<aop:config>。因此这个地方可以添加多次
		 */
		parserContext.pushContainingComponent(compositeDef);

		/**
		 * 向spring添加name为{@link AopConfigUtils#AUTO_PROXY_CREATOR_BEAN_NAME}的bean
		 */
		configureAutoProxyCreator(parserContext, element);

		List<Element> childElts = DomUtils.getChildElements(element);
		for (Element elt : childElts) {
			String localName = parserContext.getDelegate().getLocalName(elt);
			if (POINTCUT.equals(localName)) {
				/**
				 * pointcut也即<aop:pointcut>标签
				 * 向spring注册了一个{@link AspectJExpressionPointcut}的BeanDefinition
				 */
				parsePointcut(elt, parserContext);
			} else if (ADVISOR.equals(localName)) {
				/**
				 * <aop:advisor>标签
				 * 向spring注册了一个{@link DefaultBeanFactoryPointcutAdvisor}的BeanDefinition
				 *
				 * https://www.cnblogs.com/yulinfeng/p/7841167.html
				 * <context:component-scan base-package="com.demo"/>
				 *
				 * <aop:config>
				 *    <aop:pointcut id="test" expression="execution(* com.demo.TestPoint.test())"/>
				 *    <aop:advisor advice-ref="advisorTest" pointcut-ref="test"/>
				 * </aop:config>
				 */
				parseAdvisor(elt, parserContext);
			} else if (ASPECT.equals(localName)) {
				/**
				 * <aop:aspect>标签
				 *
				 */
				parseAspect(elt, parserContext);
			}
		}

		parserContext.popAndRegisterContainingComponent();
		return null;
	}

	/**
	 * Configures the auto proxy creator needed to support the {@link BeanDefinition BeanDefinitions}
	 * created by the '{@code <aop:config/>}' tag. Will force class proxying if the
	 * '{@code proxy-target-class}' attribute is set to '{@code true}'.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * </p>
	 *
	 * @see AopNamespaceUtils
	 */
	private void configureAutoProxyCreator(ParserContext parserContext, Element element) {
		AopNamespaceUtils.registerAspectJAutoProxyCreatorIfNecessary(parserContext, element);
	}

	/**
	 * Parses the supplied {@code <advisor>} element and registers the resulting
	 * {@link org.springframework.aop.Advisor} and any resulting {@link org.springframework.aop.Pointcut}
	 * with the supplied {@link BeanDefinitionRegistry}.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * </p>
	 * <aop:advisor>标签
	 */
	private void parseAdvisor(Element advisorElement, ParserContext parserContext) {
		AbstractBeanDefinition advisorDef = createAdvisorBeanDefinition(advisorElement, parserContext);
		String id = advisorElement.getAttribute(ID);

		try {
			this.parseState.push(new AdvisorEntry(id));
			String advisorBeanName = id;
			if (StringUtils.hasText(advisorBeanName)) {
				parserContext.getRegistry().registerBeanDefinition(advisorBeanName, advisorDef);
			} else {
				advisorBeanName = parserContext.getReaderContext().registerWithGeneratedName(advisorDef);
			}

			Object pointcut = parsePointcutProperty(advisorElement, parserContext);
			if (pointcut instanceof BeanDefinition) {
				advisorDef.getPropertyValues().add(POINTCUT, pointcut);
				parserContext.registerComponent(
						new AdvisorComponentDefinition(advisorBeanName, advisorDef, (BeanDefinition) pointcut));
			} else if (pointcut instanceof String) {
				advisorDef.getPropertyValues().add(POINTCUT, new RuntimeBeanReference((String) pointcut));
				parserContext.registerComponent(
						new AdvisorComponentDefinition(advisorBeanName, advisorDef));
			}
		} finally {
			this.parseState.pop();
		}
	}

	/**
	 * Create a {@link RootBeanDefinition} for the advisor described in the supplied. Does <strong>not</strong>
	 * parse any associated '{@code pointcut}' or '{@code pointcut-ref}' attributes.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parseAdvisor(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * <p>
	 * <context:component-scan base-package="com.demo"/>
	 * <p>
	 * <aop:config>
	 * <aop:pointcut id="test" expression="execution(* com.demo.TestPoint.test())"/>
	 * <aop:advisor advice-ref="advisorTest" pointcut-ref="test"/>
	 * </aop:config>
	 * </p>
	 */
	private AbstractBeanDefinition createAdvisorBeanDefinition(Element advisorElement, ParserContext parserContext) {
		RootBeanDefinition advisorDefinition = new RootBeanDefinition(DefaultBeanFactoryPointcutAdvisor.class);
		advisorDefinition.setSource(parserContext.extractSource(advisorElement));
		// "advice-ref"
		String adviceRef = advisorElement.getAttribute(ADVICE_REF);
		if (!StringUtils.hasText(adviceRef)) {
			parserContext.getReaderContext().error(
					"'advice-ref' attribute contains empty value.", advisorElement, this.parseState.snapshot());
		} else {
			advisorDefinition.getPropertyValues().add(
					ADVICE_BEAN_NAME, new RuntimeBeanNameReference(adviceRef));
		}

		if (advisorElement.hasAttribute(ORDER_PROPERTY)) {
			advisorDefinition.getPropertyValues().add(
					ORDER_PROPERTY, advisorElement.getAttribute(ORDER_PROPERTY));
		}

		return advisorDefinition;
	}

	/**
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * </p>
	 * <aop:aspect>标签
	 *
	 * @param aspectElement
	 * @param parserContext
	 */
	private void parseAspect(Element aspectElement, ParserContext parserContext) {
		String aspectId = aspectElement.getAttribute(ID);
		String aspectName = aspectElement.getAttribute(REF);

		try {
			this.parseState.push(new AspectEntry(aspectId, aspectName));
			List<BeanDefinition> beanDefinitions = new ArrayList<>();
			List<BeanReference> beanReferences = new ArrayList<>();

			/**
			 * 获取 "declare-parents" 标签,这个好像没看到有用的啊
			 * <aop:declare-parents types-matching="" implement-interface="">
			 */
			List<Element> declareParents = DomUtils.getChildElementsByTagName(aspectElement, DECLARE_PARENTS);
			for (int i = METHOD_INDEX; i < declareParents.size(); i++) {
				Element declareParentsElement = declareParents.get(i);
				beanDefinitions.add(parseDeclareParents(declareParentsElement, parserContext));
			}

			// We have to parse "advice" and all the advice kinds in one loop, to get the
			// ordering semantics right.
			NodeList nodeList = aspectElement.getChildNodes();
			boolean adviceFoundAlready = false;
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				/**
				 * <aop:before method="" pointcut="" arg-names="" pointcut-ref=""/>
				 * <aop:after method="" arg-names="" pointcut="" pointcut-ref=""/>
				 * <aop:after-returning method="" pointcut="" arg-names="" pointcut-ref="" returning=""/>
				 * <aop:after-throwing method="" pointcut-ref="" arg-names="" pointcut="" throwing=""/>
				 * <aop:around method="" pointcut="" arg-names="" pointcut-ref=""/>
				 *
				 * 好像不解析
				 * <aop:pointcut id="" expression=""/>
				 */
				if (isAdviceNode(node, parserContext)) {
					if (!adviceFoundAlready) {
						adviceFoundAlready = true;
						if (!StringUtils.hasText(aspectName)) {
							/**
							 * <aop:aspect ref="" />
							 * 标签少ref
							 */
							parserContext.getReaderContext().error(
									"<aspect> tag needs aspect bean reference via 'ref' attribute when declaring advices.",
									aspectElement, this.parseState.snapshot());
							return;
						}
						/**
						 * 把ref加到beanReferences列表里。
						 * 注意，这里只添加一次
						 */
						beanReferences.add(new RuntimeBeanReference(aspectName));
					}
					AbstractBeanDefinition advisorDefinition = parseAdvice(
							aspectName, i, aspectElement, (Element) node, parserContext, beanDefinitions, beanReferences);
					beanDefinitions.add(advisorDefinition);
				}
			}

			AspectComponentDefinition aspectComponentDefinition = createAspectComponentDefinition(
					aspectElement, aspectId, beanDefinitions, beanReferences, parserContext);
			parserContext.pushContainingComponent(aspectComponentDefinition);

			List<Element> pointcuts = DomUtils.getChildElementsByTagName(aspectElement, POINTCUT);
			for (Element pointcutElement : pointcuts) {
				parsePointcut(pointcutElement, parserContext);
			}

			parserContext.popAndRegisterContainingComponent();
		} finally {
			this.parseState.pop();
		}
	}

	private AspectComponentDefinition createAspectComponentDefinition(
			Element aspectElement, String aspectId, List<BeanDefinition> beanDefs,
			List<BeanReference> beanRefs, ParserContext parserContext) {

		BeanDefinition[] beanDefArray = beanDefs.toArray(new BeanDefinition[0]);
		BeanReference[] beanRefArray = beanRefs.toArray(new BeanReference[0]);
		Object source = parserContext.extractSource(aspectElement);
		return new AspectComponentDefinition(aspectId, beanDefArray, beanRefArray, source);
	}

	/**
	 * Return {@code true} if the supplied node describes an advice type. May be one of:
	 * '{@code before}', '{@code after}', '{@code after-returning}',
	 * '{@code after-throwing}' or '{@code around}'.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parseAspect(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * </p>
	 * 是否是以下标签之一:
	 * <aop:before method="" pointcut="" arg-names="" pointcut-ref=""/>
	 * <aop:after method="" arg-names="" pointcut="" pointcut-ref=""/>
	 * <aop:after-returning method="" pointcut="" arg-names="" pointcut-ref="" returning=""/>
	 * <aop:after-throwing method="" pointcut-ref="" arg-names="" pointcut="" throwing=""/>
	 * <aop:around method="" pointcut="" arg-names="" pointcut-ref=""/>
	 */
	private boolean isAdviceNode(Node aNode, ParserContext parserContext) {
		if (!(aNode instanceof Element)) {
			return false;
		} else {
			String name = parserContext.getDelegate().getLocalName(aNode);
			return (BEFORE.equals(name) || AFTER.equals(name) || AFTER_RETURNING_ELEMENT.equals(name) ||
					AFTER_THROWING_ELEMENT.equals(name) || AROUND.equals(name));
		}
	}

	/**
	 * Parse a '{@code declare-parents}' element and register the appropriate
	 * DeclareParentsAdvisor with the BeanDefinitionRegistry encapsulated in the
	 * supplied ParserContext.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parseAspect(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * </p>
	 */
	private AbstractBeanDefinition parseDeclareParents(Element declareParentsElement, ParserContext parserContext) {
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(DeclareParentsAdvisor.class);
		/**
		 * "implement-interface"
		 */
		builder.addConstructorArgValue(declareParentsElement.getAttribute(IMPLEMENT_INTERFACE));
		/**
		 * "types-matching"
		 */
		builder.addConstructorArgValue(declareParentsElement.getAttribute(TYPE_PATTERN));

		/**
		 * "default-impl"
		 */
		String defaultImpl = declareParentsElement.getAttribute(DEFAULT_IMPL);

		/**
		 * "delegate-ref"
		 */
		String delegateRef = declareParentsElement.getAttribute(DELEGATE_REF);

		if (StringUtils.hasText(defaultImpl) && !StringUtils.hasText(delegateRef)) {
			builder.addConstructorArgValue(defaultImpl);
		} else if (StringUtils.hasText(delegateRef) && !StringUtils.hasText(defaultImpl)) {
			builder.addConstructorArgReference(delegateRef);
		} else {
			parserContext.getReaderContext().error(
					"Exactly one of the " + DEFAULT_IMPL + " or " + DELEGATE_REF + " attributes must be specified",
					declareParentsElement, this.parseState.snapshot());
		}

		AbstractBeanDefinition definition = builder.getBeanDefinition();
		definition.setSource(parserContext.extractSource(declareParentsElement));
		parserContext.getReaderContext().registerWithGeneratedName(definition);
		return definition;
	}

	/**
	 * Parses one of '{@code before}', '{@code after}', '{@code after-returning}',
	 * '{@code after-throwing}' or '{@code around}' and registers the resulting
	 * BeanDefinition with the supplied BeanDefinitionRegistry.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parseAspect(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * </p>
	 *
	 * @return the generated advice RootBeanDefinition
	 */
	private AbstractBeanDefinition parseAdvice(String aspectName,
											   int order,
											   Element aspectElement,
											   Element adviceElement,
											   ParserContext parserContext,
											   List<BeanDefinition> beanDefinitions,
											   List<BeanReference> beanReferences) {

		try {
			this.parseState.push(new AdviceEntry(parserContext.getDelegate().getLocalName(adviceElement)));

			// create the method factory bean
			/**
			 * <aop:before method="" pointcut="" arg-names="" pointcut-ref=""/>
			 * <aop:after method="" arg-names="" pointcut="" pointcut-ref=""/>
			 * <aop:after-returning method="" pointcut="" arg-names="" pointcut-ref="" returning=""/>
			 * <aop:after-throwing method="" pointcut-ref="" arg-names="" pointcut="" throwing=""/>
			 * <aop:around method="" pointcut="" arg-names="" pointcut-ref=""/>
			 *
			 * 下面这个是根据最外层定义的beanName:aspectName,再组合标签定义的method
			 * 来组装一个{@link MethodLocatingFactoryBean},
			 * 它会作为构造器的第一个参数,传递给上述几个标签
			 */
			RootBeanDefinition methodDefinition = new RootBeanDefinition(MethodLocatingFactoryBean.class);
			/**
			 * 这个aspectName是beanName
			 */
			methodDefinition.getPropertyValues().add("targetBeanName", aspectName);
			/**
			 * method是aspectName 这个bean的method
			 */
			methodDefinition.getPropertyValues().add("methodName", adviceElement.getAttribute("method"));
			/**
			 * 嗯嗯，又见到了这个地方设置为true
			 */
			methodDefinition.setSynthetic(true);

			// create instance factory definition
			/**
			 * 呃，不知道为什么每次都要创建一个新的……
			 */
			RootBeanDefinition aspectFactoryDef =
					new RootBeanDefinition(SimpleBeanFactoryAwareAspectInstanceFactory.class);
			aspectFactoryDef.getPropertyValues().add("aspectBeanName", aspectName);
			/**
			 * 这个地方也是设置成了true
			 */
			aspectFactoryDef.setSynthetic(true);

			// register the pointcut
			AbstractBeanDefinition adviceDef = createAdviceDefinition(
					adviceElement, parserContext, aspectName, order, methodDefinition, aspectFactoryDef,
					beanDefinitions, beanReferences);

			// configure the advisor
			RootBeanDefinition advisorDefinition = new RootBeanDefinition(AspectJPointcutAdvisor.class);
			advisorDefinition.setSource(parserContext.extractSource(adviceElement));
			advisorDefinition.getConstructorArgumentValues().addGenericArgumentValue(adviceDef);
			if (aspectElement.hasAttribute(ORDER_PROPERTY)) {
				advisorDefinition.getPropertyValues().add(
						ORDER_PROPERTY, aspectElement.getAttribute(ORDER_PROPERTY));
			}

			// register the final advisor
			parserContext.getReaderContext().registerWithGeneratedName(advisorDefinition);

			return advisorDefinition;
		} finally {
			this.parseState.pop();
		}
	}

	/**
	 * Creates the RootBeanDefinition for a POJO advice bean. Also causes pointcut
	 * parsing to occur so that the pointcut may be associate with the advice bean.
	 * This same pointcut is also configured as the pointcut for the enclosing
	 * Advisor definition using the supplied MutablePropertyValues.
	 *
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parseAdvice(java.lang.String, int, org.w3c.dom.Element, org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.util.List, java.util.List)}
	 * 中调用
	 * </p>
	 */
	private AbstractBeanDefinition createAdviceDefinition(Element adviceElement,
														  ParserContext parserContext,
														  String aspectName,
														  int order,
														  RootBeanDefinition methodDef,
														  RootBeanDefinition aspectFactoryDef,
														  List<BeanDefinition> beanDefinitions,
														  List<BeanReference> beanReferences) {

		/**
		 * 根据配置的标签,确定要用哪个类:
		 * {@link AspectJMethodBeforeAdvice}
		 * {@link AspectJAfterAdvice}
		 * {@link AspectJAfterReturningAdvice}
		 * {@link AspectJAfterThrowingAdvice}
		 * {@link AspectJAroundAdvice}
		 */
		RootBeanDefinition adviceDefinition = new RootBeanDefinition(getAdviceClass(adviceElement, parserContext));
		adviceDefinition.setSource(parserContext.extractSource(adviceElement));

		adviceDefinition.getPropertyValues().add(ASPECT_NAME_PROPERTY, aspectName);
		adviceDefinition.getPropertyValues().add(DECLARATION_ORDER_PROPERTY, order);

		if (adviceElement.hasAttribute(RETURNING)) {
			adviceDefinition.getPropertyValues().add(
					RETURNING_PROPERTY, adviceElement.getAttribute(RETURNING));
		}
		if (adviceElement.hasAttribute(THROWING)) {
			adviceDefinition.getPropertyValues().add(
					THROWING_PROPERTY, adviceElement.getAttribute(THROWING));
		}
		if (adviceElement.hasAttribute(ARG_NAMES)) {
			adviceDefinition.getPropertyValues().add(
					ARG_NAMES_PROPERTY, adviceElement.getAttribute(ARG_NAMES));
		}

		ConstructorArgumentValues cav = adviceDefinition.getConstructorArgumentValues();
		/**
		 * 第一个参数位置是一个{@link java.lang.reflect.Method},
		 * methodDef是Method工厂
		 */
		cav.addIndexedArgumentValue(METHOD_INDEX, methodDef);

		/**
		 * 第二个参数{@link AspectJExpressionPointcut}
		 */
		Object pointcut = parsePointcutProperty(adviceElement, parserContext);
		if (pointcut instanceof BeanDefinition) {
			cav.addIndexedArgumentValue(POINTCUT_INDEX, pointcut);
			beanDefinitions.add((BeanDefinition) pointcut);
		} else if (pointcut instanceof String) {
			RuntimeBeanReference pointcutRef = new RuntimeBeanReference((String) pointcut);
			cav.addIndexedArgumentValue(POINTCUT_INDEX, pointcutRef);
			beanReferences.add(pointcutRef);
		}
		/**
		 * 第三个参数{@link AspectInstanceFactory},
		 * 实际上给的是{@link SimpleBeanFactoryAwareAspectInstanceFactory}
		 */
		cav.addIndexedArgumentValue(ASPECT_INSTANCE_FACTORY_INDEX, aspectFactoryDef);

		return adviceDefinition;
	}

	/**
	 * Gets the advice implementation class corresponding to the supplied {@link Element}.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#createAdviceDefinition(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.String, int, org.springframework.beans.factory.support.RootBeanDefinition, org.springframework.beans.factory.support.RootBeanDefinition, java.util.List, java.util.List)}
	 * 中调用
	 * </p>
	 * 根据配置的标签,确定要用哪个类:
	 * {@link AspectJMethodBeforeAdvice}
	 * {@link AspectJAfterAdvice}
	 * {@link AspectJAfterReturningAdvice}
	 * {@link AspectJAfterThrowingAdvice}
	 * {@link AspectJAroundAdvice}
	 */
	private Class<?> getAdviceClass(Element adviceElement, ParserContext parserContext) {
		String elementName = parserContext.getDelegate().getLocalName(adviceElement);
		if (BEFORE.equals(elementName)) {
			return AspectJMethodBeforeAdvice.class;
		} else if (AFTER.equals(elementName)) {
			return AspectJAfterAdvice.class;
		} else if (AFTER_RETURNING_ELEMENT.equals(elementName)) {
			return AspectJAfterReturningAdvice.class;
		} else if (AFTER_THROWING_ELEMENT.equals(elementName)) {
			return AspectJAfterThrowingAdvice.class;
		} else if (AROUND.equals(elementName)) {
			return AspectJAroundAdvice.class;
		} else {
			throw new IllegalArgumentException("Unknown advice kind [" + elementName + "].");
		}
	}

	/**
	 * Parses the supplied {@code <pointcut>} and registers the resulting
	 * Pointcut with the BeanDefinitionRegistry.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * </p>
	 * 处理<aop:pointcut>标签
	 * 关于标签可以参考:https://blog.csdn.net/Wdasdasda/article/details/132171740
	 */
	private AbstractBeanDefinition parsePointcut(Element pointcutElement, ParserContext parserContext) {
		/**
		 * id
		 */
		String id = pointcutElement.getAttribute(ID);
		/**
		 * 表达式
		 */
		String expression = pointcutElement.getAttribute(EXPRESSION);

		AbstractBeanDefinition pointcutDefinition = null;

		try {
			this.parseState.push(new PointcutEntry(id));
			pointcutDefinition = createPointcutDefinition(expression);
			pointcutDefinition.setSource(parserContext.extractSource(pointcutElement));

			String pointcutBeanName = id;
			if (StringUtils.hasText(pointcutBeanName)) {
				parserContext.getRegistry().registerBeanDefinition(pointcutBeanName, pointcutDefinition);
			} else {
				pointcutBeanName = parserContext.getReaderContext().registerWithGeneratedName(pointcutDefinition);
			}

			parserContext.registerComponent(
					new PointcutComponentDefinition(pointcutBeanName, pointcutDefinition, expression));
		} finally {
			this.parseState.pop();
		}

		return pointcutDefinition;
	}

	/**
	 * Parses the {@code pointcut} or {@code pointcut-ref} attributes of the supplied
	 * {@link Element} and add a {@code pointcut} property as appropriate. Generates a
	 * {@link org.springframework.beans.factory.config.BeanDefinition} for the pointcut if  necessary
	 * and returns its bean name, otherwise returns the bean name of the referred pointcut.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parseAdvisor(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * {@link ConfigBeanDefinitionParser#createAdviceDefinition(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.String, int, org.springframework.beans.factory.support.RootBeanDefinition, org.springframework.beans.factory.support.RootBeanDefinition, java.util.List, java.util.List)}
	 * 中调用
	 * </p>
	 */
	@Nullable
	private Object parsePointcutProperty(Element element, ParserContext parserContext) {
		if (element.hasAttribute(POINTCUT) && element.hasAttribute(POINTCUT_REF)) {
			/**
			 * 不能同时有标签pointcut和pointcut-ref
			 */
			parserContext.getReaderContext().error(
					"Cannot define both 'pointcut' and 'pointcut-ref' on <advisor> tag.",
					element, this.parseState.snapshot());
			return null;
		} else if (element.hasAttribute(POINTCUT)) {
			// Create a pointcut for the anonymous pc and register it.
			String expression = element.getAttribute(POINTCUT);
			AbstractBeanDefinition pointcutDefinition = createPointcutDefinition(expression);
			pointcutDefinition.setSource(parserContext.extractSource(element));
			return pointcutDefinition;
		} else if (element.hasAttribute(POINTCUT_REF)) {
			String pointcutRef = element.getAttribute(POINTCUT_REF);
			if (!StringUtils.hasText(pointcutRef)) {
				parserContext.getReaderContext().error(
						"'pointcut-ref' attribute contains empty value.", element, this.parseState.snapshot());
				return null;
			}
			return pointcutRef;
		} else {
			parserContext.getReaderContext().error(
					"Must define one of 'pointcut' or 'pointcut-ref' on <advisor> tag.",
					element, this.parseState.snapshot());
			return null;
		}
	}

	/**
	 * Creates a {@link BeanDefinition} for the {@link AspectJExpressionPointcut} class using
	 * the supplied pointcut expression.
	 * <p>
	 * {@link ConfigBeanDefinitionParser#parsePointcut(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
	 * 中调用
	 * </p>
	 * 通过<aop:pointcut id="" expression="">标签的expression创建BeanDefinition
	 * {@link AspectJExpressionPointcut}
	 */
	protected AbstractBeanDefinition createPointcutDefinition(String expression) {
		RootBeanDefinition beanDefinition = new RootBeanDefinition(AspectJExpressionPointcut.class);
		/**
		 * 是的,这个bean的scope是"prototype"的
		 */
		beanDefinition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		/**
		 * 这个地方非常重要。一般的bean默认是false
		 */
		beanDefinition.setSynthetic(true);
		beanDefinition.getPropertyValues().add(EXPRESSION, expression);
		return beanDefinition;
	}

}
