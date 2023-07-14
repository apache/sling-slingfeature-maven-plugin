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

import java.util.Map;

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.extension.apiregions.api.config.ConfigurationApi;
import org.apache.sling.feature.extension.apiregions.api.config.validation.FeatureValidationResult;
import org.apache.sling.feature.extension.apiregions.api.config.validation.FeatureValidator;
import org.apache.sling.feature.maven.ProjectHelper;

/**
 * This mojo applies default configurations to selected features.
 */
@Mojo(name = "apply-default-config",
    defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
    threadSafe = true)
public class ApplyDefaultConfigMojo extends AbstractIncludingFeatureMojo {

    /**
     * The features to apply the default configuration to.
     */
    @Parameter(name = "selection", required = true)
    FeatureSelectionConfig selection;

    /**
     * If enabled (default) the build fails if the configuration is invalid
     */
    @Parameter(defaultValue = "true")
    boolean failOnValidationError;

    /**
     * Optional feature model dependency containing the configuration api to validate against.
     * @since 1.6
     */
    @Parameter
    Dependency configurationApiDependency;

    /**
     * Optional classifier for a feature from the local project containing the configuration api to validate against
     * @since 1.6
     */
    @Parameter
    String configurationApiClassifier;

    private ConfigurationApi getDefaultConfigurationApi() throws MojoExecutionException {
        if ( this.configurationApiClassifier != null && this.configurationApiDependency != null ) {
            throw new MojoExecutionException("Only one of configurationApiDependency or configurationApiClassifier can be specified, but not both.");
        }
        ConfigurationApi defaultApi = null;
        if ( this.configurationApiClassifier != null ) {
            final Map<String, Feature> projectFeatures = ProjectHelper.getAssembledFeatures(this.project);
            for(final Feature f : projectFeatures.values()) {
                if ( this.configurationApiClassifier.equals(f.getId().getClassifier()) ) {
                    defaultApi = ConfigurationApi.getConfigurationApi(f);
                    if ( defaultApi == null ) {
                        throw new MojoExecutionException("Specified feature with classifier " + this.configurationApiClassifier + " does not contain configuration api");
                    }
                    break;
                }
            }
            if ( defaultApi == null ) {
                throw new MojoExecutionException("Specified feature with classifier + " + this.configurationApiClassifier + " does not exist in project.");
            }

        } else if ( this.configurationApiDependency != null ) {
            final ArtifactId depId = ProjectHelper.toArtifactId(this.configurationApiDependency);
            if (ProjectHelper.isLocalProjectArtifact(this.project, depId)) {
                throw new MojoExecutionException(
                            "configurationApiDependency configuration is used to select a local feature: " + depId.toMvnId());
            }
            final Feature f = ProjectHelper.getOrResolveFeature(this.project, this.mavenSession,
                    this.artifactHandlerManager, this.repoSystem, depId);
            defaultApi = ConfigurationApi.getConfigurationApi(f);
            if ( defaultApi == null ) {
                throw new MojoExecutionException("Specified feature " + depId.toMvnId() + " does not contain configuration api");
            }
        }
        if ( defaultApi != null ) {
            getLog().info("Using configured configuration-api from " + 
                (this.configurationApiClassifier != null ? "classifier " + this.configurationApiClassifier
                                                         : " dependency " + ProjectHelper.toString(this.configurationApiDependency)));
        }

        return defaultApi;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();
        getLog().info("Feature Selection: " + selection);

        final ConfigurationApi defaultApi = this.getDefaultConfigurationApi();

        final Map<String, Feature> selFeat = getSelectedFeatures(selection);
        for (final Map.Entry<String, Feature> entry : selFeat.entrySet()) {
            final Feature f = entry.getValue();

            // check if configuration api is set
            final ConfigurationApi api = defaultApi != null ? defaultApi : ConfigurationApi.getConfigurationApi(f);
            if ( api != null ) {
                final FeatureValidator validator = new FeatureValidator();
                validator.setFeatureProvider(new BaseFeatureProvider());

                final FeatureValidationResult result = validator.validate(f, api);
                if ( !result.isValid() && failOnValidationError ) {
                    throw new MojoExecutionException("Unable to apply default configuration to invalid feature ".concat(f.getId().toMvnId()));
                }

                if ( validator.applyDefaultValues(f, result) ) {
                    getLog().info("Applied default configurations to feature ".concat(f.getId().toMvnId()));
                    ProjectHelper.createTmpFeatureFile(project, f, true);
                }
            }
        }
    }
}
