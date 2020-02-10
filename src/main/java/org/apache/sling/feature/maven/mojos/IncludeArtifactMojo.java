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
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Artifacts;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionState;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.ProjectHelper;

/**
 * Attach the feature as a project artifact.
 */
@Mojo(name = "include-artifact", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE,
      threadSafe = true
    )
public class IncludeArtifactMojo extends AbstractFeatureMojo {

    /**
     * Classifier of the feature the current artifact is included in.
     */
    @Parameter
    private String includeArtifactClassifier;

    /**
     * Name of the extension to include the artifact in. If not specified the
     * artifact is included as a bundle.
     */
    @Parameter
    private String includeArtifactExtension;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (includeArtifactClassifier == null) {
            throw new MojoExecutionException("includeArtifactClassifier is not specified. Check your configuration");
        }

        checkPreconditions();

        final Map<String, Feature> featuresMap = ProjectHelper.getFeatures(this.project);
        Feature found = null;
        String key = null;
        for (final Map.Entry<String, Feature> entry : featuresMap.entrySet()) {
            if (includeArtifactClassifier.equals(entry.getValue().getId().getClassifier())) {
                key = entry.getKey();
                found = entry.getValue();
                break;
            }
        }
        final Artifact art = new Artifact(new ArtifactId(this.project.getGroupId(), this.project.getArtifactId(),
                this.project.getVersion(), null, this.project.getArtifact().getType()));
        File file = null;
        if (found == null) {
            found = new Feature(new ArtifactId(this.project.getGroupId(), this.project.getArtifactId(),
                    this.project.getVersion(), includeArtifactClassifier, FeatureConstants.PACKAGING_FEATURE));

            file = new File(this.getTmpDir(), "feature-" + this.includeArtifactClassifier + ".json");
            key = file.getAbsolutePath();
            ProjectHelper.getFeatures(this.project).put(key, found);
            ProjectHelper.getAssembledFeatures(this.project).put(key, found);
        }
        includeArtifact(found, includeArtifactExtension, art);
        includeArtifact(ProjectHelper.getAssembledFeatures(this.project).get(key), includeArtifactExtension,
                art.copy(art.getId()));
        if (file != null) {
            try ( final Writer writer = new FileWriter(file)) {
                FeatureJSONWriter.write(writer, found);
            } catch (final IOException ioe) {
                throw new MojoExecutionException("Unable to write feature", ioe);
            }
        }
    }

    private void includeArtifact(final Feature f, final String extensionName, final Artifact art)
            throws MojoExecutionException {
        Artifacts container = f.getBundles();
        if (extensionName != null) {
            Extension ext = f.getExtensions().getByName(extensionName);
            if (ext == null) {
                ext = new Extension(ExtensionType.ARTIFACTS, extensionName, ExtensionState.REQUIRED);
                f.getExtensions().add(ext);
            }
            if (ext.getType() != ExtensionType.ARTIFACTS) {
                throw new MojoExecutionException(
                        "Wrong extension type for extension " + extensionName + " : " + ext.getType());
            }
            container = ext.getArtifacts();
        }
        container.add(art);
    }
}
