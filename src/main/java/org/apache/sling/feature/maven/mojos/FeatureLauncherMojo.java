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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sling.feature.launcher.impl.Main;
import org.apache.sling.feature.maven.ProjectHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Launches the given Feature File
 */
@Mojo(
    name = "launch-features",
    requiresProject = true,
    threadSafe = true
)
public final class FeatureLauncherMojo extends AbstractFeatureMojo {

    public static final String CFG_ARTIFACT_CLASH_OVERRIDES = "artifactClashOverrides";
    public static final String CFG_REPOSITORY_URL = "frameworkRepositoryUrl";
    public static final String CFG_FRAMEWORK_PROPERTIES = "frameworkProperties";
    public static final String CFG_FEATURE_FILE = "featureFile";
    public static final String CFG_VARIABLE_VALUES = "variableValues";
    public static final String CFG_VERBOSE = "verbose";
    public static final String CFG_CACHE_DIRECTORY = "cacheDirectory";
    public static final String CFG_HOME_DIRECTORY = "homeDirectory";
    public static final String CFG_EXTENSION_CONFIGURATIONS = "extensionConfigurations";
    public static final String CFG_FRAMEWORK_VERSION = "frameworkVersion";
    public static final String CFG_FRAMEWORK_ARTIFACTS = "frameworkArtifacts";

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
    private String[] extensionConfiguration;

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

    /**
     * The path of the file that is launched here
     */
    @Parameter(property = CFG_FEATURE_FILE, required = true)
    private File featureFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        ProjectHelper.checkPreprocessorRun(this.project);
        List<String> arguments = new ArrayList<>();
        if(!featureFile.isFile()) {
            throw new MojoFailureException("Feature File is not a file: " + featureFile);
        }
        if(!featureFile.canRead()) {
            throw new MojoFailureException("Feature File is cannot be read: " + featureFile);
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
        handleStringList(arguments, extensionConfiguration, "-ec");
        handleString(arguments, frameworkVersion, "-fw");
        handleStringList(arguments, frameworkArtifacts, "-fa");
        handleFile(arguments, featureFile, "-f");

        String[] args = arguments.toArray(new String[] {});
        getLog().info("Launcher Arguments: '" + arguments + "'");
        Main.main(arguments.toArray(args));
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
