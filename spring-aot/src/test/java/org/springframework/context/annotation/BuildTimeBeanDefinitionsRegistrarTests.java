/*
 * Copyright 2019-2022 the original author or authors.
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

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.BuildTimeBeanDefinitionsRegistrarTests.CustomClasspathScanningConfiguration.CustomClasspathScanningImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.samples.compose.ImportConfiguration;
import org.springframework.context.annotation.samples.condition.ConditionalConfigurationOne;
import org.springframework.context.annotation.samples.scan.ScanConfiguration;
import org.springframework.context.annotation.samples.simple.ConfigurationOne;
import org.springframework.context.annotation.samples.simple.ConfigurationTwo;
import org.springframework.context.annotation.samples.simple.SimpleComponent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Tests for {@link BuildTimeBeanDefinitionsRegistrar}.
 *
 * @author Stephane Nicoll
 */
class BuildTimeBeanDefinitionsRegistrarTests {

	private static final String[] ANNOTATION_INFRASTRUCTURE_BEAN_NAMES = new String[] {
			"org.springframework.context.annotation.internalAutowiredAnnotationProcessor",
			"org.springframework.context.annotation.internalCommonAnnotationProcessor",
			"org.springframework.context.annotation.internalPersistenceAnnotationProcessor",
			"org.springframework.context.event.internalEventListenerProcessor",
			"org.springframework.context.event.internalEventListenerFactory" };

	private final BuildTimeBeanDefinitionsRegistrar registrar = new BuildTimeBeanDefinitionsRegistrar();

	@Test
	void processBeanDefinitionsWithRegisteredConfigurationClasses() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new,
				ConfigurationOne.class, ConfigurationTwo.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getBeanDefinitionNames()).containsOnly(ignoringAnnotationInfrastructure("configurationOne",
				"configurationTwo", "beanOne", "beanTwo"));
	}

	@Test
	void processBeanDefinitionsWithRegisteredConfigurationClassWithImport() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new, ImportConfiguration.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getBeanDefinitionNames()).containsOnly(ignoringAnnotationInfrastructure("importConfiguration",
				ConfigurationOne.class.getName(), ConfigurationTwo.class.getName(), "beanOne", "beanTwo"));
	}

	@Test
	void processBeanDefinitionsWithClasspathScanning() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new, ScanConfiguration.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getBeanDefinitionNames()).containsOnly(ignoringAnnotationInfrastructure("scanConfiguration",
				"configurationOne", "configurationTwo", "simpleComponent", "beanOne", "beanTwo"));
	}

	@Test
	void processBeanDefinitionsWithConditionsOnConfigurationClassNotMatching() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new, ConditionalConfigurationOne.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getBeanDefinitionNames()).containsOnly(ANNOTATION_INFRASTRUCTURE_BEAN_NAMES);
	}

	@Test
	void processBeanDefinitionsWithConditionsOnConfigurationClassMatching() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new,
				new MockEnvironment().withProperty("test.one.enabled", "true"), ConditionalConfigurationOne.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getBeanDefinitionNames()).contains(ignoringAnnotationInfrastructure("conditionalConfigurationOne",
				ConfigurationOne.class.getName(), "beanOne", ConfigurationTwo.class.getName(), "beanTwo"));
	}

	@Test
	void processBeanDefinitionsWithCustomClasspathScanningAndNullBeanRegistry() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new,
				new MockEnvironment().withProperty("test.one.enabled", "true"), CustomClasspathScanningConfiguration.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		// Environment not linked so the condition evaluations applies on an "empty" environment.
		assertThat(beanFactory.getBeanDefinitionNames()).containsOnly(
				ignoringAnnotationInfrastructure("buildTimeBeanDefinitionsRegistrarTests.CustomClasspathScanningConfiguration"));
	}

	// Bean definitions

	@Test
	void processBeanDefinitionsForConfigurationClassCreateRelevantBeanDefinition() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new, ImportConfiguration.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getBeanDefinition(ConfigurationOne.class.getName()))
				.isInstanceOfSatisfying(ConfigurationClassBeanDefinition.class, (bd) -> {
					assertThat(bd.getConfigurationClass().getMetadata().getClassName()).isEqualTo(ConfigurationOne.class.getName());
					assertThat(bd.getConfigurationClass().getImportedBy()).hasSize(1);
				});
	}

	@Test
	void processBeanDefinitionsForBeanMethodCreateRelevantBeanDefinition() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new, ImportConfiguration.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getBeanDefinition("beanOne"))
				.isInstanceOfSatisfying(BeanMethodBeanDefinition.class, (bd) -> {
					assertThat(bd.getFactoryMethodMetadata().getMethodName()).isEqualTo("beanOne");
					assertThat(bd.getBeanMethod().getConfigurationClass().getBeanMethods())
							.singleElement().isEqualTo(bd.getBeanMethod());
				});
	}

	// Bean definitions post processing

	@Test
	void postProcessBeanDefinitionsInvokeBeanFactoryAwareOnBeanDefinitionPostProcessors() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new, SimpleComponent.class);
		context.setClassLoader(new CustomSpringFactoriesClassLoader("bean-factory-aware.factories"));
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getMergedBeanDefinition("simpleComponent")
				.getAttribute("beanFactory")).isEqualTo(beanFactory);
	}

	// Import Aware

	@Test
	void processContextWithoutConfigurationClassesDoesNotCreateImportOriginRegistry() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(ImportOriginRegistry.get(beanFactory)).isNull();
	}

	@Test
	void processContextWithNoInstanceAwareConfigurationClassesCreateEmptyImportOriginRegistry() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new, ConfigurationOne.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		ImportOriginRegistry registry = ImportOriginRegistry.get(beanFactory);
		assertThat(registry).isNotNull();
		assertThat(registry.getImportOrigins()).isEmpty();
	}

	@Test
	void processContextWithInstanceAwareConfigurationClassImportedCreateMatchingImportOriginRegistry() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new, ImportConfiguration.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		ImportOriginRegistry registry = ImportOriginRegistry.get(beanFactory);
		assertThat(registry).isNotNull();
		assertThat(registry.getImportOrigins()).containsOnly(entry(ConfigurationTwo.class.getName(),
				ImportConfiguration.class));
	}

	@Test
	void processContextWithInstanceAwareConfigurationClassNotImportedCreateMatchingImportOriginRegistry() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new, ConfigurationTwo.class);
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		ImportOriginRegistry registry = ImportOriginRegistry.get(beanFactory);
		assertThat(registry).isNotNull();
		assertThat(registry.getImportOrigins()).isEmpty();
	}

	@Test
	void processContextWithNullBeanDefinitionTypeIsIgnoredByImportOriginRegistry() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new);
		context.registerBeanDefinition("test", new RootBeanDefinition());
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(ImportOriginRegistry.get(beanFactory)).isNull();
	}

	@Test
	void processContextWithBeanDefinitionRegistryPostProcessorAndRoleInfrastructure() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new);
		context.registerBeanDefinition("testPostProcessor", BeanDefinitionBuilder
				.rootBeanDefinition(TestBeanDefinitionRegistryPostProcessor.class)
				.setRole(BeanDefinition.ROLE_INFRASTRUCTURE).getBeanDefinition());
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getBeanDefinitionNames()).contains("simpleComponent")
				.doesNotContain("testPostProcessor");
	}

	@Test
	void processContextWithBeanDefinitionRegistryPostProcessorAndDefaultRole() {
		GenericApplicationContext context = createApplicationContext(AnnotationConfigApplicationContext::new);
		context.registerBeanDefinition("testPostProcessor", BeanDefinitionBuilder
				.rootBeanDefinition(TestBeanDefinitionRegistryPostProcessor.class)
				.getBeanDefinition());
		ConfigurableListableBeanFactory beanFactory = this.registrar.processBeanDefinitions(context);
		assertThat(beanFactory.getBeanDefinitionNames()).contains("simpleComponent", "testPostProcessor");
	}

	private String[] ignoringAnnotationInfrastructure(String... beanNames) {
		return Stream.concat(Stream.of(ANNOTATION_INFRASTRUCTURE_BEAN_NAMES),
				Arrays.stream(beanNames)).toArray(String[]::new);
	}


	private <T extends GenericApplicationContext> T createApplicationContext(
			Supplier<T> contextFactory, Class<?>... componentClasses) {
		return createApplicationContext(contextFactory, new MockEnvironment(), componentClasses);
	}

	private <T extends GenericApplicationContext> T createApplicationContext(
			Supplier<T> contextFactory, ConfigurableEnvironment environment, Class<?>... componentClasses) {
		T context = contextFactory.get();
		context.setEnvironment(environment);
		for (Class<?> componentClass : componentClasses) {
			context.registerBean(componentClass);
		}
		return context;
	}


	@Configuration(proxyBeanMethods = false)
	@Import(CustomClasspathScanningImportBeanDefinitionRegistrar.class)
	static class CustomClasspathScanningConfiguration {

		static class CustomClasspathScanningImportBeanDefinitionRegistrar implements ImportBeanDefinitionRegistrar {

			@Override
			public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry, BeanNameGenerator importBeanNameGenerator) {
				ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider();
				provider.registerDefaultFilters();
				List<BeanDefinition> candidateComponents = new ArrayList<>(provider.findCandidateComponents(ConditionalConfigurationOne.class.getPackageName()));
				for (int i = 0; i < candidateComponents.size(); i++) {
					registry.registerBeanDefinition("candidateComponent" + i, candidateComponents.get(i));
				}
			}
		}

	}

	static class CustomSpringFactoriesClassLoader extends ClassLoader {

		private final String factoriesName;

		CustomSpringFactoriesClassLoader(String factoriesName) {
			super(CustomSpringFactoriesClassLoader.class.getClassLoader());
			this.factoriesName = factoriesName;
		}

		@Override
		public Enumeration<URL> getResources(String name) throws IOException {
			if ("META-INF/spring.factories".equals(name)) {
				return super.getResources("bean-definition-tests/" + this.factoriesName);
			}
			return super.getResources(name);
		}

	}

	static class BeanFactoryAwareBeanDefinitionPostProcessor implements BeanDefinitionPostProcessor, BeanFactoryAware {

		private BeanFactory beanFactory;

		@Override
		public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
			this.beanFactory = beanFactory;
		}

		@Override
		public void postProcessBeanDefinition(String beanName, RootBeanDefinition beanDefinition) {
			beanDefinition.setAttribute("beanFactory", this.beanFactory);
		}
	}

	static class TestBeanDefinitionRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

		@Override
		public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
			registry.registerBeanDefinition("simpleComponent", new RootBeanDefinition(SimpleComponent.class));
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

		}
	}

}
