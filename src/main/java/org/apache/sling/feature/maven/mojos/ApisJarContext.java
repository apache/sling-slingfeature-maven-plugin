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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.felix.utils.manifest.Clause;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.extension.apiregions.api.ApiRegion;
import org.apache.sling.feature.extension.apiregions.api.ApiRegions;

/**
 * Context for creating the api jars
 */
class ApisJarContext {

    /**
     * Information about a single artifact (bundle) taking part in the api generation.
     */
    public static final class ArtifactInfo {

        private ArtifactId id;

        private File binDirectory;

        private File sourceDirectory;

        /** Exported packages used by all regions. */
        private Set<String> usedExportedPackages;

        /** Exported packages per region. */
        private final Map<String, Set<Clause>> usedExportedPackagesRegion = new HashMap<>();

        private final Set<File> includedResources = new HashSet<>();

        private final Set<String> nodeTypes = new HashSet<>();

        private List<License> licenses;

        public ArtifactInfo(final ArtifactId id) {
            this.id = id;
        }

        public ArtifactId getId() {
            return this.id;
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
            for(final String pck : usedExportedPackages) {
                includes.add(pck.replace('.', '/').concat("/*"));
            }
            return includes.toArray(new String[includes.size()]);
        }

        public Set<Clause> getUsedExportedPackages(final ApiRegion region) {
            return this.usedExportedPackagesRegion.get(region.getName());
        }

        public void setUsedExportedPackages(final ApiRegion region, final Set<Clause> usedExportedPackages) {
            this.usedExportedPackagesRegion.put(region.getName(), usedExportedPackages);
        }

        public String[] getUsedExportedPackageIncludes(final ApiRegion region) {
            final Set<Clause> clauses = this.getUsedExportedPackages(region);
            final Set<String> includes = new HashSet<>();
            for(final Clause clause : clauses) {
                includes.add(clause.getName().replace('.', '/').concat("/*"));
            }
            return includes.toArray(new String[includes.size()]);
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
    }

    private final Set<String> javadocClasspath = new HashSet<>();

    private final Set<String> packagesWithoutJavaClasses = new HashSet<>();

    private final File deflatedBinDir;

    private final File deflatedSourcesDir;

    private final File checkedOutSourcesDir;

    private File javadocDir;

    private final List<ArtifactInfo> infos = new ArrayList<>();

    private final ArtifactId featureId;

    private final ApiRegions apiRegions;

    private final Map<ArtifactId, Model> modelCache = new HashMap<>();

    private List<String[]> licenseDefaultMatches;

    public ApisJarContext(final File mainDir, final ArtifactId featureId, final ApiRegions regions) {
        this.featureId = featureId;

        // deflated and source dirs can be shared
        this.deflatedBinDir = new File(mainDir, "deflated-bin");
        this.deflatedSourcesDir = new File(mainDir, "deflated-sources");
        this.checkedOutSourcesDir = new File(mainDir, "checkouts");
        this.apiRegions = regions;
    }

    public ArtifactId getFeatureId() {
        return featureId;
    }

    public ApiRegions getApiRegions() {
        return this.apiRegions;
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

    public boolean addJavadocClasspath(final String classpathItem) {
        return javadocClasspath.add(classpathItem);
    }

    public Set<String> getJavadocClasspath() {
        return javadocClasspath;
    }

    public File getJavadocDir() {
        return javadocDir;
    }

    public void setJavadocDir(final File javadocDir) {
        this.javadocDir = javadocDir;
    }

    public boolean addPackageWithoutJavaClasses(final String packageName) {
        return packagesWithoutJavaClasses.add(packageName);
    }

    public Set<String> getPackagesWithoutJavaClasses() {
        return packagesWithoutJavaClasses;
    }

    public ArtifactInfo addArtifactInfo(final ArtifactId id) {
        final ArtifactInfo info = new ArtifactInfo(id);
        this.infos.add(info);

        return info;
    }

    public List<ArtifactInfo> getArtifactInfos() {
        return this.infos;
    }

    public Map<ArtifactId, Model> getModelCache() {
        return this.modelCache;
    }

    public Collection<ArtifactInfo> getArtifactInfos(final ApiRegion region) {
        final Map<ArtifactId, ArtifactInfo> result = new TreeMap<>();
        for(final ArtifactInfo info : this.infos) {
            if ( !info.getUsedExportedPackages(region).isEmpty() ) {
                result.put(info.getId(), info);
            }
        }
        return result.values();
    }


    public void setLicenseDefaults(final List<String> licenseDefaults) throws MojoExecutionException {
        this.licenseDefaultMatches = parseMatches(licenseDefaults);
    }

    public String getLicenseDefault(final ArtifactId id) {
        String result = null;
        if (this.licenseDefaultMatches != null) {
            result = match(id);
        }
        return result;
    }
    private List<String[]> parseMatches(final List<String> licenseDefaults) throws MojoExecutionException {
        List<String[]> matches = null;
        if (licenseDefaults != null && !licenseDefaults.isEmpty()) {
            matches = new ArrayList<>();
            for (final String t : licenseDefaults) {
                final String[] parts;
                if ( t.endsWith("=") ) {
                    parts = new String[] {t.substring(0, t.length() - 1), ""};
                } else {
                    parts = t.split("=");
                }
                if ( parts.length != 2 ) {
                    throw new MojoExecutionException("Illegal license default: " + t);
                }
                final String[] val = parts[0].split(":");
                if (val.length > 5) {
                    throw new MojoExecutionException("Illegal license default: " + t);
                }
                final String[] result = new String[val.length + 1];
                System.arraycopy(val, 0, result, 1, val.length);
                result[0] = parts[1];
                matches.add(result);
            }
        }
        return matches;
    }

    private boolean match(final String value, final String matcher) {
        if (matcher.endsWith("*")) {
            return value.startsWith(matcher.substring(0, matcher.length() - 1));
        }
        return matcher.equals(value);
    }

    private String match(final ArtifactId id) {
        boolean match = false;

        for(final String[] m : this.licenseDefaultMatches) {
            match = match(id.getGroupId(), m[1]);
            if (match && m.length > 2) {
                match = match(id.getArtifactId(), m[2]);
            }
            if (match && m.length == 4) {
                match = match(id.getVersion(), m[3]);
            } else if (match && m.length == 5) {
                match = match(id.getVersion(), m[4]);
                if (match) {
                    match = match(id.getType(), m[3]);
                }
            } else if (match && m.length == 6) {
                match = match(id.getVersion(), m[5]);
                if (match) {
                    match = match(id.getType(), m[3]);
                    if (match) {
                        match = match(id.getClassifier(), m[4]);
                    }
                }
            }
            if (match) {
                return m[0];
            }
        }

        return null;
    }
}
