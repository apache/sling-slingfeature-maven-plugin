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
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.maven.ProjectHelper;
import org.apache.sling.feature.scanner.BundleDescriptor;
import org.apache.sling.feature.scanner.FeatureDescriptor;
import org.apache.sling.feature.scanner.PackageInfo;
import org.apache.sling.feature.scanner.Scanner;

import edu.emory.mathcs.backport.java.util.Collections;

/**
 * Extract information from a feature This mojo does not require a project, it
 * can be run by just pointing it at a feature file. When run from within a
 * project, the normal feature selection mechanism can be used.
 *
 * This mojo currently only extracts the exported packages and writes them to a
 * file.
 *
 */
@Mojo(requiresProject = false, name = "info", threadSafe = true)
public class InfoMojo extends AbstractIncludingFeatureMojo {

    private static final String FILE_EXPORT_PACKAGES = "export-packages.txt";

    @Parameter(property = "featureFile")
    private File featureFile;

    @Parameter(property = "outputExportedPackages", defaultValue = "true")
    private boolean outputExportedPackages;

    @Parameter(readonly = true, defaultValue = "${project.build.directory}")
    private File buildDirectory;

    /**
     * Select the features for api generation.
     */
    @Parameter
    private FeatureSelectionConfig infoFeatures;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final boolean isStandalone = "standalone-pom".equals(project.getArtifactId());

        // setup scanner
        final Scanner scanner = setupScanner();

        if (isStandalone) {
            final Feature feature = readFeature();
            // wired code to get the current directory, but its needed
            process(scanner, feature, Paths.get(".").toAbsolutePath().getParent().toFile());
        } else {
            checkPreconditions();

            final Map<String, Feature> features = this.getSelectedFeatures(infoFeatures);
            for (final Feature f : features.values()) {
                process(scanner, f, new File(
                        this.project.getBuild().getDirectory() + File.separator + f.getId().toMvnName() + ".info"));
            }
        }
    }

    private void process(final Scanner scanner, final Feature feature, final File directory)
            throws MojoExecutionException {
        getLog().info("Processing feature ".concat(feature.getId().toMvnId()));
        FeatureDescriptor fd;
        try {
            fd = scanner.scan(feature);
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to scan feature " + e.getMessage(), e);
        }

        processExportedPackages(fd, directory);
    }

    private void processExportedPackages(final FeatureDescriptor fd, final File directory)
            throws MojoExecutionException {
        if (outputExportedPackages) {
            final List<String> exportedPackages = this.getExportedPackages(fd);

            final File out = new File(directory, FILE_EXPORT_PACKAGES);
            getLog().info("- writing ".concat(out.getAbsolutePath()));
            this.writeFile(out, exportedPackages);
        }

    }

    private Scanner setupScanner() throws MojoExecutionException {
        final ArtifactProvider am = new ArtifactProvider() {

            @Override
            public URL provide(final ArtifactId id) {
                try {
                    return ProjectHelper
                            .getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, id)
                            .getFile().toURI().toURL();
                } catch (final MalformedURLException e) {
                    getLog().debug("Malformed url " + e.getMessage(), e);
                    // ignore
                    return null;
                }
            }
        };

        try {
            return new Scanner(am);
        } catch (final IOException e) {
            throw new MojoExecutionException("A fatal error occurred while setting up the Scanner, see error cause:",
                    e);
        }
    }

    private Feature readFeature() throws MojoExecutionException {
        if (featureFile == null) {
            throw new MojoExecutionException("No feature file specified");
        }

        try (final Reader reader = new FileReader(this.featureFile)) {
            final Feature f = FeatureJSONReader.read(reader, this.featureFile.getAbsolutePath());

            return f;

        } catch (final IOException ioe) {
            throw new MojoExecutionException("Unable to read feature file " + ioe.getMessage(), ioe);
        }
    }

    private List<String> getExportedPackages(final FeatureDescriptor fd) {
        final List<String> packages = new ArrayList<>();

        for (final BundleDescriptor bd : fd.getBundleDescriptors()) {
            for (PackageInfo p : bd.getExportedPackages()) {
                packages.add(p.getName());
            }
        }

        Collections.sort(packages);
        return packages;
    }

    private void writeFile(final File output, final List<String> infos) throws MojoExecutionException {
        output.getParentFile().mkdirs();
        try (final Writer fw = new FileWriter(output)) {
            for (final String p : infos) {
                fw.write(p);
                fw.write(System.getProperty("line.separator"));
            }
        } catch (final IOException ioe) {
            throw new MojoExecutionException("Unable to write output file " + ioe.getMessage(), ioe);
        }
    }
}
