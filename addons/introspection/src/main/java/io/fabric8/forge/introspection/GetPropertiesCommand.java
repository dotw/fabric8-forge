package io.fabric8.forge.introspection;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.forge.addon.utils.CommandHelpers;
import io.fabric8.forge.addon.utils.dto.OutputFormat;
import io.fabric8.forge.introspection.dto.PropertyDTO;
import io.fabric8.forge.introspection.introspect.support.ClassScanner;
import org.jboss.forge.addon.parser.java.facets.JavaCompilerFacet;
import org.jboss.forge.addon.parser.java.facets.JavaSourceFacet;
import org.jboss.forge.addon.parser.java.resources.JavaFieldResource;
import org.jboss.forge.addon.parser.java.resources.JavaResource;
import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.ProjectFacet;
import org.jboss.forge.addon.projects.Projects;
import org.jboss.forge.addon.projects.building.BuildException;
import org.jboss.forge.addon.projects.building.ProjectBuilder;
import org.jboss.forge.addon.projects.facets.ClassLoaderFacet;
import org.jboss.forge.addon.projects.facets.PackagingFacet;
import org.jboss.forge.addon.resource.Resource;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.input.InputComponent;
import org.jboss.forge.addon.ui.input.UICompleter;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.input.UISelectOne;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Metadata;
import org.jboss.forge.roaster.model.Field;

import javax.inject.Inject;

import java.beans.BeanInfo;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.util.*;
import java.util.stream.Collectors;

import static io.fabric8.forge.addon.utils.JavaHelper.loadProjectClass;
import static io.fabric8.forge.addon.utils.OutputFormatHelper.toJson;

/**
 * Command that returns the properties for the supplied Java object
 */
public class GetPropertiesCommand extends AbstractIntrospectionCommand {

	@Inject
	@WithAttributes(label = "classNames", required = true, description = "Fully qualified class name")
	private UIInput<List<String>> classNames;

	private List<InputComponent> inputComponents;

	@Override
	public void initializeUI(UIBuilder builder) throws Exception {
		/*
		className.setCompleter(new UICompleter<String>() {
			@Override
			public Iterable<String> getCompletionProposals(UIContext uiContext, InputComponent<?, String> inputComponent, String s) {
				Project project = getSelectedProject(uiContext);
				ClassScanner local = ClassScanner.newInstance(project);
				SortedSet<String> answer = local.findClassNames("", 0);
				local.dispose();
				return answer;
			}
		});
		*/
		inputComponents = CommandHelpers.addInputComponents(builder, classNames);
	}

	@Override
	public UICommandMetadata getMetadata(UIContext context)
	{
		return Metadata
				.forCommand(getClass())
				.name("Introspector: Get properties")
				.description("Get the properties of a Java object")
				.category(Categories.create("Introspector"));
	}


	@Override
	public Result execute(UIExecutionContext context) throws Exception {
		CommandHelpers.putComponentValuesInAttributeMap(context, inputComponents);
		UIContext uiContext = context.getUIContext();
		Map<Object, Object> map = uiContext.getAttributeMap();
		List<String> classNames = (List<String>) map.get("classNames");
		if (classNames == null || classNames.size() == 0) {
			return Results.fail("No className field provided");
		}
		Project project = Projects.getSelectedProject(getProjectFactory(), uiContext);
		// force build
		PackagingFacet packaging = project.getFacet(PackagingFacet.class);
		ProjectBuilder builder = packaging.createBuilder();
		builder.runTests(false);
		builder.addArguments("clean", "compile");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintStream stdout = new PrintStream(baos, true);
		try {
			builder.build(stdout, stdout);
		} catch (BuildException be) {
			// no point in continuing the operation
			return Results.fail("Failed to build project: " + be + "\n\n" + baos.toString());
		}
		ClassLoaderFacet classLoaderFacet = project.getFacet(ClassLoaderFacet.class);
		URLClassLoader classLoader = classLoaderFacet.getClassLoader();
		Map<String, List<Object>> answer = new HashMap<String, List<Object>>();
		for (String className : classNames) {
			List<Object> props = new ArrayList<Object>();
			Class clazz;
			try {
				clazz = classLoader.loadClass(className);
				BeanInfo beanInfo = java.beans.Introspector.getBeanInfo(clazz);
				PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();
				for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
					// ignore the class property
					if (propertyDescriptor.getName().equals("class")) {
						continue;
					}
					PropertyDTO info = new PropertyDTO(propertyDescriptor);
					props.add(info);
				}
			} catch (Exception e) {
				props.add("Failed to load class, error: " + e.getMessage());
			}
			answer.put(className, props);
		}
		classLoader.close();
		String result = toJson(answer);
		return Results.success(result);
	}
}
