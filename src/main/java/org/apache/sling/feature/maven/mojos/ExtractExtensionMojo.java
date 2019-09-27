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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.maven.ProjectHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;

@Mojo(name = "extract-extension",
    defaultPhase = LifecyclePhase.PACKAGE,
    threadSafe = true)
public class ExtractExtensionMojo extends AbstractFeatureMojo {
    @Parameter(name = "extension", required = true)
    String extension;

    @Parameter(name = "outputFile", required = true)
    String outputFile;

    @Parameter(name = "aggregateClassifier", required = false)
    String aggregateClassifier;

    @Parameter(name = "featureFile", required = false)
    String featureFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();

        String absoluteFile = null;
        if (featureFile != null) {
            String prefix = this.features.toPath().normalize().toFile().getAbsolutePath().concat(File.separator);
            absoluteFile = prefix + featureFile;
        }

        Map<String, Feature> projFeats = ProjectHelper.getFeatures(this.project);
        Feature srcFeature = null;
        for (final Map.Entry<String, Feature> entry : projFeats.entrySet()) {
            final String classifier = entry.getValue().getId().getClassifier();
            if (classifier != null && aggregateClassifier != null) {
                if (classifier.equals(aggregateClassifier.trim())) {
                    srcFeature = entry.getValue();
                    break;
                }
            } else if (absoluteFile != null) {
                if (absoluteFile.equals(entry.getKey())) {
                    srcFeature = entry.getValue();
                    break;
                }
            }
        }

        Extension ext = srcFeature.getExtensions().getByName(extension);
        try (Writer wr = new FileWriter(outputFile)) {
            switch (ext.getType()) {
            case ARTIFACTS:
                // List the artifacts as text
                for (Artifact a : ext.getArtifacts()) {
                    wr.write(a.getId().toMvnId());
                    wr.write(System.lineSeparator());
                }
                break;
            case JSON:
                wr.write(ext.getJSON());
                break;
            case TEXT:
                wr.write(ext.getText());
                break;
            }
        } catch (IOException ex) {
            throw new MojoExecutionException("Problem writing feature" + outputFile, ex);
        }
    }
}
