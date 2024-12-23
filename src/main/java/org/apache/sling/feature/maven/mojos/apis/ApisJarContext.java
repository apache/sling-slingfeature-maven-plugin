/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.feature.maven.mojos.apis;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.apache.felix.utils.manifest.Clause;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;

/**
 * Context for creating the api jars
 */
public class ApisJarContext {

    /**
     * Information about a single artifact (bundle) taking part in the api generation.
     */
    public static final class ArtifactInfo {

        private Artifact artifact;

        private File binDirectory;

        private File sourceDirectory;

        /** Exported packages used by any region. */
        private Set<String> usedExportedPackages;

        /** Exported packages per region. */
        private final Map<String, Set<Clause>> usedExportedPackagesPerRegion = new HashMap<>();

        /** Flag if used as dependency */
        private final Map<String, String> useAsDependencyPerRegion = new HashMap<>();

        private final Set<File> includedResources = new HashSet<>();

        private final Set<String> nodeTypes = new HashSet<>();

        private List<License> licenses;

        private final Set<String> sources = new HashSet<>();

        private final Map<String, Set<Clause>> providedCapabilitiesPerRegion = new HashMap<>();

        public ArtifactInfo(final Artifact artifact) {
            this.artifact = artifact;
        }

        public ArtifactId getId() {
            return this.artifact.getId();
        }

        public Artifact getArtifact() {
            return this.artifact;
        }

        public File getBinDirectory() {
            return binDirectory;
        }

        public void setBinDirectory(File binDirectory) {
            this.binDirectory = binDirectory;
        }

        public File getSourceDirectory() {
            return sourceDirectory;
        }

        public void setSourceDirectory(File sourceDirectory) {
            this.sourceDirectory = sourceDirectory;
        }

        public Set<String> getUsedExportedPackages() {
            return usedExportedPackages;
        }

        public void setUsedExportedPackages(Set<String> usedExportedPackages) {
            this.usedExportedPackages = usedExportedPackages;
        }

        public String[] getUsedExportedPackageIncludes() {
            final Set<String> includes = new HashSet<>();
            for (final String pck : usedExportedPackages) {
                includes.add(pck.replace('.', '/').concat("/*"));
            }
            return includes.toArray(new String[includes.size()]);
        }

        public Set<Clause> getUsedExportedPackages(final String regionName) {
            return this.usedExportedPackagesPerRegion.get(regionName);
        }

        public void setUsedExportedPackages(
                final String regionName, final Set<Clause> usedExportedPackages, final String useAsDependency) {
            this.usedExportedPackagesPerRegion.put(regionName, usedExportedPackages);
            if (useAsDependency != null) {
                this.useAsDependencyPerRegion.put(regionName, useAsDependency);
            }
        }

        public String[] getUsedExportedPackageIncludes(final String regionName) {
            final Set<Clause> clauses = this.getUsedExportedPackages(regionName);
            final Set<String> includes = new HashSet<>();
            for (final Clause clause : clauses) {
                includes.add(clause.getName().replace('.', '/').concat("/*"));
            }
            return includes.toArray(new String[includes.size()]);
        }

        public boolean isUseAsDependencyPerRegion(final String regionName) {
            return this.useAsDependencyPerRegion.get(regionName) == null;
        }

        public String getNotUseAsDependencyPerRegionReason(final String regionName) {
            return this.useAsDependencyPerRegion.get(regionName);
        }

        public Set<File> getIncludedResources() {
            return includedResources;
        }

        /**
         * Get all node types from this artifact
         * @return The set of node types, might be empty
         */
        public Set<String> getNodeTypes() {
            return this.nodeTypes;
        }

        public List<License> getLicenses() {
            return licenses;
        }

        public void setLicenses(List<License> licenses) {
            this.licenses = licenses;
        }

        /**
         * Get the dependency artifacts
         * <ol>
         * <li>If {@code ApisUtil#API_IDS} is provided as metadata for the artifact,
         * the value is used as a list of artifacts
         * <li>If {@code ApisUtil#SCM_IDS} is provided as metadata for the artifact,
         * the value is used as a list of artifacts. The artifacts must have a classifier
         * set. The classifier is removed and then the artifacts are used
         * <li>The artifact itself is used
         * </ol>
         * @return The list of dependency artifacts
         * @throws MojoExecutionException If an incorrect configuration is found
         */
        public List<ArtifactId> getDependencyArtifacts() throws MojoExecutionException {
            final List<ArtifactId> dependencies = new ArrayList<>();
            final List<ArtifactId> apiIds = ApisUtil.getApiIds(artifact);
            if (apiIds != null) {
                for (final ArtifactId id : apiIds) {
                    dependencies.add(id);
                }
            } else {
                final List<ArtifactId> sourceIds = ApisUtil.getSourceIds(artifact);
                if (sourceIds != null) {
                    for (final ArtifactId id : sourceIds) {
                        dependencies.add(id.changeClassifier(null));
                    }
                } else {
                    dependencies.add(getId());
                }
            }
            return dependencies;
        }

        public void addSourceInfo(final ArtifactId id) {
            if (id != null) {
                this.sources.add(id.toMvnId());
            }
        }

        public void addSourceInfo(final String connection) {
            if (connection != null) {
                this.sources.add(connection);
            }
        }

        public Set<String> getSources() {
            return this.sources;
        }

        public Set<Clause> getProvidedCapabilities(final String regionName) {
            return providedCapabilitiesPerRegion.get(regionName);
        }

        public void setProvidedCapabilities(final String regionName, final Set<Clause> providedCapabilitiesPerRegion) {
            this.providedCapabilitiesPerRegion.put(regionName, providedCapabilitiesPerRegion);
        }

        /* (non-Javadoc)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return Objects.hash(artifact);
        }

        /* (non-Javadoc)
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ArtifactInfo)) return false;
            ArtifactInfo other = (ArtifactInfo) obj;
            return Objects.equals(artifact, other.artifact);
        }
    }

    private final ApisConfiguration config;

    private final Map<ArtifactId, String> javadocClasspath = new HashMap<>();

    private final Set<String> packagesWithoutJavaClasses = new HashSet<>();

    private final Set<String> packagesWithoutSources = new HashSet<>();

    private final File deflatedBinDir;

    private final File deflatedSourcesDir;

    private final File checkedOutSourcesDir;

    private File javadocDir;

    private final List<ArtifactInfo> infos = new ArrayList<>();

    private final Feature feature;

    private final Map<ArtifactId, Model> modelCache = new HashMap<>();

    public ApisJarContext(final File mainDir, final Feature feature) throws MojoExecutionException {
        this.config = new ApisConfiguration(feature);
        this.feature = feature;

        // deflated and source dirs can be shared
        this.deflatedBinDir = new File(mainDir, "deflated-bin");
        this.deflatedSourcesDir = new File(mainDir, "deflated-sources");
        this.checkedOutSourcesDir = new File(mainDir, "checkouts");
    }

    public ApisConfiguration getConfig() {
        return this.config;
    }

    public ArtifactId getFeatureId() {
        return feature.getId();
    }

    public Feature getFeature() {
        return this.feature;
    }

    public File getDeflatedBinDir() {
        return deflatedBinDir;
    }

    public File getDeflatedSourcesDir() {
        return deflatedSourcesDir;
    }

    public File getCheckedOutSourcesDir() {
        return checkedOutSourcesDir;
    }

    public void addJavadocClasspath(final ArtifactId artifactId, final String classpath) {
        javadocClasspath.put(artifactId, classpath);
    }

    public Map<ArtifactId, String> getJavadocClasspath() {
        return javadocClasspath;
    }

    public File getJavadocDir() {
        return javadocDir;
    }

    public void setJavadocDir(final File javadocDir) {
        this.javadocDir = javadocDir;
    }

    public Set<String> getPackagesWithoutJavaClasses() {
        return packagesWithoutJavaClasses;
    }

    public Set<String> getPackagesWithoutSources() {
        return packagesWithoutSources;
    }

    public ArtifactInfo addArtifactInfo(final Artifact artifact) {
        final ArtifactInfo info = new ArtifactInfo(artifact);
        this.infos.add(info);

        return info;
    }

    public ArtifactInfo getArtifactInfo(final ArtifactId artifactId) {
        for (final ArtifactInfo i : this.infos) {
            if (i.getArtifact().getId().equals(artifactId)) {
                return i;
            }
        }
        return null;
    }

    public List<ArtifactInfo> getArtifactInfos() {
        return this.infos;
    }

    public Map<ArtifactId, Model> getModelCache() {
        return this.modelCache;
    }

    public Collection<ArtifactInfo> getArtifactInfos(final String regionName, final boolean omitDependencyArtifacts) {
        final Map<ArtifactId, ArtifactInfo> result = new TreeMap<>();
        for (final ArtifactInfo info : this.infos) {
            final Set<Clause> pcks = info.getUsedExportedPackages(regionName);
            if (pcks != null && !pcks.isEmpty()) {
                if (!omitDependencyArtifacts || !info.isUseAsDependencyPerRegion(regionName)) {
                    result.put(info.getId(), info);
                }
            }
        }
        return result.values();
    }

    /**
     * Find a an artifact
     * If dependency repositories are configured, one of them must provide the artifact
     * @param log Logger
     * @param id The artifact id
     * @return {@code true} if the artifact could be found.
     * @throws MojoExecutionException
     */
    private boolean findDependencyArtifact(final Log log, final ArtifactId id) throws MojoExecutionException {
        boolean result = true;
        if (!this.getConfig().getDependencyRepositories().isEmpty()) {
            result = false;
            log.debug("Trying to resolve "
                    .concat(id.toMvnId())
                    .concat(" from ")
                    .concat(this.getConfig().getDependencyRepositories().toString()));
            for (final String server : this.getConfig().getDependencyRepositories()) {
                try {
                    final URL url = new URL(server.concat(id.toMvnPath()));
                    try {
                        url.openConnection().getInputStream().close();
                        log.debug("Found ".concat(id.toMvnId()).concat(" at ").concat(url.toString()));
                        result = true;
                        break;
                    } catch (IOException e) {
                        // not available
                        log.debug("Missed "
                                .concat(id.toMvnId())
                                .concat(" at ")
                                .concat(url.toString())
                                .concat(" : ")
                                .concat(e.toString()));
                    }
                } catch (final MalformedURLException mue) {
                    throw new MojoExecutionException("Unable to find dependency on ".concat(server), mue);
                }
            }
        }
        return result;
    }

    /**
     * Check if all dependency artifacts can be found
     * @param log The logger
     * @param info The artifact info
     * @return {@code true} if all artifacts are publically available
     * @throws MojoExecutionException If an incorrect configuration is found
     */
    public boolean findDependencyArtifact(final Log log, final ArtifactInfo info) throws MojoExecutionException {
        boolean result = true;
        for (final ArtifactId id : info.getDependencyArtifacts()) {
            if (!findDependencyArtifact(log, id)) {
                result = false;
                break;
            }
        }
        return result;
    }
}
