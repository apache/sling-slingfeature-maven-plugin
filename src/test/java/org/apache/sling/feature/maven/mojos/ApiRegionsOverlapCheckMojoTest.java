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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApiRegionsOverlapCheckMojoTest {
    @Test
    public void testMustSpecifyRegion() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();
        mojo.selection = new FeatureSelectionConfig();

        try {
            mojo.execute();
            fail("Expected MojoExecutionException");
        } catch (MojoExecutionException mee) {
            assertTrue(mee.getMessage().contains("Please specify at least one region to check for"));
        }

        mojo.regions = Collections.emptySet();
        try {
            mojo.execute();
            fail("Expected MojoExecutionException");
        } catch (MojoExecutionException mee) {
            assertTrue(mee.getMessage().contains("Please specify at least one region to check for"));
        }
    }

    @Test
    public void testOverlap() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();

        mojo.features = new File(getClass().getResource("/api-regions-crossfeature-duplicates/testOverlap").getFile());
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : mojo.features.listFiles()) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        mojo.project = Mockito.mock(MavenProject.class);
        Mockito.when(mojo.project.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);

        mojo.regions = Collections.singleton("foo");
        mojo.packages = new ApiRegionsOverlapCheckMojo.NoErrorPackageConfig();
        mojo.packages.ignored = Collections.emptySet();
        mojo.packages.warnings = Collections.emptySet();
        FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setFilesInclude("*.json");
        mojo.selection = cfg;

        try {
            mojo.execute();
            fail("Expect to fail here as there is overlap");
        } catch (MojoExecutionException mee) {
            assertTrue(mee.getMessage().contains("Errors found"));
        }
    }

    @SuppressWarnings({ "unchecked", "deprecation" })
    @Test
    public void testOverlap2() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();

        mojo.features = new File(getClass().getResource("/api-regions-crossfeature-duplicates/testOverlap2").getFile());
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : mojo.features.listFiles()) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        ArtifactHandler artifactHandler = Mockito.mock(ArtifactHandler.class);

        mojo.mavenSession = Mockito.mock(MavenSession.class);
        mojo.artifactHandlerManager = Mockito.mock(ArtifactHandlerManager.class);
        Mockito.when(mojo.artifactHandlerManager.getArtifactHandler("jar"))
            .thenReturn(artifactHandler);
        mojo.artifactResolver = Mockito.mock(ArtifactResolver.class);
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Artifact art = invocation.getArgumentAt(0, Artifact.class);

                if ("feature-exports".equals(art.getArtifactId())) {
                    art.setFile(new File(getClass().
                            getResource("/bundles/feature-export.jar").getFile()));
                }
                if ("feature-exports2".equals(art.getArtifactId())) {
                    art.setFile(new File(getClass().
                            getResource("/bundles/feature-export2.jar").getFile()));
                }
                return null;
            }

        }).when(mojo.artifactResolver).resolve(Mockito.isA(Artifact.class),
                Mockito.any(List.class), Mockito.any(ArtifactRepository.class));
        mojo.project = Mockito.mock(MavenProject.class);
        Mockito.when(mojo.project.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);

        mojo.regions = Collections.singleton("global");
        FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setFilesInclude("*.json");
        mojo.selection = cfg;

        try {
            mojo.execute();
            fail("Expect to fail here as there is overlap");
        } catch (MojoExecutionException mee) {
            assertTrue(mee.getMessage().contains("Errors found"));
        }
    }

    @Test
    public void testOverlap3() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();

        mojo.features = new File(getClass().getResource("/api-regions-crossfeature-duplicates/testOverlap3").getFile());
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : mojo.features.listFiles()) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        mojo.project = Mockito.mock(MavenProject.class);
        Mockito.when(mojo.project.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);

        mojo.regions = new HashSet<>(Arrays.asList("bar", "foo"));
        mojo.packages = new ApiRegionsOverlapCheckMojo.NoErrorPackageConfig();
        mojo.packages.ignored = Collections.emptySet();
        mojo.packages.warnings = Collections.emptySet();
        FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setFilesInclude("*.json");
        mojo.selection = cfg;

        try {
            mojo.execute();
            fail("Expect to fail here as there is overlap");
        } catch (MojoExecutionException mee) {
            assertTrue(mee.getMessage().contains("Errors found"));
        }
    }

    @Test
    public void testNotEnoughFeatureModels() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();

        mojo.features = new File(getClass().getResource("/api-regions-crossfeature-duplicates/testNotEnoughFeatureModels").getFile());
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : mojo.features.listFiles()) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        mojo.project = Mockito.mock(MavenProject.class);
        Mockito.when(mojo.project.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);

        mojo.regions = Collections.singleton("foo");
        FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setFilesInclude("*.json");
        mojo.selection = cfg;

        // There is only one feature model, so this should not fail (but produce a warning)
        mojo.execute();
    }

    @Test
    public void testNoOverlap() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();

        mojo.features = new File(getClass().getResource("/api-regions-crossfeature-duplicates/testNoOverlap").getFile());
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : mojo.features.listFiles()) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        mojo.project = Mockito.mock(MavenProject.class);
        Mockito.when(mojo.project.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);

        mojo.regions = Collections.singleton("foo");
        FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setFilesInclude("*.json");
        mojo.selection = cfg;

        // Should not cause an exception as there is no overlap
        mojo.execute();
    }

    @Test
    public void testNoOverlap2() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();

        mojo.features = new File(getClass().getResource("/api-regions-crossfeature-duplicates/testNoOverlap2").getFile());
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : mojo.features.listFiles()) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        mojo.project = Mockito.mock(MavenProject.class);
        Mockito.when(mojo.project.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);

        mojo.regions = Collections.singleton("foo");
        FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setFilesInclude("*.json");
        mojo.selection = cfg;

        // Should not cause an exception as there is no overlap
        mojo.execute();
    }

    @SuppressWarnings({ "unchecked", "deprecation" })
    @Test
    public void testNoOverlap3() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();

        mojo.features = new File(getClass().getResource("/api-regions-crossfeature-duplicates/testNoOverlap3").getFile());
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : mojo.features.listFiles()) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        ArtifactHandler artifactHandler = Mockito.mock(ArtifactHandler.class);

        mojo.mavenSession = Mockito.mock(MavenSession.class);
        mojo.artifactHandlerManager = Mockito.mock(ArtifactHandlerManager.class);
        Mockito.when(mojo.artifactHandlerManager.getArtifactHandler("jar"))
            .thenReturn(artifactHandler);
        mojo.artifactResolver = Mockito.mock(ArtifactResolver.class);
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Artifact art = invocation.getArgumentAt(0, Artifact.class);

                if ("feature-exports".equals(art.getArtifactId())) {
                    art.setFile(new File(getClass().
                            getResource("/bundles/feature-export.jar").getFile()));
                }
                if ("no-exports".equals(art.getArtifactId())) {
                    art.setFile(new File(getClass().
                            getResource("/bundles/no-exports.jar").getFile()));
                }
                return null;
            }

        }).when(mojo.artifactResolver).resolve(Mockito.isA(Artifact.class),
                Mockito.any(List.class), Mockito.any(ArtifactRepository.class));
        mojo.project = Mockito.mock(MavenProject.class);
        Mockito.when(mojo.project.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);

        mojo.regions = new HashSet<>(Arrays.asList("global", "other"));
        FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setFilesInclude("*.json");
        mojo.selection = cfg;

        // Should not cause an exception as there is no overlap
        mojo.execute();
    }

    @Test
    public void testOverlapIgnore() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();

        mojo.features = new File(getClass().getResource("/api-regions-crossfeature-duplicates/testOverlap").getFile());
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : mojo.features.listFiles()) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        mojo.project = Mockito.mock(MavenProject.class);
        Mockito.when(mojo.project.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);

        mojo.regions = Collections.singleton("foo");
        mojo.packages = new ApiRegionsOverlapCheckMojo.NoErrorPackageConfig();
        mojo.packages.ignored = Collections.singleton("ding.dong");
        FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setFilesInclude("*.json");
        mojo.selection = cfg;

        // There is overlap with the ding.dong package, but it's configured as 'ignore', so the build should not fail
        mojo.execute();
    }

    @Test
    public void testOverlapWarning() throws Exception {
        ApiRegionsOverlapCheckMojo mojo = new ApiRegionsOverlapCheckMojo();

        mojo.features = new File(getClass().getResource("/api-regions-crossfeature-duplicates/testOverlap").getFile());
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : mojo.features.listFiles()) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        mojo.project = Mockito.mock(MavenProject.class);
        Mockito.when(mojo.project.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);

        mojo.regions = Collections.singleton("foo");
        mojo.packages = new ApiRegionsOverlapCheckMojo.NoErrorPackageConfig();
        mojo.packages.warnings = Collections.singleton("ding.dong");
        FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setFilesInclude("*.json");
        mojo.selection = cfg;

        // There is overlap with the ding.dong package, but it's configured as 'ignore', so the build should not fail
        mojo.execute();
    }
}
