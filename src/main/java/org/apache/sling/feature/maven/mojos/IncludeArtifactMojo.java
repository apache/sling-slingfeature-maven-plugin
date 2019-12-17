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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.install.ArtifactInstaller;
import org.apache.maven.shared.transfer.artifact.install.ArtifactInstallerException;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Artifacts;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionState;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Extensions;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.BuilderContext;
import org.apache.sling.feature.builder.FeatureBuilder;
import org.apache.sling.feature.builder.MergeHandler;
import org.apache.sling.feature.builder.PostProcessHandler;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.ProjectHelper;

/**
 * This goal creates a Feature Model file that includes the Module Artifact as
 * bundle (or extension) so that the Artifact can be added through a FM into a
 * FM project. The FM file can be found in the 'build directory'/slingfeature-tmp
 * folder.
 * After a FM file is created successfully this file will be installed
 * in the local Maven Repository as 'slingosgifeature' file under the Module's Maven
 * Id location (group, artifact, version). This file can then later be used inside
 * the 'aggregate-features' goal with:
 * {@code
 * <includeArtifact>
 *     <groupId>org.apache.sling</groupId>
 *     <artifactId>org.apache.sling.test.feature</artifactId>
 *     <version>1.0.0</version>
 *     <classifier>my-test-classifier</classifier>
 *     <type>slingosgifeature</type>
 * </includeArtifact>
 * }
 * It also can add dependencies to the FM file if its scope is provided (normally
 * that would be 'compile'). In addition a bundle start order can be set for these
 * included dependency bundles.
 * Finally any FM files inside the Source FM folder are embedded into the FM file. This
 * allows to add extension files like 'repoinit' etc to be added to provide them with
 * the module.
 */
@Mojo(name = "include-artifact", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE,
      threadSafe = true
    )
public class IncludeArtifactMojo extends AbstractIncludingFeatureMojo {

    public static final String CFG_CLASSIFIER = "includeArtifactClassifier";
    public static final String CFG_START_ORDER = "bundleStartOrder";
    public static final String CFG_INCLUDE_DEPENDENCIES_WITH_SCOPE = "includeDependenciesWithScope";

    @Component
    protected ArtifactInstaller installer;

    /**
     * Classifier of the feature the current artifact is included in.
     */
    @Parameter(property = CFG_CLASSIFIER, required = true)
    private String includeArtifactClassifier;

    /**
     * Start Order of all included Dependencies.
     */
    @Parameter(property = CFG_START_ORDER, required = false, defaultValue = "-1")
    private int bundlesStartOrder;

    /**
     * All listed dependency's scopes will be added to the descriptor.
     */
    @Parameter(property = CFG_INCLUDE_DEPENDENCIES_WITH_SCOPE, required = false)
    private String[] includeDependenciesWithScope;

    /**
     * Name of the extension to include the artifact in. If not specified the
     * artifact is included as a bundle.
     */
    @Parameter
    private String includeArtifactExtension;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (includeArtifactClassifier == null || includeArtifactClassifier.isEmpty()) {
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
        getLog().debug("Found Feature: " + found + ", artifact: " + art);
        includeArtifact(found, includeArtifactExtension, art);
        getLog().debug("Feature Key: " + key + ", feature from key: " + ProjectHelper.getAssembledFeatures(this.project).get(key));
        includeArtifact(ProjectHelper.getAssembledFeatures(this.project).get(key), includeArtifactExtension,
                art.copy(art.getId()));
        // Add Dependencies if configured so
        for(String includeDependencyScope: includeDependenciesWithScope) {
            List<Dependency> dependencies = project.getDependencies();
            getLog().info("Project Dependencies: " + dependencies);
            for(Dependency dependency: dependencies) {
                if(includeDependencyScope.equals(dependency.getScope())) {
                    getLog().info("Include Artifact: " + dependencies);
                    ArtifactId id = new ArtifactId(
                        dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getClassifier(), dependency.getType()
                    );
                    Artifact artifact = new Artifact(id);
                    if(bundlesStartOrder >= 0) {
                        artifact.setStartOrder(bundlesStartOrder);
                    }
                    found.getBundles().add(artifact);
                }
            }
        }

        // Obtain any features from Source folder and add any Extensions to the target feature
        final FeatureSelectionConfig featureSelectionConfig = new FeatureSelectionConfig();
        featureSelectionConfig.setFilesInclude("**/*.json" );
        featureSelectionConfig.setFilesExclude("**/" + file.getName());
        final Map<String, Feature> selection = this.getSelectedFeatures(featureSelectionConfig);
        getLog().debug("Including Features found: " + selection);
        for(Feature feature: selection.values()) {
            getLog().debug("Including Feature found: " + feature);
            Extensions extensions = feature.getExtensions();
            if(extensions != null && !extensions.isEmpty()) {
                found.getExtensions().addAll(extensions);
            }
        }

        // Write the Feature into its rile
        if (file != null) {
            try ( final Writer writer = new FileWriter(file)) {
                FeatureJSONWriter.write(writer, found);
                installFMDescriptor(file, found);
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
            getLog().debug("Extension: " + extensionName + ", found extension: " + ext);
            if (ext == null) {
                ext = new Extension(ExtensionType.ARTIFACTS, extensionName, ExtensionState.REQUIRED);
                getLog().debug("New Extension: " + ext);
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

    private void installFMDescriptor(File file, Feature feature) {
        Collection<org.apache.maven.artifact.Artifact> artifacts = Collections.synchronizedCollection(new ArrayList<>());
        if(file.exists() && file.canRead()) {
            // Need to create a new Artifact Handler for the different extension and an Artifact to not
            // change the module artifact
            DefaultArtifactHandler fmArtifactHandler = new DefaultArtifactHandler("slingosgifeature");
            ArtifactId artifactId = feature.getId();
            DefaultArtifact fmArtifact = new DefaultArtifact(
                artifactId.getGroupId(), artifactId.getArtifactId(), artifactId.getVersion(),
                null, "slingosgifeature", artifactId.getClassifier(), fmArtifactHandler
            );
            fmArtifact.setFile(file);
            artifacts.add(fmArtifact);
            try {
                installArtifact(mavenSession.getProjectBuildingRequest(), artifacts);
            } catch (MojoExecutionException e) {
                getLog().error("Failed to install FM Descriptor", e);
            }
        } else {
            getLog().error("Could not find FM Descriptor File: " + file);
        }
    }

    private void installArtifact(ProjectBuildingRequest pbr, Collection<org.apache.maven.artifact.Artifact> artifacts )
        throws MojoExecutionException {
        try {
            installer.install(pbr, artifacts);
        } catch ( ArtifactInstallerException e ) {
            throw new MojoExecutionException( "ArtifactInstallerException", e );
        }
    }
}
