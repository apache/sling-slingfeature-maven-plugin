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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.maven.ProjectHelper;

@Mojo(
    name = "collect-artifacts",
    requiresProject = false,
    threadSafe = true
)
public final class ArtifactsMojo extends AbstractRepositoryMojo {

    private final Pattern gavPattern = Pattern.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?:([^: ]+)");

    /**
     * A CSV list of Feature GAV.
     * Specifying this property, <code>includes</code> parameter will be overridden
     */
    @Parameter(property = "features")
    private String csvFeaturesGAV;
    
    public void execute() throws MojoExecutionException, MojoFailureException {
        final File artifactDir ;
        if ( this.project.getBuild().getDirectory().contains( "${project.basedir}" ) ) {
            artifactDir = new File(repositoryDir);
        } else {
            artifactDir = new File(this.project.getBuild().getDirectory(), repositoryDir);
        }
        this.getLog().info("Creating repository in '" + artifactDir.getPath() + "'...");

        final Map<String, org.apache.sling.feature.Feature> features = ProjectHelper.getAssembledFeatures(this.project);

        List<Include> includes = getIncludes();

        if (includes != null && !includes.isEmpty()) {
            for (Include include : includes) {
                boolean found = false;
                for (Feature f : features.values()) {
                    if (f.getId().equals(include.getID())) {
                        processFeature(artifactDir, f);
                        found = true;
                    }
                }
                if (!found) {
                    processRemoteFeature(artifactDir, include.getID());
                }
            }
        } else {
            for (final Feature f : features.values()) {
                processFeature(artifactDir, f);
            }
        }
        if (embed != null) {
            for (Include include : embed) {
                copyArtifactToRepository(include.getID(), artifactDir);
            }
        }
    }

    protected List<Include> getIncludes() {
        List<Include> includes = new ArrayList<>();

        if (csvFeaturesGAV != null && !csvFeaturesGAV.isEmpty()) {

            StringTokenizer tokenizer = new StringTokenizer(csvFeaturesGAV, ",");
            while (tokenizer.hasMoreTokens()) {
                String gav = tokenizer.nextToken();
                Matcher gavMatcher = gavPattern.matcher(gav);

                if (!gavMatcher.matches()) {
                    getLog().warn("Wrong GAV coordinates "
                            + gav
                            + " specified on 'features' property, expected format is groupId:artifactId[:packaging[:classifier]]:version");
                }

                Include include = new Include();
                include.setGroupId(gavMatcher.group(1));
                include.setArtifactId(gavMatcher.group(2));
                include.setVersion(gavMatcher.group(7));
                include.setType(gavMatcher.group(4));
                include.setClassifier(gavMatcher.group(6));

                includes.add(include);
            }
        }

        return includes;
    }
}
