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

import java.io.File;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.sling.feature.maven.FeatureProjectConfig;

/**
 * Base class for all mojos.
 */
public abstract class AbstractFeatureMojo extends AbstractMojo {

	/**
	 * All of the below configurations are handled by the Preprocessor.
	 * Mojos should only use them for informational purposes but not
	 * for processing!
	 * The read features and test features are available through the
	 * ProjectHelper.
	 */

    /**
     * Directory containing feature files
     */
    @Parameter(name = FeatureProjectConfig.CFG_FEATURES,
            required = true,
            defaultValue = FeatureProjectConfig.DEFAULT_FEATURE_DIR)
    protected File features;

    /**
     * Comma separated list of includes for the feature files in
     * the configured directory. Only feature files specified by
     * this include are processed.
     */
    @Parameter(name = FeatureProjectConfig.CFG_FEATURES_INCLUDES,
            defaultValue = FeatureProjectConfig.DEFAULT_FEATURE_INCLUDES)
    private String featuresIncludes;

    /**
     * Comma separated list of excludes for the feature files.
     * Feature files excluded by this configuration are not processed
     * at all.
     */
    @Parameter(name = FeatureProjectConfig.CFG_FEATURES_EXCLUDES)
    private String featuresExcludes;

    /**
     * Directory containing test feature files.
     */
    @Parameter(name = FeatureProjectConfig.CFG_TEST_FEATURES,
            required = true,
            defaultValue = FeatureProjectConfig.DEFAULT_TEST_FEATURE_DIR)
    private File testFeatures;

    /**
     * Comma separated list of includes for the test features.
     */
    @Parameter(name = FeatureProjectConfig.CFG_TEST_FEATURES_INCLUDES,
            defaultValue = FeatureProjectConfig.DEFAULT_FEATURE_INCLUDES)
    private String testFeaturesIncludes;

    /**
     * Comma separated list of excludes for the test features.
     */
    @Parameter(name = FeatureProjectConfig.CFG_TEST_FEATURES_EXCLUDES)
    private String testFeaturesExcludes;

    /**
     * If set to {@code true} the artifacts from the feature are not as dependencies to the project.
     */
    @Parameter(name=FeatureProjectConfig.CFG_SKIP_ADD_FEATURE_DEPENDENCIES,
            defaultValue="false")
    private boolean skipAddFeatureDependencies;

    /**
     * If set to {@code true} the artifacts from the test feature are not as dependencies to the project.
     */
    @Parameter(name=FeatureProjectConfig.CFG_SKIP_ADD_TEST_FEATURE_DEPENDENCIES,
            defaultValue="true")
    private boolean skipAddTestFeatureDependencies;

    /**
     * If set to {@code true} the main jar artifact is not added to the feature.
     */
    @Parameter(name=FeatureProjectConfig.CFG_SKIP_ADD_JAR_TO_FEATURE,
            defaultValue="false")
    private boolean skipAddJarToFeature;

    /**
     * If set to {@code true} the main jar artifact is not added to the test feature.
     */
    @Parameter(name=FeatureProjectConfig.CFG_SKIP_ADD_JAR_TO_TEST_FEATURE,
            defaultValue="false")
    private boolean skipAddJarToTestFeature;

    /**
     * The start level for the attached jar/bundle.
     */
    @Parameter(name=FeatureProjectConfig.CFG_JAR_START_ORDER)
    private int jarStartOrder;

    @Parameter(property = "project", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "session", readonly = true, required = true)
    protected MavenSession mavenSession;

    @Component
    protected MavenProjectHelper projectHelper;

    protected File getTmpDir() {
        final File dir = new File(this.project.getBuild().getDirectory(), "slingfeature-tmp");
        dir.mkdirs();
        return dir;
    }
}
