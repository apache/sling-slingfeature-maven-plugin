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
import java.io.IOException;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.maven.ProjectHelper;

/**
 * Create a Maven repository structure from the referenced artifacts in the features.
 */
@Mojo(
        name = "repository",
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true
    )
public class RepositoryMojo extends AbstractFeatureMojo {

    /**
     * The directory to store the artifacts into.
     */
    @Parameter(defaultValue = "artifacts")
    private String repositoryDir;

    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    /**
     * Used to look up Artifacts in the remote repository.
     *
     */
    @Component
    private ArtifactResolver resolver;

    /**
     * This is the name of the final model as written to the target directory.
     */
    @Parameter(defaultValue="slingstart.txt")
    private String repositoryModelName;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final File artifactDir = new File(this.project.getBuild().getDirectory(), repositoryDir);
        this.getLog().info("Creating repository in '" + artifactDir.getPath() + "'...");

        final Map<String, org.apache.sling.feature.Feature> features = ProjectHelper.getAssembledFeatures(this.project);

        for(final Feature f : features.values()) {
            for(final org.apache.sling.feature.Artifact artifact : f.getBundles()) {
                copyArtifactToRepository(artifact, artifactDir);
            }
            for(final Extension ext : f.getExtensions()) {
                if ( ext.getType() == ExtensionType.ARTIFACTS ) {
                    for(final org.apache.sling.feature.Artifact artifact : ext.getArtifacts()) {
                        copyArtifactToRepository(artifact, artifactDir);
                    }
                }
            }
        }
    }

    /**
     * Get the file in the repository directory
     * @param artifactDir The base artifact directory
     * @param artifact The artifact
     * @return The file
     */
    private File getRepositoryFile(final File artifactDir, final org.apache.sling.feature.Artifact artifact) {
        final StringBuilder artifactNameBuilder = new StringBuilder();
        artifactNameBuilder.append(artifact.getId().getArtifactId());
        artifactNameBuilder.append('-');
        artifactNameBuilder.append(artifact.getId().getVersion());
        if ( artifact.getId().getClassifier() != null ) {
            artifactNameBuilder.append('-');
            artifactNameBuilder.append(artifact.getId().getClassifier());
        }
        artifactNameBuilder.append('.');
        artifactNameBuilder.append(artifact.getId().getType());
        final String artifactName = artifactNameBuilder.toString();

        final StringBuilder sb = new StringBuilder();
        sb.append(artifact.getId().getGroupId().replace('.', File.separatorChar));
        sb.append(File.separatorChar);
        sb.append(artifact.getId().getArtifactId());
        sb.append(File.separatorChar);
        sb.append(artifact.getId().getVersion());
        sb.append(File.separatorChar);
        sb.append(artifactName);
        final String destPath = sb.toString();

        final File artifactFile = new File(artifactDir, destPath);
        artifactFile.getParentFile().mkdirs();

        return artifactFile;
    }

    /**
     * Copy a single artifact to the repository
     * @throws MojoExecutionException
     */
    private void copyArtifactToRepository(final org.apache.sling.feature.Artifact artifact,
            final File artifactDir)
    throws MojoExecutionException {
        final File artifactFile = getRepositoryFile(artifactDir, artifact);

        final Artifact source = ProjectHelper.getOrResolveArtifact(this.project,
                this.mavenSession,
                this.artifactHandlerManager,
                this.resolver,
                artifact.getId());

        try {
            FileUtils.copyFile(source.getFile(), artifactFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to copy artifact from " + source.getFile(), e);
        }
    }
}
