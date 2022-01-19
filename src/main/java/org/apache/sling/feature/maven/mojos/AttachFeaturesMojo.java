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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.IOUtils;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.ProjectHelper;
import org.osgi.framework.Constants;

/**
 * Attach the feature as a project artifact.
 */
@Mojo(name = "attach-features",
        defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.TEST,
      threadSafe = true
    )
public class AttachFeaturesMojo extends AbstractFeatureMojo {
    /**
     * Attach main features (source)
     */
    @Parameter(name = "attachMainFeatures", defaultValue = "true")
    private boolean attachMainFeatures;

    /**
     * Attach test features (source)
     */
    @Parameter(name = "attachTestFeatures",
            defaultValue = "false")
    private boolean attachTestFeatures;

    /**
     * Create reference file
     */
    @Parameter(name = "createReferenceFile", defaultValue = "false")
    private boolean createReferenceFile;

    /**
     * Classifier for the reference file (if any)
     */
    @Parameter(name = "referenceFileClassifier")
    private String referenceFileClassifier;

    /**
     * Include bundle metadata
     * @since 1.6.0
     */
    @Parameter(name = "includeBundleMetadata", defaultValue = "false")
    private boolean includeBundleMetadata;

    /** Shared metadata cache */
    private static final Map<String, Map.Entry<String, String>> METADATA_CACHE = new ConcurrentHashMap<>();

    /** Not found entry */
    private static final Map.Entry<String, String> NOT_FOUND = new AbstractMap.SimpleImmutableEntry<>("NULL", "NULL");

    private void attach(final Feature feature)
    throws MojoExecutionException {
        final String classifier = feature.getId().getClassifier();

        boolean changed = false;
        // check for metadata
        if ( this.includeBundleMetadata ) {
            for(final Artifact bundle : feature.getBundles()) {
                if ( bundle.getMetadata().get(Constants.BUNDLE_SYMBOLICNAME) == null ) {
                     Map.Entry<String, String> value = METADATA_CACHE.get(bundle.getId().toMvnId());
                     if ( value == null ) {
                        final org.apache.maven.artifact.Artifact source = ProjectHelper.getOrResolveArtifact(this.project,
                             this.mavenSession,
                            this.artifactHandlerManager,
                            this.artifactResolver,
                            bundle.getId());
        
                        try (final JarFile jarFile = new JarFile(source.getFile())) {
                            final String symbolicName = jarFile.getManifest().getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME);
                            final String version = jarFile.getManifest().getMainAttributes().getValue(Constants.BUNDLE_VERSION);
                            if ( symbolicName != null && version != null ) {
                                final int idx = symbolicName.indexOf(";");
                                value = new AbstractMap.SimpleImmutableEntry<>(idx == -1 ? symbolicName : symbolicName.substring(0, idx), version);
                            }
                        } catch (final IOException e) {
                            // we ignore this
                        }
                        if ( value == null ) {
                            value = NOT_FOUND;
                        }
                        METADATA_CACHE.put(bundle.getId().toMvnId(), value);
                     }
                     if ( value != NOT_FOUND ) {
                         bundle.getMetadata().put(Constants.BUNDLE_SYMBOLICNAME, value.getKey());
                         bundle.getMetadata().put(Constants.BUNDLE_VERSION, value.getValue());
                         changed = true;
                     }
                }
            }
        }

        // write the feature
        final File outputFile = ProjectHelper.createTmpFeatureFile(project, feature, changed);

        // if this project is a feature, it's the main artifact
        if ( project.getPackaging().equals(FeatureConstants.PACKAGING_FEATURE)
             && classifier == null) {
            project.getArtifact().setFile(outputFile);
        } else {
            // otherwise attach it as an additional artifact
            projectHelper.attachArtifact(project, FeatureConstants.PACKAGING_FEATURE,
                    classifier, outputFile);
        }
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();
        final List<String> featureUrls = new ArrayList<>();
        this.attachClassifierFeatures(ProjectHelper.getFeatures(this.project), featureUrls, this.attachMainFeatures);
        this.attachClassifierFeatures(ProjectHelper.getTestFeatures(this.project), featureUrls,
                this.attachTestFeatures);

        if (this.createReferenceFile) {
            this.createReferenceFile(featureUrls);
        }
    }

    private void createReferenceFile(final List<String> featureUrls) throws MojoExecutionException {
        if (featureUrls.isEmpty()) {
            getLog().warn(
                    "Create reference file is enabled but no features are attached. Skipping reference file generation.");
        } else {
            String fileName = "references";
            if (this.referenceFileClassifier != null) {
                fileName = fileName.concat("-").concat(this.referenceFileClassifier);
            }
            fileName = fileName.concat(IOUtils.EXTENSION_REF_FILE);
            final File outputFile = new File(this.getTmpDir(), fileName);
            outputFile.getParentFile().mkdirs();

            try (final Writer w = new FileWriter(outputFile)) {
                for (final String url : featureUrls) {
                    w.write(url);
                    w.write('\n');
                }
            } catch (final IOException e) {
                throw new MojoExecutionException("Unable to write feature reference file to " + outputFile, e);
            }
            projectHelper.attachArtifact(project, "ref", this.referenceFileClassifier, outputFile);
        }
    }

    /**
     * Attach all features
     * @throws MojoExecutionException
     */
    void attachClassifierFeatures(final Map<String, Feature> features, final List<String> featureUrls,
            final boolean addSourceFeatures)
            throws MojoExecutionException {
        for (final Map.Entry<String, Feature> entry : features.entrySet()) {
            final boolean add;
            if (ProjectHelper.isAggregate(entry.getKey())) {
                add = ProjectHelper.isAttachAggregate(entry.getKey());
            } else {
                add = addSourceFeatures;
            }

            if (add) {
                attach(entry.getValue());
                featureUrls.add(entry.getValue().getId().toMvnUrl());
            }
        }
    }
}
