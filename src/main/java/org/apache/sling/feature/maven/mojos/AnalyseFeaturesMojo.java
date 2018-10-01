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

import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
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
      defaultPhase = LifecyclePhase.VERIFY,
      requiresDependencyResolution = ResolutionScope.TEST,
      threadSafe = true
    )
public class AnalyseFeaturesMojo extends AbstractFeatureMojo {

    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    @Component
    ArtifactResolver artifactResolver;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final ArtifactProvider am = new ArtifactProvider() {

            @Override
            public File provide(final ArtifactId id) {
                return ProjectHelper.getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, id).getFile();
            }
        };
        try {
            final Scanner scanner = new Scanner(am);
            final Analyser analyser = new Analyser(scanner);
            final Collection<Feature> features = ProjectHelper.getAssembledFeatures(this.project).values();
            for(final Feature f : features) {
                try {
                    analyser.analyse(f);
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}
