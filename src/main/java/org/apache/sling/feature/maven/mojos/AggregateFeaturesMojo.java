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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.builder.BuilderContext;
import org.apache.sling.feature.builder.FeatureBuilder;
import org.apache.sling.feature.builder.FeatureProvider;
import org.apache.sling.feature.builder.MergeHandler;
import org.apache.sling.feature.builder.PostProcessHandler;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.ProjectHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

/**
 * Aggregate multiple features into a single one.
 */
@Mojo(name = "aggregate-features",
    defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
public class AggregateFeaturesMojo extends AbstractIncludingFeatureMojo {

    /**
     * The definition of the features used to create the new feature.
     */
    @Parameter(required = true)
    List<Aggregate> aggregates;

    @Parameter
    Map<String, Properties> handlerConfiguration = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        ProjectHelper.checkPreprocessorRun(this.project);
        for (final Aggregate aggregate : aggregates) {
            // check classifier
            ProjectHelper.validateFeatureClassifiers(this.project, aggregate.classifier);

            final Map<String, Feature> selection = this.getSelectedFeatures(aggregate);
            if (selection.isEmpty()) {
                throw new MojoExecutionException(
                        "No features found for aggregate with classifier " + aggregate.classifier);
            }

            final List<String> artifactOverrides = new ArrayList<>();
            if (aggregate.artifactOverrides != null)
                artifactOverrides.addAll(aggregate.artifactOverrides);
            final Map<String,String> variablesOverwrites = new HashMap<>();
            if (aggregate.variableOverrides != null)
                variablesOverwrites.putAll(aggregate.variableOverrides);
            final Map<String,String> frameworkPropertiesOverwrites = new HashMap<>();
            if (aggregate.frameworkPropertyOverrides != null)
                frameworkPropertiesOverwrites.putAll(aggregate.frameworkPropertyOverrides);

            final BuilderContext builderContext = new BuilderContext(new FeatureProvider() {
                @Override
                public Feature provide(ArtifactId id) {
                    // check in selection
                    for (final Feature feat : selection.values()) {
                        if (feat.getId().equals(id)) {
                            return feat;
                        }
                    }

                    // Check for the feature in the local context
                    for (final Feature feat : ProjectHelper.getAssembledFeatures(project).values()) {
                        if (feat.getId().equals(id)) {
                            return feat;
                        }
                    }

                    if (ProjectHelper.isLocalProjectArtifact(project, id)) {
                        throw new RuntimeException("Unable to resolve local artifact " + id.toMvnId());
                    }

                    // Finally, look the feature up via Maven's dependency mechanism
                    return ProjectHelper.getOrResolveFeature(project, mavenSession, artifactHandlerManager,
                            artifactResolver, id);
                }
            }).setArtifactProvider(new ArtifactProvider() {

                @Override
                public File provide(final ArtifactId id) {
                    if (ProjectHelper.isLocalProjectArtifact(project, id)) {
                        for (final Map.Entry<String, Feature> entry : ProjectHelper.getAssembledFeatures(project)
                                .entrySet()) {
                            if (entry.getValue().getId().equals(id)) {
                                // TODO - we might need to create a file to return it here
                                throw new RuntimeException(
                                        "Unable to get file for project feature " + entry.getValue().getId().toMvnId());
                            }
                        }
                    }
                    return ProjectHelper
                            .getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, id)
                            .getFile();
                }
            }).addArtifactsOverrides(artifactOverrides)
                .addVariablesOverrides(variablesOverwrites)
                .addFrameworkPropertiesOverrides(frameworkPropertiesOverwrites)
                .addMergeExtensions(StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                    ServiceLoader.load(MergeHandler.class).iterator(), Spliterator.ORDERED),
                    false).toArray(MergeHandler[]::new))
                .addPostProcessExtensions(StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                    ServiceLoader.load(PostProcessHandler.class).iterator(), Spliterator.ORDERED),
                    false).toArray(PostProcessHandler[]::new));

            for (final Map.Entry<String, Properties> entry : handlerConfiguration.entrySet()) {
                builderContext.setHandlerConfiguration(entry.getKey(), ProjectHelper.propertiesToMap(entry.getValue()));
            }

            final ArtifactId newFeatureID = new ArtifactId(project.getGroupId(), project.getArtifactId(),
                    project.getVersion(), aggregate.classifier, FeatureConstants.PACKAGING_FEATURE);
            final Feature result = FeatureBuilder.assemble(newFeatureID, builderContext,
                    selection.values().toArray(new Feature[selection.size()]));

            if (aggregate.markAsFinal) {
                result.setFinal(true);
            }
            if (aggregate.markAsComplete) {
                result.setComplete(true);
            }
            if (aggregate.title != null) {
                result.setTitle(aggregate.title);
            }
            if (aggregate.description != null) {
                result.setDescription(aggregate.description);
            }
            if (aggregate.vendor != null) {
                result.setVendor(aggregate.vendor);
            }

            ProjectHelper.setFeatureInfo(project, result);

            // Add feature to map of features
            final String key = ProjectHelper.generateAggregateFeatureKey(aggregate.classifier);
            ProjectHelper.getAssembledFeatures(project).put(key, result);
            ProjectHelper.getFeatures(this.project).put(key, result);
        }
    }
}
