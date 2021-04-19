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
package org.apache.sling.feature.maven.mojos.apis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.maven.ProjectHelper;
import org.apache.sling.feature.maven.mojos.selection.IncludeExcludeMatcher;

/**
 * Configuration for creating the api jars.
 *
 * The configuration can be controlled by a JSON extension:
 * <pre>
 * {
 *   "license-report" : "PATH",
 *   "license-header" : "STRING or STRING ARRAY",
 *   "license-footer" : "STRING or STRING ARRAY",
 *   "license-defaults" : ["PATTERN", "PATTERN"],
 *   "javadoc-links" : ["LINK", "LINK"],
 *   "javadoc-classpath-removals" : ["LINK", "LINK"],
 *   "javadoc-classpath-highest-versions" : ["LINK", "LINK"],
 *   "javadoc-classpath-tops" : ["LINK", "LINK"],
 *   "javadoc-source-level" : "STRING",
 *   "api-version" : "STRING",
 *   "bundle-resource-folders" : ["STRING", "STRING"],
 *   "bundle-resources" : ["STRING", "STRING"],
 *   "region-mapping" : {
 *     "REGION" : "MAPPED_NAME"
 *   },
 *   "classifier-mapping" : {
 *     "CLASSIFIER" : "MAPPED_NAME"
 *   },
 *   "manifest-entries" : {
 *     "key" : "value"
 *   },
 *   "javadoc-extensions" : [
 *     "[[REGION_NAME]:][COMMA_SEPARATED_LIST_OF_ARTIFACT_EXTENSIONS"
 *   ]
 * }
 * </pre>
 */
public class ApisConfiguration {

    private static final String EXTENSION_NAME = "apis-jar-config";

    private static final String PROP_LICENSE_REPORT = "license-report";

    private static final String PROP_MANIFEST_ENTRIES = "manifest-entries";

    private static final String PROP_CLASSIFIER_MAPPINGS = "classifier-mappings";

    private static final String PROP_REGION_MAPPINGS = "region-mappings";

    private static final String PROP_BUNDLE_RESOURCES = "bundle-resources";

    private static final String PROP_BUNDLE_RESOURCE_FOLDERS = "bundle-resource-folders";

    private static final String PROP_API_VERSION = "api-version";

    private static final String PROP_JAVADOC_SOURCE_LEVEL = "javadoc-source-level";

    private static final String PROP_JAVADOC_CLASSPATH_TOPS = "javadoc-classpath-tops";

    private static final String PROP_JAVADOC_CLASSPATH_HIGHEST_VERSIONS = "javadoc-classpath-highest-versions";

    private static final String PROP_JAVADOC_CLASSPATH_REMOVALS = "javadoc-classpath-removals";

    private static final String PROP_JAVADOC_LINKS = "javadoc-links";

    private static final String PROP_LICENSE_DEFAULTS = "license-defaults";

    private static final String PROP_LICENSE_FOOTER = "license-footer";

    private static final String PROP_LICENSE_HEADER = "license-header";

    private static final String PROP_ADDITIONAL_JAVADOC_EXTENSIONS = "javadoc-extensions";

    private String licenseReport;

    private final List<String> licenseDefaults = new ArrayList<>();

    private String licenseReportHeader;

    private String licenseReportFooter;

    private final List<String> javadocLinks = new ArrayList<>();

    private final List<String> javadocClasspathRemovals = new ArrayList<>();

    private final List<String> javadocClasspathHighestVersions = new ArrayList<>();

    private final List<String> javadocClasspathTops = new ArrayList<>();

    private String javadocSourceLevel;

    private String apiVersion;

    private final List<String> bundleResourceFolders = new ArrayList<>();

    private final List<String> bundleResources = new ArrayList<>();

    private final Map<String, String> regionMappings = new HashMap<>();

    private final Map<String, String> classifierMappings = new HashMap<>();

    private final Map<String, String> manifestEntries = new HashMap<>();

    private final Set<String> enabledToggles = new HashSet<>();

    private boolean useApiDependencies;

    /** The set of dependency repositories (URLs) */
    private final Set<String> dependencyRepositories = new HashSet<>();

    private boolean useApiDependenciesForJavadoc;

    private boolean generateJavadocForAllApi;

    /**
     * A map for additional extensions used for javadoc generation.
     * The key is the region name, "*" is used to indicate that these
     * extensions should be added to all regions.
     */
    private final Map<String, Set<String>> additionJavadocExtensionNames = new HashMap<>();

    public ApisConfiguration(final Feature feature) throws MojoExecutionException {
        // check for extension
        final Extension ext = feature.getExtensions().getByName(EXTENSION_NAME);
        if ( ext != null ) {
            if ( ext.getType() != ExtensionType.JSON) {
                throw new MojoExecutionException("Invalid extension type for " + ext.getName() + " : " + ext.getType());
            }
            final JsonObject json = ext.getJSONStructure().asJsonObject();
            this.licenseReport = json.getString(PROP_LICENSE_REPORT, null);
            this.licenseReportHeader = getStringOrArray(json, PROP_LICENSE_HEADER);
            this.licenseReportFooter = getStringOrArray(json, PROP_LICENSE_FOOTER);
            add(this.licenseDefaults, json, PROP_LICENSE_DEFAULTS);

            add(this.javadocLinks, json, PROP_JAVADOC_LINKS);

            add(this.javadocClasspathRemovals, json, PROP_JAVADOC_CLASSPATH_REMOVALS);
            add(this.javadocClasspathHighestVersions, json, PROP_JAVADOC_CLASSPATH_HIGHEST_VERSIONS);
            add(this.javadocClasspathTops, json, PROP_JAVADOC_CLASSPATH_TOPS);

            this.javadocSourceLevel = json.getString(PROP_JAVADOC_SOURCE_LEVEL, null);
            this.apiVersion = json.getString(PROP_API_VERSION, null);

            add(this.bundleResourceFolders, json, PROP_BUNDLE_RESOURCE_FOLDERS);
            add(this.bundleResources, json, PROP_BUNDLE_RESOURCES);

            add(this.regionMappings, json, PROP_REGION_MAPPINGS);
            add(this.classifierMappings, json, PROP_CLASSIFIER_MAPPINGS);
            add(this.manifestEntries, json, PROP_MANIFEST_ENTRIES);

            final List<String> additionalExtensions = new ArrayList<>();
            add(additionalExtensions, json, PROP_ADDITIONAL_JAVADOC_EXTENSIONS);
            this.setAdditionalJavadocExtensions(additionalExtensions);
        }
    }

    public void logConfiguration(final Log log) {
        if ( log.isInfoEnabled() ) {
            log.info("Using configuration:");
            log.info("- useApiDependencies : " + this.useApiDependencies);
            if ( this.useApiDependencies ) {
                log.info("- dependencyRepositories : " + (this.dependencyRepositories.isEmpty() ? "NONE" : this.dependencyRepositories.toString()));
                log.info("- useApiDependenciesForJavadoc : " + this.useApiDependenciesForJavadoc);
                log.info("- generateJavadocForAllApi : " + this.generateJavadocForAllApi);
            }
            log.info("- " + PROP_JAVADOC_SOURCE_LEVEL + " : " + this.javadocSourceLevel);
            log.info("- " + PROP_JAVADOC_LINKS + " : " + this.javadocLinks);
            log.info("- " + PROP_API_VERSION + " : " + this.apiVersion);
            log.info("- " + PROP_BUNDLE_RESOURCE_FOLDERS + " : " + this.bundleResourceFolders);
            log.info("- " + PROP_BUNDLE_RESOURCES + " : " + this.bundleResources);
            log.info("- " + PROP_REGION_MAPPINGS + " : " + this.regionMappings);
            log.info("- " + PROP_CLASSIFIER_MAPPINGS + " : " + this.classifierMappings);
            log.info("- " + PROP_JAVADOC_CLASSPATH_REMOVALS + " : " + this.javadocClasspathRemovals);
            log.info("- " + PROP_JAVADOC_CLASSPATH_HIGHEST_VERSIONS + " : " + this.javadocClasspathHighestVersions);
            log.info("- " + PROP_JAVADOC_CLASSPATH_TOPS + " : " + this.javadocClasspathTops);
            log.info("- " + PROP_MANIFEST_ENTRIES + " : " + this.manifestEntries);
            log.info("- " + PROP_LICENSE_REPORT + " : " + this.licenseReport);
            log.info("- " + PROP_LICENSE_DEFAULTS + " : " + this.licenseDefaults);
            log.info("- " + PROP_LICENSE_HEADER + " : " + this.licenseReportHeader);
            log.info("- " + PROP_LICENSE_FOOTER + " : " + this.licenseReportFooter);
            log.info("- " + PROP_ADDITIONAL_JAVADOC_EXTENSIONS + " : " + this.additionJavadocExtensionNames);
        }
    }

    private String getStringOrArray(final JsonObject json, final String propName) {
        String result = null;
        final JsonValue val = json.containsKey(propName) ? json.get(propName) : null;
        if ( val != null ) {
            if ( val.getValueType() == ValueType.ARRAY ) {
                final StringBuilder sb = new StringBuilder();
                for(final JsonValue v : val.asJsonArray()) {
                    sb.append(v);
                    sb.append('\n');
                }
                result = sb.toString();
            } else {
                result = ((JsonString)val).getString();
            }
        }
        return result;
    }

    private void add(final List<String> list, final JsonObject json, final String propName) {
        final JsonArray array = json.containsKey(propName) ? json.getJsonArray(propName) : null;
        if ( array != null ) {
            for(final JsonValue val : array) {
                list.add(((JsonString)val).getString());
            }
        }
    }

    private void add(final Map<String, String> map, final JsonObject json, final String propName) {
        final JsonObject obj = json.containsKey(propName) ? json.getJsonObject(propName) : null;
        if ( obj != null ) {
            for(final Map.Entry<String, JsonValue> entry : obj.entrySet()) {
                map.put(entry.getKey(), ((JsonString)entry.getValue()).getString());
            }
        }
    }

    public List<String> getBundleResources() {
        return bundleResources;
    }

    public List<String> getJavadocLinks() {
        return javadocLinks;
    }

    public String getJavadocSourceLevel() {
        return javadocSourceLevel;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public List<String> getBundleResourceFolders() {
        return bundleResourceFolders;
    }

    public String getLicenseReport() {
        return licenseReport;
    }

    public List<String> getLicenseDefaults() {
        return licenseDefaults;
    }

    public String getLicenseReportHeader() {
        return licenseReportHeader;
    }

    public String getLicenseReportFooter() {
        return licenseReportFooter;
    }

    public Map<String, String> getManifestEntries() {
        return manifestEntries;
    }

    public List<String> getJavadocClasspathRemovals() {
        return javadocClasspathRemovals;
    }

    public List<String> getJavadocClasspathHighestVersions() {
        return javadocClasspathHighestVersions;
    }

    public List<String> getJavadocClasspathTops() {
        return javadocClasspathTops;
    }

    private IncludeExcludeMatcher licenseDefaultMatcher;

    /**
     * Apply region name mapping if configured
     *
     * @param regionName The region name
     * @return The mapped name or the original name
     */
    public String mapApiRegionName(final String regionName) {
        if (this.regionMappings.containsKey(regionName)) {
            return this.regionMappings.get(regionName);
        }
        return regionName;
    }

    /**
     * Apply classifier mapping if configured
     *
     * @param classifier The classifier
     * @return The mapped classifier or the original classifier
     */
    public String mapApiClassifier(final String classifier) {
        if (this.classifierMappings.containsKey(classifier)) {
            return this.classifierMappings.get(classifier);
        }
        return classifier;
    }

    public String getLicenseDefault(final ArtifactId id) {
        return this.licenseDefaultMatcher.matches(id);
    }

    public void setLicenseDefaults(final List<String> licenseDefaultsFromProjcect) throws MojoExecutionException {
        if ( this.licenseDefaults.isEmpty() && licenseDefaultsFromProjcect != null ) {
            this.licenseDefaults.addAll(licenseDefaultsFromProjcect);
        }
        this.licenseDefaultMatcher = new IncludeExcludeMatcher(this.licenseDefaults, null, "=", true);
    }

    public void setLicenseReport(final String licenseReportFromProjcect) {
        if ( this.licenseReport == null ) {
            this.licenseReport = licenseReportFromProjcect;
        }
    }

    public void setLicenseReportHeader(final String licenseReportHeaderFromProjcect) {
        if ( this.licenseReportHeader == null ) {
            this.licenseReportHeader = licenseReportHeaderFromProjcect;
        }
    }

    public void setLicenseReportFooter(final String licenseReportFooterFromProjcect) {
        if ( this.licenseReportFooter == null ) {
            this.licenseReportFooter = licenseReportFooterFromProjcect;
        }
    }

    public void setJavadocLinks(final String[] javadocLinksFromProject) {
        if ( this.javadocLinks.isEmpty() && javadocLinksFromProject != null ) {
            for(final String v : javadocLinksFromProject) {
                this.javadocLinks.add(v);
            }
        }
    }

    public void setJavadocClasspathRemovals(final List<String> javadocClasspathRemovalsFromProject) {
        if ( this.javadocClasspathRemovals.isEmpty() && javadocClasspathRemovalsFromProject != null ) {
            this.javadocClasspathRemovals.addAll(javadocClasspathRemovalsFromProject);
        }
    }

    public void setJavadocClasspathHighestVersions(final List<String> javadocClasspathHighestVersionsFromProject) {
        if ( this.javadocClasspathHighestVersions.isEmpty() && javadocClasspathHighestVersionsFromProject != null ) {
            this.javadocClasspathHighestVersions.addAll(javadocClasspathHighestVersionsFromProject);
        }
    }

    public void setJavadocClasspathTops(final List<String> javadocClasspathTopsFromProject) {
        if ( this.javadocClasspathTops.isEmpty() && javadocClasspathTopsFromProject != null ) {
            this.javadocClasspathTops.addAll(javadocClasspathTopsFromProject);
        }
    }

    public void setJavadocSourceLevel(final String javadocSourceLevelFromProject) {
        if ( this.javadocSourceLevel == null ) {
            this.javadocSourceLevel = javadocSourceLevelFromProject;
        }
    }

    public void setApiVersion(final String apiVersionFromProject) {
        if ( this.apiVersion == null ) {
            this.apiVersion = apiVersionFromProject;
        }
    }

    public void setBundleResources(final String[] includeResourcesFromProject) {
        if ( this.bundleResources.isEmpty() && includeResourcesFromProject != null ) {
            for(final String v : includeResourcesFromProject) {
                this.bundleResources.add(v);
            }
        }
    }

    public void setBundleResourceFolders(final String resourceFoldersFromProject) {
        if ( this.bundleResourceFolders.isEmpty() && resourceFoldersFromProject != null ) {
            for(final String v : resourceFoldersFromProject.split(",")) {
                this.bundleResourceFolders.add(v.trim());
            }
        }
    }

    public void setRegionMappings(final Map<String, String> valuesFromProject) {
        if ( this.regionMappings.isEmpty() && valuesFromProject != null ) {
            this.regionMappings.putAll(valuesFromProject);
        }
    }

    public void setClassifierMappings(final Map<String, String> valuesFromProject) {
        if ( this.classifierMappings.isEmpty() && valuesFromProject != null ) {
            this.classifierMappings.putAll(valuesFromProject);
        }
    }

    public void setManifestEntries(final Properties valuesFromProject) {
        if ( this.manifestEntries.isEmpty() && valuesFromProject != null ) {
            this.manifestEntries.putAll(ProjectHelper.propertiesToMap(valuesFromProject));
        }
    }

    public void setEnabledToggles(final String value) {
        if (value != null ) {
            for(final String name : value.split(",")) {
                enabledToggles.add(name.trim());
            }
        }
    }

    public Set<String> getEnabledToggles() {
        return this.enabledToggles;
    }

    /**
     * Add the additional extensions for javadoc generation
     * @param javadocAdditionalExtensions A list of strings
     */
    public void setAdditionalJavadocExtensions(final List<String> javadocAdditionalExtensions) {
        if ( javadocAdditionalExtensions != null ) {
            for(final String val : javadocAdditionalExtensions) {
                final int sepPos = val.indexOf(":");
                final String regionName = sepPos == -1 ? "*" : val.substring(0, sepPos);
                for(final String name : val.substring(sepPos+1).split(",")) {
                    if ( !name.trim().isEmpty() ) {
                        this.additionJavadocExtensionNames.computeIfAbsent(regionName, key -> new LinkedHashSet<>()).add(name.trim());
                    }
                }
            }
        }
    }

    /**
     * Get the map for additional javadoc extensions for a region
     * @param regionName the region
     * @return The set of extension names, might be empty
     */
    public Set<String> getAdditionalJavadocExtensions(final String regionName) {
        final Set<String> result = new LinkedHashSet<>();
        result.addAll(this.additionJavadocExtensionNames.getOrDefault("*", Collections.emptySet()));
        result.addAll(this.additionJavadocExtensionNames.getOrDefault(regionName, Collections.emptySet()));
        return result;
    }

    /**
     * @return the useApiDependencies
     */
    public boolean isUseApiDependencies() {
        return useApiDependencies;
    }

    /**
     * @param useApiDependencies the useApiDependencies to set
     */
    public void setUseApiDependencies(final boolean flag) {
        this.useApiDependencies = flag;
    }

    /**
     * @return the useApiDependenciesForJavadoc
     */
    public boolean isUseApiDependenciesForJavadoc() {
        return useApiDependenciesForJavadoc;
    }

    /**
     * @param useApiDependenciesForJavadoc the useApiDependenciesForJavadoc to set
     */
    public void setUseApiDependenciesForJavadoc(final boolean flag) {
        this.useApiDependenciesForJavadoc = flag;
    }

    /**
     * @return the generateJavadocForAllApi
     */
    public boolean isGenerateJavadocForAllApi() {
        return generateJavadocForAllApi;
    }

    /**
     * @param generateJavadocForAllApi the generateJavadocForAllApi to set
     */
    public void setGenerateJavadocForAllApi(boolean generateJavadocForAllApi) {
        this.generateJavadocForAllApi = generateJavadocForAllApi;
    }

    public Set<String> getDependencyRepositories() {
        return this.dependencyRepositories;
    }

   /**
     * Set the dependency repositories
     * @param list Comma separated list or {@code null}
     */
    public void setDependencyRepositories(final String list) {
        this.dependencyRepositories.clear();
        if ( list != null ) {
            for(String val : list.split(",") ) {
                val = val.trim();
                if ( !val.endsWith("/") ) {
                    val = val.concat("/");
                }
                this.dependencyRepositories.add(val);
            }
        }
    }    
}
