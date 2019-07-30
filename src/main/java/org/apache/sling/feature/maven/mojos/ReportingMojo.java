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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;

import edu.emory.mathcs.backport.java.util.Collections;

/**
 * Simple reporting mojo
 */
@Mojo(
        name = "report",
        threadSafe = true
    )
public class ReportingMojo extends AbstractIncludingFeatureMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();
        // get the features
        final Map<String, Feature> features = this.selectAllFeatureFiles();
        if (features.isEmpty()) {
            throw new MojoExecutionException("No features found in project!");
        }

        final List<ArtifactId> bundles = new ArrayList<>();
        final List<ArtifactId> artifacts = new ArrayList<>();

        for (final Map.Entry<String, Feature> entry : features.entrySet()) {
            for (final Artifact bundle : entry.getValue().getBundles()) {
                if (!bundles.contains(bundle.getId())) {
                    bundles.add(bundle.getId());
                }
            }
            for (final Extension ext : entry.getValue().getExtensions()) {
                if (ext.getType() == ExtensionType.ARTIFACTS) {
                    for (final Artifact artifact : ext.getArtifacts()) {
                        if (!artifacts.contains(artifact.getId())) {
                            artifacts.add(artifact.getId());
                        }
                    }
                }
            }
        }

        Collections.sort(bundles);
        Collections.sort(artifacts);

        getLog().info("Bundles:");
        getLog().info("-------------------------------------------");
        for (final ArtifactId id : bundles) {
            getLog().info(id.toMvnId());
        }
        getLog().info("");
        getLog().info("Artifacts:");
        getLog().info("-------------------------------------------");
        for (final ArtifactId id : artifacts) {
            getLog().info(id.toMvnId());
        }
    }
}
