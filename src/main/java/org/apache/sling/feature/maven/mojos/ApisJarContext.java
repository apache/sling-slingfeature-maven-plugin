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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.felix.utils.manifest.Clause;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.extension.apiregions.api.ApiRegions;

class ApisJarContext {

    public static final class ArtifactInfo {

        private ArtifactId id;

        private File binDirectory;

        private File sourceDirectory;

        private Clause[] exportedPackageClauses;

        private Set<String> usedExportedPackages;

        private final  Set<File> includedResources = new HashSet<>();

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

        public Clause[] getExportedPackageClauses() {
            return exportedPackageClauses;
        }

        public void setExportedPackageClauses(final Clause[] exportedPackageClauses) {
            this.exportedPackageClauses = exportedPackageClauses;
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

        public Set<File> getIncludedResources() {
            return includedResources;
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

    public ApisJarContext(final File mainDir, final ArtifactId featureId, final ApiRegions regions) {
        this.featureId = featureId;

        // deflated and source dirs can be shared
        this.deflatedBinDir = newDir(mainDir, "deflated-bin");
        this.deflatedSourcesDir = newDir(mainDir, "deflated-sources");
        this.checkedOutSourcesDir = newDir(mainDir, "checkouts");
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

    public boolean addJavadocClasspath(String classpathItem) {
        return javadocClasspath.add(classpathItem);
    }

    public boolean addPackageWithoutJavaClasses(String packageName) {
        return packagesWithoutJavaClasses.add(packageName);
    }

    public Set<String> getJavadocClasspath() {
        return javadocClasspath;
    }

    public Set<String> getPackagesWithoutJavaClasses() {
        return packagesWithoutJavaClasses;
    }

    private static File newDir(File parent, String child) {
        File dir = new File(parent, child);
        dir.mkdirs();
        return dir;
    }

    public ArtifactInfo addArtifactInfo(final ArtifactId id) {
        final ArtifactInfo info = new ArtifactInfo(id);
        this.infos.add(info);

        return info;
    }

    public List<ArtifactInfo> getArtifactInfos() {
        return this.infos;
    }

    public File getJavadocDir() {
        return javadocDir;
    }

    public void setJavadocDir(File javadocDir) {
        this.javadocDir = javadocDir;
    }
}
