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

import java.util.Collections;
import java.util.Set;

import org.apache.felix.utils.manifest.Clause;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.extension.apiregions.api.ApiExport;
import org.apache.sling.feature.extension.apiregions.api.ApiRegion;
import org.apache.sling.feature.extension.apiregions.api.ApiRegions;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RegionSupportTest {

    @Test
    public void testNoRegionInfo() throws MojoExecutionException {
        final RegionSupport support = new RegionSupport(
                Mockito.mock(Log.class), true, false, Collections.singleton("*"), Collections.emptySet());

        final Feature f = new Feature(ArtifactId.parse("g:a:1"));

        final ApiRegions regions = support.getApiRegions(f);
        assertNotNull(regions);
        assertEquals(1, regions.listRegions().size());
        assertEquals("global", regions.listRegions().get(0).getName());
    }

    @Test
    public void testApiExportAllPackages() throws MojoExecutionException {
        final ApiRegions regions = new ApiRegions();
        final ApiRegion region = new ApiRegion("global");
        regions.add(region);
        final ApiExport e1 = new ApiExport("p1");
        region.add(e1);
        final ApiExport e2 = new ApiExport("p2");
        region.add(e2);
        final ApiExport e3 = new ApiExport("p3");
        region.add(e3);
        final Artifact bundle = new Artifact(ArtifactId.parse("g:b:1"));
        final RegionSupport support = new RegionSupport(
                Mockito.mock(Log.class), true, false, Collections.singleton("*"), Collections.emptySet());

        // three packages are exported
        final Clause[] exportedPackages = new Clause[3];
        exportedPackages[0] = new Clause("p1", null, null);
        exportedPackages[1] = new Clause("p2", null, null);
        exportedPackages[2] = new Clause("p3", null, null);

        final Set<String> used =
                support.computeAllUsedExportPackages(regions, Collections.emptySet(), exportedPackages, bundle);
        assertEquals(3, used.size());
        assertTrue(used.contains("p1"));
        assertTrue(used.contains("p2"));
        assertTrue(used.contains("p3"));

        final Set<Clause> usedPerRegion = support.computeUsedExportPackagesPerRegion(region, exportedPackages, used);
        assertEquals(3, usedPerRegion.size());
        assertTrue(usedPerRegion.contains(exportedPackages[0]));
        assertTrue(usedPerRegion.contains(exportedPackages[1]));
        assertTrue(usedPerRegion.contains(exportedPackages[2]));
    }

    @Test
    public void testApiExportSubsetOfPackages() throws MojoExecutionException {
        final ApiRegions regions = new ApiRegions();
        final ApiRegion region = new ApiRegion("global");
        regions.add(region);
        final ApiExport e1 = new ApiExport("p1");
        region.add(e1);
        final ApiExport e2 = new ApiExport("p2");
        region.add(e2);
        final ApiExport e3 = new ApiExport("p4");
        region.add(e3);
        final Artifact bundle = new Artifact(ArtifactId.parse("g:b:1"));
        final RegionSupport support = new RegionSupport(
                Mockito.mock(Log.class), true, false, Collections.singleton("*"), Collections.emptySet());

        // three packages are exported
        final Clause[] exportedPackages = new Clause[3];
        exportedPackages[0] = new Clause("p1", null, null);
        exportedPackages[1] = new Clause("p2", null, null);
        exportedPackages[2] = new Clause("p3", null, null);

        final Set<String> used =
                support.computeAllUsedExportPackages(regions, Collections.emptySet(), exportedPackages, bundle);
        assertEquals(2, used.size());
        assertTrue(used.contains("p1"));
        assertTrue(used.contains("p2"));

        final Set<Clause> usedPerRegion = support.computeUsedExportPackagesPerRegion(region, exportedPackages, used);
        assertEquals(2, usedPerRegion.size());
        assertTrue(usedPerRegion.contains(exportedPackages[0]));
        assertTrue(usedPerRegion.contains(exportedPackages[1]));
    }

    @Test
    public void testApiJarExcludeProperty() throws MojoExecutionException {
        final ApiRegions regions = new ApiRegions();
        final ApiRegion region = new ApiRegion("global");
        regions.add(region);
        final ApiExport included = new ApiExport("included");
        region.add(included);
        final ApiExport excluded = new ApiExport("excluded");
        region.add(excluded);
        final Artifact bundle = new Artifact(ArtifactId.parse("g:b:1"));
        final Clause[] exportedPackages = {new Clause("included", null, null), new Clause("excluded", null, null)};
        final Set<String> enabledToggles = Collections.singleton("feature");

        for (final boolean toggleApiOnly : new boolean[] {false, true}) {
            final RegionSupport support = new RegionSupport(
                    Mockito.mock(Log.class), true, toggleApiOnly, Collections.singleton("*"), Collections.emptySet());
            included.setToggle(toggleApiOnly ? "feature" : null);
            excluded.setToggle(toggleApiOnly ? "feature" : null);

            for (final String value : new String[] {"true", "false", ""}) {
                excluded.getProperties().put("api-jar-exclude", value);

                final Set<String> used =
                        support.computeAllUsedExportPackages(regions, enabledToggles, exportedPackages, bundle);
                assertEquals(Collections.singleton("included"), used);
                assertEquals(
                        Collections.singleton(exportedPackages[0]),
                        support.computeUsedExportPackagesPerRegion(region, exportedPackages, used));
                assertEquals(Collections.singletonList(included), support.getAllExports(region, enabledToggles));
                assertEquals(excluded, region.getExportByName("excluded"));
            }
        }
    }

    @Test
    public void testApiJarExcludeWithIgnoredPackages() throws MojoExecutionException {
        final ApiRegions regions = new ApiRegions();
        final ApiRegion region = new ApiRegion("global");
        regions.add(region);
        region.add(new ApiExport("included"));
        region.add(new ApiExport("ignored"));
        final ApiExport excluded = new ApiExport("excluded");
        excluded.getProperties().put("api-jar-exclude", "true");
        region.add(excluded);
        final Artifact bundle = new Artifact(ArtifactId.parse("g:b:1"));
        bundle.getMetadata().put(ApisUtil.IGNORE_PACKAGES, "ignored");
        final RegionSupport support = new RegionSupport(
                Mockito.mock(Log.class), true, false, Collections.singleton("*"), Collections.emptySet());
        final Clause[] exportedPackages = {
            new Clause("included", null, null), new Clause("ignored", null, null), new Clause("excluded", null, null)
        };

        assertEquals(
                Collections.singleton("included"),
                support.computeAllUsedExportPackages(regions, Collections.emptySet(), exportedPackages, bundle));
    }

    @Test
    public void testApiJarExcludePerRegion() throws MojoExecutionException {
        final ApiRegions regions = new ApiRegions();
        final ApiRegion includedRegion = new ApiRegion("included");
        includedRegion.add(new ApiExport("p1"));
        regions.add(includedRegion);
        final ApiRegion excludedRegion = new ApiRegion("excluded");
        final ApiExport excluded = new ApiExport("p1");
        excluded.getProperties().put("api-jar-exclude", "true");
        excludedRegion.add(excluded);
        regions.add(excludedRegion);
        final Artifact bundle = new Artifact(ArtifactId.parse("g:b:1"));
        final RegionSupport support = new RegionSupport(
                Mockito.mock(Log.class), true, false, Collections.singleton("*"), Collections.emptySet());
        final Clause[] exportedPackages = {new Clause("p1", null, null)};

        final Set<String> used =
                support.computeAllUsedExportPackages(regions, Collections.emptySet(), exportedPackages, bundle);
        assertEquals(Collections.singleton("p1"), used);
        assertEquals(
                Collections.singleton(exportedPackages[0]),
                support.computeUsedExportPackagesPerRegion(includedRegion, exportedPackages, used));
        assertTrue(support.computeUsedExportPackagesPerRegion(excludedRegion, exportedPackages, used)
                .isEmpty());
        assertTrue(support.getAllExports(excludedRegion, Collections.emptySet()).isEmpty());
    }

    @Test
    public void testApiExportWithToggles() throws MojoExecutionException {
        final ApiRegions regions = new ApiRegions();
        final ApiRegion region = new ApiRegion("global");
        regions.add(region);
        final ApiExport e1 = new ApiExport("p1");
        region.add(e1);
        final ApiExport e2 = new ApiExport("p2");
        e2.setToggle("p2-feature");
        region.add(e2);
        final ApiExport e3 = new ApiExport("p3");
        region.add(e3);
        final Artifact bundle = new Artifact(ArtifactId.parse("g:b:1"));
        final RegionSupport support = new RegionSupport(
                Mockito.mock(Log.class), true, false, Collections.singleton("*"), Collections.emptySet());

        // three packages are exported
        final Clause[] exportedPackages = new Clause[3];
        exportedPackages[0] = new Clause("p1", null, null);
        exportedPackages[1] = new Clause("p2", null, null);
        exportedPackages[2] = new Clause("p3", null, null);

        // no toggle set, p2 is not included
        Set<String> used =
                support.computeAllUsedExportPackages(regions, Collections.emptySet(), exportedPackages, bundle);
        assertEquals(2, used.size());
        assertTrue(used.contains("p1"));
        assertTrue(used.contains("p3"));

        Set<Clause> usedPerRegion = support.computeUsedExportPackagesPerRegion(region, exportedPackages, used);
        assertEquals(2, usedPerRegion.size());
        assertTrue(usedPerRegion.contains(exportedPackages[0]));
        assertTrue(usedPerRegion.contains(exportedPackages[2]));

        // set toggle - p2 is included
        used = support.computeAllUsedExportPackages(
                regions, Collections.singleton("p2-feature"), exportedPackages, bundle);
        assertEquals(3, used.size());
        assertTrue(used.contains("p1"));
        assertTrue(used.contains("p2"));
        assertTrue(used.contains("p3"));

        usedPerRegion = support.computeUsedExportPackagesPerRegion(region, exportedPackages, used);
        assertEquals(3, usedPerRegion.size());
        assertTrue(usedPerRegion.contains(exportedPackages[0]));
        assertTrue(usedPerRegion.contains(exportedPackages[1]));
        assertTrue(usedPerRegion.contains(exportedPackages[2]));

        // no toggle set, but toggle uses previous version
        e2.setPreviousArtifactId(ArtifactId.parse("g:b:0.1"));
        used = support.computeAllUsedExportPackages(regions, Collections.emptySet(), exportedPackages, bundle);
        assertEquals(3, used.size());
        assertTrue(used.contains("p1"));
        assertTrue(used.contains("p2"));
        assertTrue(used.contains("p3"));

        usedPerRegion = support.computeUsedExportPackagesPerRegion(region, exportedPackages, used);
        assertEquals(3, usedPerRegion.size());
        assertTrue(usedPerRegion.contains(exportedPackages[0]));
        assertTrue(usedPerRegion.contains(exportedPackages[1]));
        assertTrue(usedPerRegion.contains(exportedPackages[2]));
    }

    @Test
    public void testApiExportWithTogglesOnly() throws MojoExecutionException {
        final ApiRegions regions = new ApiRegions();
        final ApiRegion region = new ApiRegion("global");
        regions.add(region);
        final ApiExport e1 = new ApiExport("p1");
        region.add(e1);
        final ApiExport e2 = new ApiExport("p2");
        e2.setToggle("p2-feature");
        region.add(e2);
        final ApiExport e3 = new ApiExport("p3");
        region.add(e3);
        final Artifact bundle = new Artifact(ArtifactId.parse("g:b:1"));
        final RegionSupport support = new RegionSupport(
                Mockito.mock(Log.class), true, true, Collections.singleton("*"), Collections.emptySet());

        // three packages are exported
        final Clause[] exportedPackages = new Clause[3];
        exportedPackages[0] = new Clause("p1", null, null);
        exportedPackages[1] = new Clause("p2", null, null);
        exportedPackages[2] = new Clause("p3", null, null);

        // no toggle set, toggle is required - empty result
        Set<String> used =
                support.computeAllUsedExportPackages(regions, Collections.emptySet(), exportedPackages, bundle);
        assertTrue(used.isEmpty());

        Set<Clause> usedPerRegion = support.computeUsedExportPackagesPerRegion(region, exportedPackages, used);
        assertTrue(usedPerRegion.isEmpty());

        // set toggle - p2-feature is set -> p2 is included
        used = support.computeAllUsedExportPackages(
                regions, Collections.singleton("p2-feature"), exportedPackages, bundle);
        assertEquals(1, used.size());
        assertTrue(used.contains("p2"));

        usedPerRegion = support.computeUsedExportPackagesPerRegion(region, exportedPackages, used);
        assertEquals(1, usedPerRegion.size());
        assertTrue(usedPerRegion.contains(exportedPackages[1]));

        // no toggle set, but toggle uses previous version -> empty result
        e2.setPreviousArtifactId(ArtifactId.parse("g:b:0.1"));
        used = support.computeAllUsedExportPackages(regions, Collections.emptySet(), exportedPackages, bundle);
        assertTrue(used.isEmpty());

        usedPerRegion = support.computeUsedExportPackagesPerRegion(region, exportedPackages, used);
        assertTrue(usedPerRegion.isEmpty());

        // set toggle - p2-feature is set -> p2 is included
        used = support.computeAllUsedExportPackages(
                regions, Collections.singleton("p2-feature"), exportedPackages, bundle);
        assertEquals(1, used.size());
        assertTrue(used.contains("p2"));

        usedPerRegion = support.computeUsedExportPackagesPerRegion(region, exportedPackages, used);
        assertEquals(1, usedPerRegion.size());
        assertTrue(usedPerRegion.contains(exportedPackages[1]));
    }
}
