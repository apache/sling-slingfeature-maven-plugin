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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.maven.FeatureProjectConfig;
import org.apache.sling.feature.maven.ProjectHelper;
import org.apache.sling.feature.maven.mojos.RepositoryMojo.Include;

@Mojo(
    name = "collect-artifacts",
    requiresProject = false,
    threadSafe = true
)
public final class ArtifactsMojo extends AbstractFeatureMojo {

    private final Pattern gavPattern = Pattern.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?:([^: ]+)");

    /**
     * The directory to store the artifacts into.
     */
    @Parameter(defaultValue = "artifacts")
    private String repositoryDir;

    @Parameter
    private List<Include> includes;

    @Parameter
    private List<Include> embed;
    
    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    /**
     * Used to look up Artifacts in the remote repository.
     *
     */
    @Component
    private ArtifactResolver resolver;

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
    
    private void processFeature(final File artifactDir, final Feature f) throws MojoExecutionException {
        for(final org.apache.sling.feature.Artifact artifact : f.getBundles()) {
            copyArtifactToRepository(artifact.getId(), artifactDir);
        }
        for(final Extension ext : f.getExtensions()) {
            if ( ext.getType() == ExtensionType.ARTIFACTS ) {
                for(final org.apache.sling.feature.Artifact artifact : ext.getArtifacts()) {
                    copyArtifactToRepository(artifact.getId(), artifactDir);
                }
            }
        }

        final File featureFile = getRepositoryFile(artifactDir, f.getId());
        featureFile.getParentFile().mkdirs();
        try ( final Writer writer = new FileWriter(featureFile)) {
            FeatureJSONWriter.write(writer, f);
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to write feature file ", e);
        }
        if ( f.getInclude() != null ) {
            processRemoteFeature(artifactDir, f.getInclude().getId());
        }
    }

    private void processRemoteFeature(final File artifactDir, final ArtifactId id) throws MojoExecutionException {
        final Artifact source = ProjectHelper.getOrResolveArtifact(this.project,
            this.mavenSession,
            this.artifactHandlerManager,
            this.resolver,
            id);

        try (final Reader reader = new FileReader(source.getFile()) ) {
            final Feature inc = FeatureJSONReader.read(reader, id.toMvnId());
            processFeature(artifactDir, inc);
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to read feature file ", e);
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


    /**
     * Get the file in the repository directory
     * @param artifactDir The base artifact directory
     * @param artifact The artifact
     * @return The file
     */
    private File getRepositoryFile(final File artifactDir, final org.apache.sling.feature.ArtifactId artifact) {
        final StringBuilder artifactNameBuilder = new StringBuilder();
        artifactNameBuilder.append(artifact.getArtifactId());
        artifactNameBuilder.append('-');
        artifactNameBuilder.append(artifact.getVersion());
        if ( artifact.getClassifier() != null ) {
            artifactNameBuilder.append('-');
            artifactNameBuilder.append(artifact.getClassifier());
        }
        artifactNameBuilder.append('.');
        artifactNameBuilder.append(artifact.getType());
        final String artifactName = artifactNameBuilder.toString();

        final StringBuilder sb = new StringBuilder();
        sb.append(artifact.getGroupId().replace('.', File.separatorChar));
        sb.append(File.separatorChar);
        sb.append(artifact.getArtifactId());
        sb.append(File.separatorChar);
        sb.append(artifact.getVersion());
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
    private void copyArtifactToRepository(final ArtifactId artifactId,
            final File artifactDir)
    throws MojoExecutionException {
        final File artifactFile = getRepositoryFile(artifactDir, artifactId);
        // TODO - we could overwrite snapshots?
        if ( artifactFile.exists() ) {
            return;
        }
        final Artifact source = ProjectHelper.getOrResolveArtifact(this.project,
                this.mavenSession,
                this.artifactHandlerManager,
                this.resolver,
                artifactId);

        try {
            FileUtils.copyFile(source.getFile(), artifactFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to copy artifact from " + source.getFile(), e);
        }
    }

}
