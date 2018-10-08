/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.feature.maven.mojos;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;

import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.analyser.Analyser;
import org.apache.sling.feature.maven.ProjectHelper;
import org.apache.sling.feature.scanner.Scanner;
import org.apache.sling.feature.scanner.spi.ArtifactProvider;

/**
 * Analyse the feature.
 */
@Mojo(name = "analyse-features",
      defaultPhase = LifecyclePhase.VALIDATE,
      requiresDependencyResolution = ResolutionScope.TEST,
      threadSafe = true
    )
public class AnalyseFeaturesMojo extends AbstractFeatureMojo {

    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    @Component
    ArtifactResolver artifactResolver;

    @Parameter
    Set<String> includeTasks;

    @Parameter
    Set<String> excludeTasks;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final ArtifactProvider am = new ArtifactProvider() {

            @Override
            public File provide(final ArtifactId id) {
                return ProjectHelper.getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, id).getFile();
            }
        };

        boolean failed = false;

        try {
            getLog().debug("Setting up the Scanner...");
            final Scanner scanner = new Scanner(am);
            getLog().debug("Scanner successfully set up");

            getLog().debug("Setting up the Analyser...");
            final Analyser analyser = new Analyser(scanner, includeTasks, excludeTasks);
            getLog().debug("Analyser successfully set up");

            getLog().debug("Retrieving Feature files...");
            final Collection<Feature> features = ProjectHelper.getAssembledFeatures(this.project).values();

            if (features.isEmpty()) {
                getLog().debug("There are no assciated Feature files to current ptoject, plugin execution will be interrupted");
                return;
            } else {
                getLog().debug("Starting Features analysis...");
            }

            for(final Feature f : features) {
                try {
                    getLog().debug("Analyzing Feature " + f.getId() + "...");
                    analyser.analyse(f);
                    getLog().debug("Feature " + f.getId() + " succesfully passed all analysis");
                } catch (Throwable t) {
                    failed = true;
                    getLog().error("An error occurred while analyzing Feature '" + f.getId() + "', read the log for details");
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("A fatal error occurred while setting up the Scanner and related Analyzer, see error cause:", e);
        } finally {
            getLog().debug("Features analysis complete");
        }

        if (failed) {
            throw new MojoFailureException("One or more features Analyzer detected Feature error(s), please read the plugin log for more datils");
        }
    }
}
