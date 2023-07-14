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

import static org.apache.sling.feature.diff.FeatureDiff.compareFeatures;
import static org.apache.sling.feature.io.json.FeatureJSONWriter.write;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.diff.DiffRequest;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.maven.FeatureConstants;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.version.Version;

/**
 * Compares different versions of the same Feature Model.
 */
@Mojo(name = "features-diff",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
public final class FeaturesDiffMojo extends AbstractIncludingFeatureMojo {

    @Parameter
    private FeatureSelectionConfig selection;

    @Parameter(defaultValue = "${project.build.directory}/features-diff", readonly = true)
    private File mainOutputDir;

    @Parameter(defaultValue = "(,${project.version})")
    protected String comparisonVersion;

    @Component
    protected RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().debug("Retrieving Feature files...");
        final Collection<Feature> features = getSelectedFeatures(selection).values();

        if (features.isEmpty()) {
            getLog().debug("There are no assciated Feature files to current project, plugin execution will be interrupted");
            return;
        }

        if (!mainOutputDir.exists()) {
            mainOutputDir.mkdirs();
        }

        getLog().debug("Starting Feature(s) analysis...");

        for (final Feature feature : features) {
            onFeature(feature);
        }
    }

    private void onFeature(Feature current) throws MojoExecutionException, MojoFailureException {
        Feature previous = getPreviousFeature(current);

        if (previous == null) {
            getLog().info("There is no previous verion available of " + current + " model");
            return;
        }

        getLog().info("Comparing current " + current + " to previous " + previous);

        Feature featureDiff = compareFeatures(new DiffRequest()
                                              .setPrevious(previous)
                                              .setCurrent(current));

        File outputDiffFile = new File(mainOutputDir, featureDiff.getId().getClassifier().concat(".json"));

        getLog().info("Rendering differences to file " + outputDiffFile);

        try (FileWriter writer = new FileWriter(outputDiffFile)) {
            write(writer, featureDiff);
        } catch (IOException e) {
            throw new MojoExecutionException("An error occurred while serializing Feature diff to " + outputDiffFile, e);
        }

        projectHelper.attachArtifact(project,
                                     FeatureConstants.PACKAGING_FEATURE,
                                     featureDiff.getId().getClassifier(),
                                     outputDiffFile);
    }

    private Feature getPreviousFeature(Feature current) throws MojoExecutionException, MojoFailureException {
        VersionRange range;
        try {
            range = VersionRange.createFromVersionSpec(comparisonVersion);
        } catch (InvalidVersionSpecificationException e) {
            throw new MojoFailureException("Invalid comparison version: " + e.getMessage());
        }

        org.eclipse.aether.artifact.Artifact previousArtifact = new DefaultArtifact(current.getId().getGroupId(),
                                                                current.getId().getArtifactId(),
                                                                current.getId().getType(),
                                                                current.getId().getClassifier(),
                                                                comparisonVersion);

        try {
            
            if (!range.isSelectedVersionKnown(RepositoryUtils.toArtifact(previousArtifact))) {
                getLog().debug("Searching for versions in range: " + comparisonVersion);
                VersionRangeRequest vrr = new VersionRangeRequest(previousArtifact, project.getRemoteProjectRepositories(), null);
                VersionRangeResult vrResult = repoSystem.resolveVersionRange(mavenSession.getRepositorySession(), vrr);
                // filter out snapshots
                Optional<Version> version = vrResult.getVersions().stream().filter(v -> !ArtifactUtils.isSnapshot(v.toString())).reduce((first, second) -> second);
                previousArtifact.setVersion(version.map(Version::toString).orElse(null));
            }
        } catch (OverConstrainedVersionException ocve) {
            throw new MojoFailureException("Invalid comparison version: " + ocve.getMessage());
        } catch (VersionRangeResolutionException e) {
            throw new MojoExecutionException("Error determining previous version: " + e.getMessage(), e);
        }

        if (previousArtifact.getVersion() == null) {
            getLog().info("Unable to find a previous version of the " + current + " Feature in the repository");
            return null;
        }

        File featureFile = null;
        try {
            ArtifactRequest artifactRequest = new ArtifactRequest(previousArtifact, project.getRemoteProjectRepositories(), null);
            ArtifactResult artifactResult = repoSystem.resolveArtifact(mavenSession.getRepositorySession(), artifactRequest);
            featureFile = artifactResult.getArtifact().getFile();
        } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
            getLog().warn("Artifact " + previousArtifact + " cannot be resolved : " + e.getMessage(), e);
        }

        if (featureFile == null || !featureFile.exists()) {
            return null;
        }

        try (FileReader reader = new FileReader(featureFile)) {
            return FeatureJSONReader.read(reader, featureFile.getAbsolutePath());
        } catch (IOException e) {
            throw new MojoExecutionException("An error occurred while reading the " + featureFile + " Feature file:", e);
        }
    }

}
