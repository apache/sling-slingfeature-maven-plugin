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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.Deflater;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.io.archive.ArchiveWriter;
import org.apache.sling.feature.maven.ProjectHelper;

/**
 * Create a feature model archive and attach it. An archive is created for each
 * feature of this project
 */
@Mojo(
        name = "attach-featurearchives",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true
    )
public class AttachFeatureArchive extends AbstractFeatureMojo {

    private static final String EXTENSION = "zip";

    private static final String CLASSIFIER = "far";

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();
        this.attachArchives(ProjectHelper.getFeatures(this.project));
    }

    /**
     * Attach archives for all features
     *
     * @throws MojoExecutionException
     */
    void attachArchives(final Map<String, Feature> features) throws MojoExecutionException {
        for (final Map.Entry<String, Feature> entry : features.entrySet()) {
            final boolean add;
            if (ProjectHelper.isAggregate(entry.getKey())) {
                add = ProjectHelper.isAttachAggregate(entry.getKey());
            } else {
                add = true;
            }

            if (add) {
                attachArchive(entry.getValue());
            }
        }
    }

    public static final String ATTR_BUILT_BY = "Built-By";

    public static final String ATTR_CREATED_BY = "Created-By";

    public static final String ATTR_IMPLEMENTATION_VERSION = "Implementation-Version";

    public static final String ATTR_IMPLEMENTATION_VENDOR = "Implementation-Vendor";

    public static final String ATTR_IMPLEMENTATION_BUILD = "Implementation-Build";

    public static final String ATTR_IMPLEMENTATION_VENDOR_ID = "Implementation-Vendor-Id";

    public static final String ATTR_IMPLEMENTATION_TITLE = "Implementation-Title";

    public static final String ATTR_SPECIFICATION_TITLE = "Specification-Title";

    public static final String ATTR_SPECIFICATION_VENDOR = "Specification-Vendor";

    public static final String ATTR_SPECIFICATION_VERSION = "Specification-Version";

    private Manifest createBaseManifest(final Feature feature) {
        final Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Implementation-Build", project.getVersion());
        mf.getMainAttributes().putValue("Implementation-Version", project.getVersion());
        mf.getMainAttributes().putValue("Specification-Version", project.getVersion());

        if (feature.getVendor() != null) {
            mf.getMainAttributes().putValue("Implementation-Vendor", feature.getVendor());
            mf.getMainAttributes().putValue("Created-By", feature.getVendor());
            mf.getMainAttributes().putValue("Built-By", feature.getVendor());
            mf.getMainAttributes().putValue("Specification-Vendor", feature.getVendor());
        }

        mf.getMainAttributes().putValue("Implementation-Vendor-Id", feature.getId().getGroupId());
        if (feature.getTitle() != null) {
            mf.getMainAttributes().putValue("Implementation-Title", feature.getTitle());
            mf.getMainAttributes().putValue("Specification-Title", feature.getTitle());
        }

        return mf;
    }

    private void attachArchive(final Feature feature) throws MojoExecutionException {
        final String classifier = feature.getId().getClassifier() == null ? CLASSIFIER
                : feature.getId().getClassifier().concat(CLASSIFIER);

        final ArtifactId archiveId = new ArtifactId(feature.getId().getGroupId(), feature.getId().getArtifactId(),
                feature.getId().getVersion(), classifier, EXTENSION);

        // write the feature model archive
        final File outputFile = new File(
                this.project.getBuild().getDirectory().concat(File.separator).concat(archiveId.toMvnName()));
        outputFile.getParentFile().mkdirs();

        try ( final FileOutputStream fos = new FileOutputStream(outputFile)) {
            final JarOutputStream jos = ArchiveWriter.write(fos, feature, createBaseManifest(feature),
                    new ArtifactProvider() {

                @Override
                public URL provide(final ArtifactId id) {
                    try {
                        return ProjectHelper.getOrResolveArtifact(project, mavenSession, artifactHandlerManager,
                                artifactResolver, id).getFile().toURI().toURL();
                    } catch (final MalformedURLException e) {
                        getLog().debug("Malformed url " + e.getMessage(), e);
                        // ignore
                        return null;
                    }
                }
            });

            // handle license etc.
            jos.setLevel(Deflater.BEST_COMPRESSION);
            final File classesDir = new File(this.project.getBuild().getOutputDirectory());
            if ( classesDir.exists() ) {
                final File metaInfDir = new File(classesDir, "META-INF");
                for(final String name : new String[] {"LICENSE", "NOTICE", "DEPENDENCIES"}) {
                    final File f = new File(metaInfDir, name);
                    if ( f.exists() ) {
                        final JarEntry artifactEntry = new JarEntry("META-INF/" + name);
                        jos.putNextEntry(artifactEntry);

                        final byte[] buffer = new byte[8192];
                        try (final InputStream is = new FileInputStream(f)) {
                            int l = 0;
                            while ( (l = is.read(buffer)) > 0 ) {
                                jos.write(buffer, 0, l);
                            }
                        }
                        jos.closeEntry();

                    }
                }
            }
            jos.finish();
        } catch (final IOException e) {
            throw new MojoExecutionException(
                    "Unable to write feature model archive to " + outputFile + " : " + e.getMessage(), e);
        }

        // attach it as an additional artifact
        projectHelper.attachArtifact(project, archiveId.getType(), archiveId.getClassifier(), outputFile);
    }
}
