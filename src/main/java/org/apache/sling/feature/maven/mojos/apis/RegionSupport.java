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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.apache.felix.utils.manifest.Clause;
import org.apache.felix.utils.manifest.Parser;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.extension.apiregions.api.ApiExport;
import org.apache.sling.feature.extension.apiregions.api.ApiRegion;
import org.apache.sling.feature.extension.apiregions.api.ApiRegions;
import org.osgi.framework.Constants;

public class RegionSupport {

    private final Log log;

    private final boolean incrementalApis;

    private final Set<String> includeRegions;

    private final Set<String> excludeRegions;

    private final boolean toggleApiOnly;

    public RegionSupport(final Log logger,
             final boolean incrementalApis,
             final boolean toggleApiOnly,
             final Set<String> includeRegions,
             final Set<String> excludeRegions) {
        this.log = logger;
        this.incrementalApis = incrementalApis;
        this.includeRegions = includeRegions;
        this.excludeRegions = excludeRegions;
        this.toggleApiOnly = toggleApiOnly;
    }

    /**
     * Get the api regions for a feature If the feature does not have an api region
     * an artificial global region is returned.
     *
     * @param feature The feature
     * @return The api regions or {@code null} if the feature is wrongly configured
     *         or all regions are excluded
     * @throws MojoExecutionException If an error occurs
     */
    public ApiRegions getApiRegions(final Feature feature) throws MojoExecutionException {
        ApiRegions regions = new ApiRegions();

        final ApiRegions sourceRegions;
        try {
            sourceRegions = ApiRegions.getApiRegions(feature);
        } catch (final IllegalArgumentException iae) {
            throw new MojoExecutionException(iae.getMessage(), iae);
        }
        if (sourceRegions != null) {
            // calculate all api-regions first, taking the inheritance in account
            for (final ApiRegion r : sourceRegions.listRegions()) {
                if (r.getParent() != null && !this.incrementalApis) {
                    for (final ApiExport exp : r.getParent().listExports()) {
                        r.add(exp);
                    }
                }
                if (isRegionIncluded(r.getName())) {
                    log.debug("API Region " + r.getName()
                            + " will not processed due to the configured include/exclude list");
                    regions.add(r);
                }
            }

            if (regions.isEmpty()) {
                log.info("Feature file " + feature.getId().toMvnId()
                        + " has no included api regions, no API JAR will be created");
                regions = null;
            }
        } else {
            // create exports on the fly
            regions.add(new ApiRegion(ApiRegion.GLOBAL) {

                @Override
                public ApiExport getExportByName(final String name) {
                    ApiExport exp = super.getExportByName(name);
                    if (exp == null) {
                        exp = new ApiExport(name);
                        this.add(exp);
                    }
                    return exp;
                }
            });
        }

        return regions;
    }

    /**
     * Check if the region is included
     *
     * @param name The region name
     * @return {@code true} if the region is included
     */
    private boolean isRegionIncluded(final String name) {
        boolean included = false;
        for (final String i : this.includeRegions) {
            if ("*".equals(i) || i.equals(name)) {
                included = true;
                break;
            }
        }
        if (included && this.excludeRegions != null) {
            for (final String e : this.excludeRegions) {
                if (name.equals(e)) {
                    included = false;
                    break;
                }
            }
        }

        return included;
    }

    public List<ApiExport> getAllExports(final ApiRegion region, final Set<String> enabledToggles) {
        final List<ApiExport> result = new ArrayList<>();
        for (final ApiExport exp : region.listExports()) {
            if ( this.include(exp, enabledToggles) ) {
                result.add(exp);
            }
        }
        return result;
    }

    private boolean include(final ApiExport exp, final Set<String> enabledToggles) {
        boolean include = !toggleApiOnly;
        if ( toggleApiOnly ) {
            include = exp.getToggle() != null && enabledToggles.contains(exp.getToggle());
        } else {
            // if the package is behind a toggle,  only include if toggle is enabled or if previous artifact is set
            if (exp.getToggle() != null && !enabledToggles.contains(exp.getToggle()) && exp.getPrevious() == null) {
                include = false;
            }
        }
        return include;
    }

    /**
     * Compute exports based on all regions
     *
     * @return Set of packages exported by this bundle and used in any region
     */
    public Set<String> computeAllUsedExportPackages(final ApiRegions apiRegions,
            final Set<String> enabledToggles,
            final Clause[] exportedPackages,
            final Artifact bundle) throws MojoExecutionException {
        final Set<String> result = new HashSet<>();

        // filter for each region
        for (final Clause exportedPackage : exportedPackages) {
            final String packageName = exportedPackage.getName();

            for (ApiRegion apiRegion : apiRegions.listRegions()) {
                final ApiExport exp = apiRegion.getExportByName(packageName);
                if (exp != null) {
                    if ( this.include(exp, enabledToggles) ) {
                        result.add(exportedPackage.getName());
                    }
                }
            }
        }

        // check ignored packages configuration
        result.removeAll(ApisUtil.getIgnoredPackages(bundle));

        return result;
    }

   /**
     * Compute exports based on a single region
     *
     * @return List of packages exported by this bundle and used in the region
     */
    public Set<Clause> computeUsedExportPackagesPerRegion(final ApiRegion apiRegion,
            final Clause[] exportedPackages,
            final Set<String> allPackages) throws MojoExecutionException {

        final Set<Clause> result = new HashSet<>();

        // filter for each region
        for (final Clause exportedPackage : exportedPackages) {
            final String packageName = exportedPackage.getName();

            if (allPackages.contains(packageName)) {
                final ApiExport exp = apiRegion.getExportByName(packageName);
                if (exp != null) {
                    result.add(exportedPackage);
                }
            }
        }

        return result;
    }

    public Clause[] getExportedPackages(final Manifest manifest) {
        final String exportPackageHeader = manifest.getMainAttributes().getValue(Constants.EXPORT_PACKAGE);
        final Clause[] exportPackages = Parser.parseHeader(exportPackageHeader);

        return exportPackages;
    }

    public Manifest getManifest(final ArtifactId artifactId, final File bundleFile) throws MojoExecutionException {
        try (JarInputStream jis = new JarInputStream(new FileInputStream(bundleFile))) {
            log.debug("Reading Manifest headers from bundle " + bundleFile);

            final Manifest manifest = jis.getManifest();

            if (manifest == null) {
                throw new MojoExecutionException("Artifact + " + artifactId.toMvnId() + " does not  have a manifest.");
            }
            return manifest;
        } catch (final IOException e) {
            throw new MojoExecutionException("An error occurred while reading manifest from file " + bundleFile
                    + " for artifact " + artifactId.toMvnId(), e);
        }
    }

    /**
     * Get all packages for an artifact. If the artifact is a bundle use the export header, otherwise scan contents
     * @param ctx The generation context
     * @param artifact The artifact
     * @param artifactFile The file
     * @return A set of clauses
     * @throws MojoExecutionException If processing fails
     */
    public Set<Clause> getAllPublicPackages(final ApisJarContext ctx, final Artifact artifact, final File artifactFile)
            throws MojoExecutionException {
        final Set<Clause> packages = new LinkedHashSet<>();

        final Manifest manifest = getManifest(artifact.getId(), artifactFile);
        if (  manifest.getMainAttributes().getValue(Constants.BUNDLE_MANIFESTVERSION) != null ) {
            for(final Clause c : getExportedPackages(manifest)) {
                packages.add(c);
            }
        } else {
            final Set<String> names = ApisUtil.getPackages(ctx, artifactFile, ArtifactType.APIS.getContentExtension()).getKey();
            for(final String n : names) {
                packages.add(new Clause(n, null, null));
            }
        }

        return packages;
    }

    /**
     * Calculate whether the artifact can be omitted and a dependency can be used instead
     * @param region The api region
     * @param exportedPackageClauses All exported packages
     * @param usedExportedPackagesPerRegion Used exported packages
     * @return {@code null} if the artifact can be used as a dependency
     */
    public String calculateOmitDependenciesFlag(final ApiRegion region, final Clause[] exportedPackageClauses,
            final Set<Clause> usedExportedPackagesPerRegion) {
        // check whether all packages are exported in this region
        String reason = null;
        for (final Clause c : exportedPackageClauses) {
            boolean found = false;
            for (final Clause current : usedExportedPackagesPerRegion) {
                if (current.getName().equals(c.getName())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                final String msg = "Package ".concat(c.getName()).concat(" not exported.");
                reason = reason == null ? msg : reason.concat(" ").concat(msg);
            } else {
                // check deprecation - if deprecation is set, artifact can't be used as a
                // dependency
                final ApiExport exp = region.getAllExportByName(c.getName());
                if (exp != null && (exp.getDeprecation().getPackageInfo() != null || !exp.getDeprecation().getMemberInfos().isEmpty())) {
                    final String msg = "Package (or parts) ".concat(c.getName()).concat(" marked as deprecated.");
                    reason = reason == null ? msg : reason.concat(" ").concat(msg);
                }
            }
        }

        return reason;
    }
}
