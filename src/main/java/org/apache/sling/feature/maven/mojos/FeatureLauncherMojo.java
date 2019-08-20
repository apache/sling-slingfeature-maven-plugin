/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.feature.maven.mojos;

import com.google.common.io.Files;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
//import org.apache.sling.feature.launcher.impl.Main;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.maven.ProjectHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Launches the given Feature File
 */
@Mojo(
    name = "launch-features",
    requiresProject = true,
    threadSafe = true
)
public class FeatureLauncherMojo extends AbstractIncludingFeatureMojo {

    public static final String CFG_ARTIFACT_CLASH_OVERRIDES = "artifactClashOverrides";
    public static final String CFG_REPOSITORY_URL = "frameworkRepositoryUrl";
    public static final String CFG_FRAMEWORK_PROPERTIES = "frameworkProperties";
    public static final String CFG_VARIABLE_VALUES = "variableValues";
    public static final String CFG_VERBOSE = "verbose";
    public static final String CFG_CACHE_DIRECTORY = "cacheDirectory";
    public static final String CFG_HOME_DIRECTORY = "homeDirectory";
    public static final String CFG_EXTENSION_CONFIGURATIONS = "extensionConfigurations";
    public static final String CFG_FRAMEWORK_VERSION = "frameworkVersion";
    public static final String CFG_FRAMEWORK_ARTIFACTS = "frameworkArtifacts";

    @Parameter
    private FeatureSelectionConfig selection;

    /**
     * The Artifact Id Overrides (see Feature Launcher for more info)
     */
    @Parameter(property = CFG_ARTIFACT_CLASH_OVERRIDES, required = false)
    private String[] artifactClashOverrides;

    /**
     * The Url for the Repository (see Feature Launcher for more info)
     */
    @Parameter(property = CFG_REPOSITORY_URL, required = false)
    private String repositoryUrl;

    /**
     * Framework Properties for the Launcher
     */
    @Parameter(property = CFG_FRAMEWORK_PROPERTIES, required = false)
    private String[] frameworkProperties;

    /**
     * Variable Values for the Launcher
     */
    @Parameter(property = CFG_VARIABLE_VALUES, required = false)
    private String[] variableValues;

    /**
     * Framework Properties for the Launcher
     */
    @Parameter(property = CFG_VERBOSE, required = false, defaultValue = "false")
    private boolean verbose;

    /**
     * The path of the folder where the cache is located
     */
    @Parameter(property = CFG_CACHE_DIRECTORY, required = false)
    private File cacheDirectory;

    /**
     * The path of the folder where the launcher home is located
     */
    @Parameter(property = CFG_HOME_DIRECTORY, required = false)
    private File homeDirectory;

    /**
     * Extension Configurations for the Launcher
     */
    @Parameter(property = CFG_EXTENSION_CONFIGURATIONS, required = false)
    private String[] extensionConfigurations;

    /**
     * The Framework Version (see Feature Launcher for more info)
     */
    @Parameter(property = CFG_FRAMEWORK_VERSION, required = false)
    private String frameworkVersion;

    /**
     * Framework Artifacts for the Launcher
     */
    @Parameter(property = CFG_FRAMEWORK_ARTIFACTS, required = false)
    private String[] frameworkArtifacts;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();
        List<String> arguments = new ArrayList<>();
        getLog().info("Feature Selection: " + selection);
        final Collection<Feature> features = getSelectedFeatures(selection).values();
        getLog().info("Features from Selection: " + features);
        for(Feature feature: features) {
            // Loop over all features found, create a temporary file, write the features there and add them to the launcher's file list
            File folder = Files.createTempDir();
            ArtifactId id = feature.getId();
            File featureFile = new File(folder, id.toMvnId().replaceAll(":", "-") + ".json");
            // TODO: Do we need to support Prototypes etc?
            try ( final Writer writer = new FileWriter(featureFile)) {
                FeatureJSONWriter.write(writer, feature);
            } catch (final IOException e) {
                throw new MojoExecutionException("Unable to write feature file  :" + id.toMvnId(), e);
            }
            getLog().info("Feature File Location: " + featureFile);
            handleFile(arguments, featureFile, "-f");
        }
        handleStringList(arguments, artifactClashOverrides, "-C");
        handleString(arguments, repositoryUrl, "-u");
        handleStringList(arguments, frameworkProperties, "-D");
        handleStringList(arguments, variableValues, "-V");
        if(verbose) {
            arguments.add("-v");
        }
        handleFile(arguments, cacheDirectory, "-c");
        handleFile(arguments, homeDirectory, "-p");
        handleStringList(arguments, extensionConfigurations, "-ec");
        handleString(arguments, frameworkVersion, "-fw");
        handleStringList(arguments, frameworkArtifacts, "-fa");

        String[] args = arguments.toArray(new String[] {});
        getLog().info("Launcher Arguments: '" + arguments + "'");
        launch(args);
    }

    void launch(String[] arguments) throws MojoExecutionException {
        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(
                "org.apache.sling.feature.launcher.impl.Main"
            );
            Method main = clazz.getMethod("main", String[].class);
            main.invoke(null, (Object) arguments);
        } catch (ClassNotFoundException e) {
            throw new MojoExecutionException("Failed to load Feature Launcher, make sure the Launcher Dependency is added to the Plugin", e);
        } catch (NoSuchMethodException e) {
            throw new MojoExecutionException("Failed to find Launcher's Main class, make sure the Launcher Dependency is added to the Plugin", e);
        } catch (IllegalAccessException e) {
            throw new MojoExecutionException("Access to Launcher's Main.main() failed", e);
        } catch (InvocationTargetException e) {
            throw new MojoExecutionException("Invocation of Launcher's Main.main() failed", e);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Arguments of Launcher's Main.main() wrong", e);
        }
    }

    private void handleStringList(List<String> arguments, String[] list, String parameter) {
        if(list != null) {
            for(String item: list) {
                arguments.add(parameter);
                arguments.add(item);
            }
        }
    }

    private void handleString(List<String> arguments, String item, String parameter) {
        if(item != null && !item.isEmpty()) {
            arguments.add(parameter);
            arguments.add(item);
        }
    }

    private void handleFile(List<String> arguments, File file, String parameter) {
        if(file != null) {
            arguments.add(parameter);
            arguments.add(file.getAbsolutePath());
        }
    }
}
