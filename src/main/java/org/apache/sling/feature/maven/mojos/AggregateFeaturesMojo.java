/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.feature.maven.mojos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.BuilderContext;
import org.apache.sling.feature.builder.FeatureBuilder;
import org.apache.sling.feature.builder.MergeHandler;
import org.apache.sling.feature.builder.PostProcessHandler;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.ProjectHelper;

/**
 * Aggregate multiple features into a single one.
 */
@Mojo(
        name = "aggregate-features",
        defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true)
public class AggregateFeaturesMojo extends AbstractIncludingFeatureMojo {

    /* A context flag to track if we have already been processed */
    private static final String PROPERTY_HANDLED_AGGREGATE_FEATURES =
            AggregateFeaturesMojo.class.getName() + "/generated";

    /**
     * The definition of the features used to create the new feature.
     */
    @Parameter(required = true)
    List<Aggregate> aggregates;

    @Parameter
    Map<String, Properties> handlerConfiguration = new HashMap<>();

    /**
     * Additional post process handlers to use when aggregating
     *
     * <p>Normally handlers are discovered using the {@link ServiceLoader} mechanism but some
     * special-purpose handlers are not registered by default.</p>
     *
     * <p>These handlers will be installed after the ones discovered by the {@link ServiceLoader}.</p>
     */
    @Parameter
    List<String> additionalPostProcessHandlers = new ArrayList<>();

    @Override
    public void execute() throws MojoExecutionException {
        checkPreconditions();

        // SLING-9656 - make sure to process each aggregate feature only once
        @SuppressWarnings("unchecked")
        Map<Aggregate, Feature> handledAggregates =
                (Map<Aggregate, Feature>) this.project.getContextValue(PROPERTY_HANDLED_AGGREGATE_FEATURES);
        if (handledAggregates == null) {
            handledAggregates = new HashMap<>();
            this.project.setContextValue(PROPERTY_HANDLED_AGGREGATE_FEATURES, handledAggregates);
        }

        for (final Aggregate aggregate : aggregates) {
            final String aggregateFeatureKey =
                    ProjectHelper.generateAggregateFeatureKey(aggregate.classifier, aggregate.attach);

            // SLING-9656 - check if we have already processed this one
            Feature processedFeature = handledAggregates.get(aggregate);
            if (processedFeature != null) {
                getLog().debug("Found previously processed aggregate-feature " + aggregateFeatureKey);
                if (ProjectHelper.getAssembledFeatures(project).remove(aggregateFeatureKey, processedFeature)) {
                    getLog().debug("  Removed previous aggregate feature '" + aggregateFeatureKey
                            + "' from the project assembled features map");
                }

                if (ProjectHelper.getFeatures(this.project).remove(aggregateFeatureKey, processedFeature)) {
                    getLog().debug("  Removed previous aggregate feature '" + aggregateFeatureKey
                            + "' from the project features map");
                }
            }

            // check classifier
            ProjectHelper.validateFeatureClassifiers(this.project, aggregate.classifier, aggregate.attach);

            final Map<String, Feature> selection = this.getSelectedFeatures(aggregate);
            if (selection.isEmpty()) {
                getLog().warn("No features found for aggregate with classifier " + aggregate.classifier);
            }

            final Map<String, String> variablesOverwrites = new HashMap<>();
            if (aggregate.variablesOverrides != null) variablesOverwrites.putAll(aggregate.variablesOverrides);
            final Map<String, String> frameworkPropertiesOverwrites = new HashMap<>();
            if (aggregate.frameworkPropertiesOverrides != null)
                frameworkPropertiesOverwrites.putAll(aggregate.frameworkPropertiesOverrides);

            final BuilderContext builderContext = new BuilderContext(new BaseFeatureProvider() {
                        @Override
                        public Feature provide(ArtifactId id) {
                            // check in selection
                            for (final Feature feat : selection.values()) {
                                if (feat.getId().equals(id)) {
                                    return feat;
                                }
                            }
                            return super.provide(id);
                        }
                    })
                    .setArtifactProvider(new BaseArtifactProvider())
                    .addVariablesOverrides(variablesOverwrites)
                    .addFrameworkPropertiesOverrides(frameworkPropertiesOverwrites)
                    .addMergeExtensions(StreamSupport.stream(
                                    Spliterators.spliteratorUnknownSize(
                                            ServiceLoader.load(MergeHandler.class)
                                                    .iterator(),
                                            Spliterator.ORDERED),
                                    false)
                            .toArray(MergeHandler[]::new))
                    .addPostProcessExtensions(postProcessHandlers().toArray(PostProcessHandler[]::new));
            for (final ArtifactId rule : aggregate.getArtifactOverrideRules()) {
                builderContext.addArtifactsOverride(rule);
            }

            builderContext.addConfigsOverrides(aggregate.getConfigurationOverrideRules());

            for (final Map.Entry<String, Properties> entry : handlerConfiguration.entrySet()) {
                String key = entry.getKey();
                Properties props = entry.getValue();

                builderContext.setHandlerConfiguration(key, ProjectHelper.propertiesToMap(props));
            }

            final ArtifactId newFeatureID = new ArtifactId(
                    project.getGroupId(),
                    project.getArtifactId(),
                    project.getVersion(),
                    aggregate.classifier,
                    FeatureConstants.PACKAGING_FEATURE);
            final Feature result = assembleFeature(newFeatureID, builderContext, selection);

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

            ProjectHelper.createTmpFeatureFile(project, result);
            ProjectHelper.setFeatureInfo(project, result);

            // Add feature to map of features
            ProjectHelper.getAssembledFeatures(project).put(aggregateFeatureKey, result);
            ProjectHelper.getFeatures(this.project).put(aggregateFeatureKey, result);

            // SLING-9656 - remember that we have already processed this one
            handledAggregates.put(aggregate, result);
        }
    }

    private Stream<PostProcessHandler> postProcessHandlers() {

        Stream<PostProcessHandler> serviceLoaderHandlers = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        ServiceLoader.load(PostProcessHandler.class).iterator(), Spliterator.ORDERED),
                false);

        Stream<PostProcessHandler> additionalHandlers = this.additionalPostProcessHandlers.stream()
                .map(extension -> {
                    try {
                        return (PostProcessHandler) Class.forName(extension)
                                .getDeclaredConstructor()
                                .newInstance();
                    } catch (Exception e) {
                        throw new RuntimeException("Unable to instantiate post-process extension " + extension, e);
                    }
                });

        return Stream.concat(serviceLoaderHandlers, additionalHandlers);
    }

    Feature assembleFeature(
            final ArtifactId newFeatureID, final BuilderContext builderContext, final Map<String, Feature> selection)
            throws MojoExecutionException {
        try {
            return FeatureBuilder.assemble(
                    newFeatureID, builderContext, selection.values().toArray(new Feature[selection.size()]));
        } catch (final Exception e) {
            throw new MojoExecutionException(
                    "Unable to aggregate feature "
                            + (newFeatureID.getClassifier() == null ? "<main artifact>" : newFeatureID.getClassifier())
                            + " : " + e.getMessage(),
                    e);
        }
    }
}
