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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
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

    @Parameter(name = "selection", required = true)
    FeatureSelectionConfig selection;

    @Parameter(defaultValue = "true")
    boolean failOnValidationError;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();

        getLog().info("Feature Selection: " + selection);

        Map<String, Feature> selFeat = getSelectedFeatures(selection);
        for (Map.Entry<String, Feature> entry : selFeat.entrySet()) {
            final Feature f = entry.getValue();

            // check if configuration api is set
            final ConfigurationApi api = ConfigurationApi.getConfigurationApi(f);
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
