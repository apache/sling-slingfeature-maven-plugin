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

import org.apache.felix.utils.manifest.Clause;
import org.apache.felix.utils.manifest.Parser;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.Extensions;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.extension.apiregions.api.ApiExport;
import org.apache.sling.feature.extension.apiregions.api.ApiRegion;
import org.apache.sling.feature.extension.apiregions.api.ApiRegions;
import org.apache.sling.feature.maven.ProjectHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import javax.json.JsonArray;


/**
 * This mojo compares multiple feature models and checks if there is overlap between exported packages from
 * these feature models. It will fail the Maven execution if there is. <p>
 *
 * It can be used to detect if a feature model provides packages that are already provided as part of
 * some platform and report an error if there is such a case. <p>
 *
 * It does this by looking at the exports of the api-regions extension in the feature model and collecting
 * the packages listed there. If a feature model does not opt-in to the api-regions extension, all bundles
 * listed as part of that feature are examined for exported packages and these are added to the global
 * region. <p>
 *
 * If multiple features export the same package in any listed API region then the mojo will cause the build
 * to fail.
 */
@Mojo(name = "api-regions-crossfeature-duplicates",
    defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public class ApiRegionsOverlapCheckMojo extends AbstractIncludingFeatureMojo {
    private static final String GLOBAL_REGION = "global";

    @Parameter
    FeatureSelectionConfig selection;

    /**
     * The regions to check for overlapping exports
     */
    @Parameter
    Set<String> regions;

    /**
     * Special package instructions
     */
    @Parameter
    NoErrorPackageConfig packages;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (regions == null || regions.isEmpty()) {
            throw new MojoExecutionException("Please specify at least one region to check for duplicate exports");
        }

        Map<FeatureIDRegion, Set<String>> featureExports = new HashMap<>();
        Map<String, Feature> fs = getSelectedFeatures(selection);

        if (fs.size() < 2) {
            getLog().warn("Comparing feature models for overlapping exports is only useful for 2 ore more features. "
                    + "Number of feature models selected: " + fs.size());
        }

        for (Map.Entry<String, Feature> f : fs.entrySet()) {
            Feature feature = f.getValue();
            ApiRegions fRegions = getApiRegions(feature);
            if (fRegions != null) {
                // there are API regions defined

                for(ApiRegion r : fRegions.listRegions()) {
                    if (!regions.contains(r.getName())) {
                        continue;
                    }

                    Set<String> el = new HashSet<>();
                    for (ApiExport ex : r.listExports()) {
                        el.add(ex.getName());
                    }
                    featureExports.put(new FeatureIDRegion(f.getKey(), r.getName()), el);
                }
            } else {
                // no API regions defined, get the exports from all the bundles and record them for the global region
                Set<String> exports = new HashSet<>();
                for (Artifact bundle : feature.getBundles()) {
                    ArtifactId bid = bundle.getId();
                    org.apache.maven.artifact.Artifact art = ProjectHelper.getOrResolveArtifact(
                            project, mavenSession, artifactHandlerManager, artifactResolver, bid);
                    File bundleJar = art.getFile();
                    try (JarFile jf = new JarFile(bundleJar)) {
                        Manifest mf = jf.getManifest();
                        if (mf != null) {
                            String epHeader = mf.getMainAttributes().getValue("Export-Package");
                            if (epHeader != null) {
                                Clause[] clauses = Parser.parseHeader(epHeader);
                                for (Clause c : clauses) {
                                    exports.add(c.getName());
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw new MojoExecutionException(e.getMessage(), e);
                    }
                }
                featureExports.put(new FeatureIDRegion(f.getKey(), GLOBAL_REGION), exports);
            }
        }

        if (featureExports.size() < 2) {
            // Not 2 or more features, so no overlap to check
            return;
        }

        boolean overlapFound = false;
        List<FeatureIDRegion> keyList = new ArrayList<>(featureExports.keySet());
        for (int i=0; i<keyList.size(); i++) {
            FeatureIDRegion key1 = keyList.get(i);
            for (int j=i+1; j<keyList.size(); j++) {
                FeatureIDRegion key2 = keyList.get(j);
                Set<String> exp1 = featureExports.get(key1);
                Set<String> exp2 = featureExports.get(key2);
                overlapFound |= checkOverlap(key1, exp1, key2, exp2);
            }
        }

        if (overlapFound) {
            throw new MojoExecutionException("Errors found, see log");
        }
    }

    private boolean checkOverlap(FeatureIDRegion key1, Set<String> exp1, FeatureIDRegion key2, Set<String> exp2) {
        String msgPrefix = "Overlap found between " + key1 + " and " + key2 + ". Both export: ";

        if (key1.equals(key2)) {
            // Don't compare a region with itself
            return false;
        }

        Set<String> s = new HashSet<>(exp1);

        s.retainAll(exp2);

        if (packages != null) {
            // Remove all ignored packages
            s = removeAllMatching(packages.ignored, s);

            if (!packages.warnings.isEmpty()) {
                Set<String> ws = new HashSet<>(s);
                ws = retainAllMatching(packages.warnings, ws);
                s = removeAllMatching(packages.warnings, s);

                if (!ws.isEmpty()) {
                    getLog().warn(msgPrefix + ws);
                }
            }
        }

        if (s.isEmpty()) {
            // no overlap
            return false;
        }


        getLog().error(msgPrefix + s);
        return true;
    }

    private Set<String> removeAllMatching(Set<String> toRemove, Set<String> set) {
        return processAllMatching(toRemove, set, true);
    }

    private Set<String> retainAllMatching(Set<String> toRetain, Set<String> set) {
        return processAllMatching(toRetain, set, false);
    }

    private Set<String> processAllMatching(Set<String> toConsider, Set<String> set, boolean remove) {
        for (String e : toConsider) {
            String element = e.trim();
            if (e.endsWith("*")) {
                String prefix = element.substring(0, e.length() - 1);
                // Reverse the 'x.startsWith()' based on the value of remove
                set = set.stream().filter(
                        x -> x.startsWith(prefix) ^ remove).collect(Collectors.toSet());
            } else {
                set = set.stream().filter(
                        x -> x.equals(element) ^ remove).collect(Collectors.toSet());
            }
        }
        return set;
    }

    /**
     * Get the api regions for a feature.
     *
     * @param feature The feature
     * @return The api regions or {@code null} if the feature is not using API Regions
     * @throws MojoExecutionException If an error occurs
     */
    private ApiRegions getApiRegions(final Feature feature) throws MojoExecutionException {
        Extensions extensions = feature.getExtensions();
        Extension apiRegionsExtension = extensions.getByName(ApiRegions.EXTENSION_NAME);
        if (apiRegionsExtension != null) {
            ApiRegions regions = new ApiRegions();
            if (apiRegionsExtension.getJSONStructure() != null) {
                try {
                    regions = ApiRegions.parse((JsonArray) apiRegionsExtension.getJSONStructure());
                } catch (final IOException ioe) {
                    throw new MojoExecutionException(ioe.getMessage(), ioe);
                }
            }
            return regions;
        } else {
            return null;
        }
    }

    private static class FeatureIDRegion {
        private final String featureID;
        private final String region;

        private FeatureIDRegion(String featureID, String region) {
            this.featureID = featureID;
            this.region = region;
        }

        @Override
        public int hashCode() {
            return Objects.hash(featureID, region);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            FeatureIDRegion other = (FeatureIDRegion) obj;
            return Objects.equals(featureID, other.featureID) && Objects.equals(region, other.region);
        }

        @Override
        public String toString() {
            return "Feature: " + featureID + ", Region: " + region;
        }
    }

    public static class NoErrorPackageConfig {
        Set<String> ignored = new HashSet<>();
        Set<String> warnings = new HashSet<>();

        public void setIgnore(String pkg) {
            ignored.add(pkg);
        }

        public void setWarning(String pkg) {
            warnings.add(pkg);
        }
    }
}
