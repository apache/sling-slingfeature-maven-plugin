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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Configuration;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.maven.ProjectHelper;
import org.apache.sling.feature.scanner.BundleDescriptor;
import org.apache.sling.feature.scanner.FeatureDescriptor;
import org.apache.sling.feature.scanner.PackageInfo;
import org.apache.sling.feature.scanner.Scanner;


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

    public enum DUPLICATE {
        bundles,
        configurations,
        artifacts,
        frameworkproperties
    }

    private static final String FILE_EXPORT_PACKAGES = "export-packages.txt";

    private static final String FILE_DUPLICATES_REPORT = "duplicates-report.txt";

    private static final String DUPLICATES_ALL = "all";

    private static final String DUPLICATES_BUNDLES = "bundles";

    private static final String DUPLICATES_CONFIGURATIONS = "configurations";

    private static final String DUPLICATES_ARTIFACTS = "artifacts";

    private static final String DUPLICATES_PROPERTIES = "framework-properties";

    @Parameter(property = "featureFile")
    private File featureFile;

    @Parameter(property = "outputExportedPackages", defaultValue = "true")
    private boolean outputExportedPackages;

    @Parameter(property = "outputDuplicates")
    private String outputDuplicates;

    @Parameter(readonly = true, defaultValue = "${project.build.directory}")
    private File buildDirectory;

    /**
     * Select the features for info generation.
     */
    @Parameter
    private FeatureSelectionConfig infoFeatures;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final boolean isStandalone = "standalone-pom".equals(project.getArtifactId());

        final List<Map.Entry<Feature, File>> selection = selectFeatures(isStandalone);

        if (outputExportedPackages) {
            // setup scanner
            final Scanner scanner = setupScanner();
            for(final Map.Entry<Feature, File> entry : selection ) {
                process(scanner, entry.getKey(), entry.getValue());
            }
        }
        if ( selection.size() > 1 && this.outputDuplicates != null ) {
            processDuplicates(this.outputDuplicates, selection);
        }
    }


    /**
     * Select the features to process
     * @throws MojoExecutionException
     */
    private List<Map.Entry<Feature, File>>  selectFeatures(final boolean isStandalone) throws MojoExecutionException {
        final List<Map.Entry<Feature, File>> result = new ArrayList<>();
        if (isStandalone || featureFile != null) {
            final Map.Entry<Feature, File> entry = new MapEntry(readFeature(), Paths.get(".").toAbsolutePath().getParent().toFile());

            result.add(entry);
        } else {
            checkPreconditions();

            final Map<String, Feature> features = infoFeatures == null ? this.selectAllFeatureFiles() : this.getSelectedFeatures(infoFeatures);
            for (final Feature f : features.values()) {
                final Map.Entry<Feature, File> entry = new MapEntry(f, new File(
                        this.project.getBuild().getDirectory() + File.separator + f.getId().toMvnName() + ".info"));
                result.add(entry);
            }
        }
        return result;
    }

    private Set<DUPLICATE> getDuplicateConfiguration(final String duplicatesConfig) throws MojoExecutionException {
        final Set<DUPLICATE> cfg = new HashSet<>();
        for(final String c : duplicatesConfig.split(",")) {
            final String value = c.trim();
            boolean valid = false;
            if ( DUPLICATES_ARTIFACTS.equals(value) || DUPLICATES_ALL.equals(value)) {
                cfg.add(DUPLICATE.artifacts);
                valid = true;
            }
            if ( DUPLICATES_BUNDLES.equals(value) || DUPLICATES_ALL.equals(value)) {
                cfg.add(DUPLICATE.bundles);
                valid = true;
            }
            if ( DUPLICATES_CONFIGURATIONS.equals(value) || DUPLICATES_ALL.equals(value)) {
                cfg.add(DUPLICATE.configurations);
                valid = true;
            }
            if ( DUPLICATES_PROPERTIES.equals(value) || DUPLICATES_ALL.equals(value)) {
                cfg.add(DUPLICATE.frameworkproperties);
                valid = true;
            }
            if ( !valid) {
                throw new MojoExecutionException("Invalid configuration value for duplicates : " + value);
            }
        }
        return cfg;
    }

    private void processDuplicates(final String duplicatesConfig, List<Map.Entry<Feature, File>> selection) throws MojoExecutionException {
         final Set<DUPLICATE> cfg = getDuplicateConfiguration(duplicatesConfig);

         final Map<String, List<ArtifactId>> artifactMap = new TreeMap<>();
         final Map<String, List<ArtifactId>> bundleMap = new TreeMap<>();
         final Map<String, List<ArtifactId>> configMap = new TreeMap<>();
         final Map<String, List<ArtifactId>> propsMap = new TreeMap<>();

         for(final Map.Entry<Feature, File> entry : selection) {
             final Feature feature = entry.getKey();
             if ( cfg.contains(DUPLICATE.artifacts)) {
                 for(final Extension ext : feature.getExtensions()) {
                     if ( ext.getType() == ExtensionType.ARTIFACTS ) {
                         for(final Artifact a : ext.getArtifacts()) {
                             artifactMap.putIfAbsent(a.getId().toMvnId(), new ArrayList<>());
                             artifactMap.get(a.getId().toMvnId()).add(feature.getId());
                         }
                     }
                 }
             }

             if ( cfg.contains(DUPLICATE.bundles)) {
                 for(final Artifact a : feature.getBundles()) {
                     bundleMap.putIfAbsent(a.getId().toMvnId(), new ArrayList<>());
                     bundleMap.get(a.getId().toMvnId()).add(feature.getId());
                 }
             }

             if ( cfg.contains(DUPLICATE.configurations)) {
                 for(final Configuration c : feature.getConfigurations()) {
                     configMap.putIfAbsent(c.getPid(), new ArrayList<>());
                     configMap.get(c.getPid()).add(feature.getId());
                 }
             }

             if ( cfg.contains(DUPLICATE.frameworkproperties)) {
                 for(final String a : feature.getFrameworkProperties().keySet()) {
                     propsMap.putIfAbsent(a, new ArrayList<>());
                     propsMap.get(a).add(feature.getId());
                 }
             }
         }
         final List<String> output = new ArrayList<>();
         outputDuplicates(output, DUPLICATES_PROPERTIES, propsMap);
         outputDuplicates(output, DUPLICATES_BUNDLES, bundleMap);
         outputDuplicates(output, DUPLICATES_CONFIGURATIONS, configMap);
         outputDuplicates(output, DUPLICATES_ARTIFACTS, artifactMap);
         if ( output.isEmpty()) {
             output.add("No duplicates found");
         }

         final File out = new File(this.buildDirectory, FILE_DUPLICATES_REPORT);
         try {
             Files.write(out.toPath(), output);
         } catch (IOException e) {
             throw new MojoExecutionException("Unable to write report", e);
         }
         getLog().info("Generated duplicates report at " + out);
    }

    private void outputDuplicates(final List<String> output, final String key, final Map<String, List<ArtifactId>> duplicates) {
        boolean writeHeader;
        if ( !duplicates.isEmpty() ) {
            writeHeader = true;
            for(final Map.Entry<String, List<ArtifactId>> entry : duplicates.entrySet()) {
                if ( entry.getValue().size() > 1 ) {
                    if ( writeHeader ) {
                        writeHeader = false;
                        output.add(key);
                        output.add("-------------------------------------------");
                    }
                    output.add(entry.getKey().concat(" : ").concat(entry.getValue().stream().map(id -> id.getClassifier()).collect(Collectors.toList()).toString()));
                }
            }
            if ( !writeHeader ) {
                output.add("");
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

    private static final class MapEntry implements Map.Entry<Feature, File> {

        private final Feature feature;
        private final File file;

        public MapEntry(final Feature f, final File file) {
            this.feature = f;
            this.file = file;
        }

        @Override
        public Feature getKey() {
            return this.feature;
        }

        @Override
        public File getValue() {
            return this.file;
        }

        @Override
        public File setValue(File value) {
            return null;
        }
    }
}
