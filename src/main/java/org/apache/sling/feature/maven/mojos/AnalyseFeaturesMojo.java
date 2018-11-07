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
import java.util.Collections;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.analyser.Analyser;
import org.apache.sling.feature.analyser.AnalyserResult;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.maven.ProjectHelper;
import org.apache.sling.feature.scanner.Scanner;

/**
 * Analyse the feature.
 */
@Mojo(name = "analyse-features",
      defaultPhase = LifecyclePhase.VALIDATE,
      requiresDependencyResolution = ResolutionScope.TEST,
      threadSafe = true
    )
public class AnalyseFeaturesMojo extends AbstractIncludingFeatureMojo {

    @Parameter
    private List<Scan> scans;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        List<Scan> list = scans;
        if (list == null || list.isEmpty()) {
            // use default configuration
            final Scan a = new Scan();
            a.setFilesInclude("**/*.*");
            list = Collections.singletonList(a);
        }
        final ArtifactProvider am = new ArtifactProvider() {

            @Override
            public File provide(final ArtifactId id) {
                return ProjectHelper.getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, id).getFile();
            }
        };

        getLog().debug(MessageUtils.buffer().a("Setting up the ").strong("Scanner").a("...").toString());
        Scanner scanner;
        try {
            scanner = new Scanner(am);
        } catch (final IOException e) {
            throw new MojoExecutionException("A fatal error occurred while setting up the Scanner, see error cause:",
                    e);
        }
        getLog().debug(MessageUtils.buffer().strong("Scanner").a(" successfully set up").toString());

        boolean hasErrors = false;
        for (final Scan an : list) {
            try {

                getLog().debug(MessageUtils.buffer().a("Setting up the ").strong("Analyser").a(" with following configuration:").toString());
                getLog().debug(" * Context Configuration = " + an.getContextConfiguration());
                getLog().debug(" * Include Tasks = " + an.getIncludeTasks());
                getLog().debug(" * Exclude Tasks = " + an.getExcludeTasks());
                final Analyser analyser = new Analyser(scanner, an.getContextConfiguration(), an.getIncludeTasks(), an.getExcludeTasks());
                getLog().debug(MessageUtils.buffer().strong("Analyser").a(" successfully set up").toString());

                getLog().debug("Retrieving Feature files...");
                final Collection<Feature> features = this.getSelectedFeatures(an).values();

                if (features.isEmpty()) {
                    getLog().debug(
                            "There are no assciated Feature files to current project, plugin execution will be interrupted");
                    continue;
                } else {
                    getLog().debug("Starting Features analysis...");
                }

                for (final Feature f : features) {
                    try {
                        getLog().debug(MessageUtils.buffer().a("Analyzing Feature ").strong(f.getId().toMvnId())
                                .a(" ...").toString());
                        final AnalyserResult result = analyser.analyse(f);
                        for (final String msg : result.getWarnings()) {
                            getLog().warn(msg);
                        }
                        for (final String msg : result.getErrors()) {
                            getLog().error(msg);
                        }

                        if (!result.getErrors().isEmpty()) {
                            getLog().error("Analyser detected errors on Feature '" + f.getId().toMvnId()
                                    + "'. See log output for error messages.");
                            hasErrors = true;
                        } else {
                            getLog().debug(MessageUtils.buffer().a("Feature ").debug(f.getId().toMvnId())
                                    .a(" succesfully passed all analysis").toString());
                        }
                    } catch (Exception t) {
                        throw new MojoFailureException(
                                "Exception during analysing feature " + f.getId().toMvnId() + " : " + t.getMessage(),
                                t);
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "A fatal error occurred while setting up the Analyzer, see error cause:", e);
            } finally {
                getLog().debug("Features analysis complete");
            }
        }
        if (hasErrors) {
            throw new MojoFailureException("One or more features Analyzer detected Feature error(s), please read the plugin log for more datils");
        }
    }
}
