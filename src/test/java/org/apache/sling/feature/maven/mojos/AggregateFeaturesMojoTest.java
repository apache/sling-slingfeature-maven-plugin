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
package org.apache.sling.feature.maven.mojos;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultMavenProjectHelper;
import org.apache.maven.project.MavenProject;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Bundles;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.BuilderContext;
import org.apache.sling.feature.builder.HandlerContext;
import org.apache.sling.feature.builder.PostProcessHandler;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.Preprocessor;
import org.eclipse.aether.RepositorySystem;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AggregateFeaturesMojoTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path tempDir;

    private static Map<String, ArtifactId> pluginCallbacks;

    public static final String FEATURE_PROCESSED_LOCATION = "/features/processed";

    @Before
    public void setup() throws Exception {
        pluginCallbacks = new HashMap<>();
        tempDir = folder.newFolder().toPath();
    }

    public static void addPluginCallback(String plugin, ArtifactId artifactId) {
        pluginCallbacks.put(plugin, artifactId);
    }

    @Test
    public void testAggregateFeaturesFromDirectory() throws Exception {

        TestContext ctx = prepareTestContext("/aggregate-features/dir2", new String[] {"*.json"});

        ctx.getMojo().execute();

        Feature genFeat = ctx.getFeatureMap().get(":aggregate:aggregated:T");
        assertNotNull(genFeat);
        ArtifactId id = genFeat.getId();

        assertEquals("org.foo", id.getGroupId());
        assertEquals("org.foo.bar", id.getArtifactId());
        assertEquals("1.2.3-SNAPSHOT", id.getVersion());
        assertEquals(FeatureConstants.PACKAGING_FEATURE, id.getType());
        assertEquals("aggregated", id.getClassifier());

        Set<ArtifactId> expectedBundles = new HashSet<>();
        expectedBundles.add(new ArtifactId("org.apache.aries", "org.apache.aries.util", "1.1.3", null, null));
        expectedBundles.add(new ArtifactId("org.apache.sling", "someotherbundle", "1", null, null));
        Set<ArtifactId> actualBundles = new HashSet<>();
        for (org.apache.sling.feature.Artifact art : genFeat.getBundles()) {
            actualBundles.add(art.getId());
        }
        assertEquals(expectedBundles, actualBundles);

        Map<String, Dictionary<String, Object>> expectedConfigs = new HashMap<>();
        expectedConfigs.put("some.pid", new Hashtable<>(Collections.singletonMap("x", "y")));
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("foo", 123L);
        dict.put("bar", Boolean.TRUE);
        expectedConfigs.put("another.pid", dict);

        Map<String, Dictionary<String, Object>> actualConfigs = new HashMap<>();
        for (org.apache.sling.feature.Configuration conf : genFeat.getConfigurations()) {
            actualConfigs.put(conf.getPid(), conf.getProperties());
        }
        assertConfigsEquivalent(expectedConfigs, actualConfigs);
    }

    @Test
    public void testAggregateFeaturesFromDirectoryWithIncludesExcludes() throws Exception {

        TestContext ctx =
                prepareTestContext("/aggregate-features/dir", new String[] {"*.json", "*.foobar"}, (agg, mojo) -> {
                    agg.setFilesExclude("*_v*");
                    agg.setFilesExclude("test_w.json");
                });

        ctx.getMojo().execute();

        Feature genFeat = ctx.getFeatureMap().get(":aggregate:aggregated:T");
        assertNotNull(genFeat);
        ArtifactId id = genFeat.getId();

        assertEquals("org.foo", id.getGroupId());
        assertEquals("org.foo.bar", id.getArtifactId());
        assertEquals("1.2.3-SNAPSHOT", id.getVersion());
        assertEquals(FeatureConstants.PACKAGING_FEATURE, id.getType());
        assertEquals("aggregated", id.getClassifier());

        int numBundlesFound = 0;
        for (org.apache.sling.feature.Artifact art : genFeat.getBundles()) {
            numBundlesFound++;

            ArtifactId expectedBundleCoords =
                    new ArtifactId("org.apache.aries", "org.apache.aries.util", "1.1.3", null, null);
            assertEquals(expectedBundleCoords, art.getId());
        }
        assertEquals("Expected only one bundle", 1, numBundlesFound);

        Map<String, Dictionary<String, Object>> expectedConfigs = new HashMap<>();
        expectedConfigs.put("some.pid", new Hashtable<>(Collections.singletonMap("x", "y")));
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("foo", 123L);
        dict.put("bar", Boolean.TRUE);
        expectedConfigs.put("another.pid", dict);

        Map<String, Dictionary<String, Object>> actualConfigs = new HashMap<>();
        for (org.apache.sling.feature.Configuration conf : genFeat.getConfigurations()) {
            actualConfigs.put(conf.getPid(), conf.getProperties());
        }
        assertConfigsEquivalent(expectedConfigs, actualConfigs);
    }

    @Test
    public void testNonMatchingDirectoryIncludes() throws Exception {

        TestContext ctx = prepareTestContext("/aggregate-features/dir", new String[] {"doesnotexist.json"});

        try {
            ctx.getMojo().execute();
            fail("Should have thrown an exception because doesnotexist.json is not a file");
        } catch (MojoExecutionException mee) {
            assertTrue(mee.getMessage().contains("Include doesnotexist.json not found"));
        }
    }

    @Test
    public void testNonMatchingDirectoryExcludes() throws Exception {

        TestContext ctx = prepareTestContext("/aggregate-features/dir", new String[] {"doesnotexist.json"});

        try {
            ctx.getMojo().execute();
            fail("Should have thrown an exception because doesnotexist.json is not a file");
        } catch (MojoExecutionException mee) {
            assertTrue(mee.getMessage().contains("FeatureInclude doesnotexist.json not found"));
        }
    }

    @Test
    public void testIncludeOrdering() throws Exception {

        TestContext ctx = prepareTestContext(
                "/aggregate-features/dir4",
                new String[] {"test_x.json", "test_u.json", "test_y.json", "test_v.json", "test_z.json", "test_t.json"},
                (agg, mojo) -> {
                    agg.configurationOverrides = Arrays.asList("*=" + BuilderContext.CONFIG_MERGE_LATEST);
                    agg.classifier = "agg";
                });

        ctx.getMojo().execute();

        Feature genFeat = ctx.getFeatureMap().get(":aggregate:agg:T");
        assertNotNull(genFeat);
        ArtifactId id = genFeat.getId();

        assertEquals("org.foo", id.getGroupId());
        assertEquals("org.foo.bar", id.getArtifactId());
        assertEquals("1.2.3-SNAPSHOT", id.getVersion());
        assertEquals(FeatureConstants.PACKAGING_FEATURE, id.getType());
        assertEquals("agg", id.getClassifier());

        Map<String, Dictionary<String, Object>> expectedConfigs = new HashMap<>();
        expectedConfigs.put("t.pid", new Hashtable<>(Collections.singletonMap("t", "t")));
        expectedConfigs.put("u.pid", new Hashtable<>(Collections.singletonMap("u", "u")));
        expectedConfigs.put("v.pid", new Hashtable<>(Collections.singletonMap("v", "v")));
        expectedConfigs.put("x.pid", new Hashtable<>(Collections.singletonMap("x", "x")));
        expectedConfigs.put("y.pid", new Hashtable<>(Collections.singletonMap("y", "y")));
        expectedConfigs.put("z.pid", new Hashtable<>(Collections.singletonMap("z", "z")));

        Map<String, Dictionary<String, Object>> actualConfigs = new HashMap<>();
        for (org.apache.sling.feature.Configuration conf : genFeat.getConfigurations()) {
            actualConfigs.put(conf.getPid(), conf.getProperties());
        }
        assertConfigsEquivalent(expectedConfigs, actualConfigs);
    }

    private void assertConfigsEquivalent(
            Map<String, Dictionary<String, Object>> expectedConfigs,
            Map<String, Dictionary<String, Object>> actualConfigs) {
        for (Map.Entry<String, Dictionary<String, Object>> entry : expectedConfigs.entrySet()) {
            Dictionary<String, Object> actualValues = actualConfigs.get(entry.getKey());
            assertNotNull(actualValues);

            Dictionary<String, Object> expectedValues = entry.getValue();
            for (Enumeration<String> e = expectedValues.keys(); e.hasMoreElements(); ) {
                String key = e.nextElement();
                assertEquals(expectedValues.get(key), actualValues.get(key));
            }
        }
    }

    @Test
    public void testReadFeatureFromArtifact() throws Exception {
        File featureFile = new File(
                getClass().getResource("/aggregate-features/test_x.json").getFile());
        // read feature
        Map<String, Feature> featureMap = new HashMap<>();
        Feature feat = FeatureJSONReader.read(new FileReader(featureFile), null);
        featureMap.put(featureFile.getAbsolutePath(), feat);

        Aggregate fc = new Aggregate();
        final Dependency dep = new Dependency();
        dep.setGroupId("g1");
        dep.setArtifactId("a1");
        dep.setVersion("9.9.9");
        dep.setType(FeatureConstants.PACKAGING_FEATURE);
        dep.setClassifier("c1");

        fc.setIncludeArtifact(dep);

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("mygroup");
        Mockito.when(mockProj.getArtifactId()).thenReturn("myart");
        Mockito.when(mockProj.getVersion()).thenReturn("42");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        final Artifact fileArtifact = new DefaultArtifact(
                dep.getGroupId(),
                dep.getArtifactId(),
                dep.getVersion(),
                Artifact.SCOPE_COMPILE,
                dep.getType(),
                dep.getClassifier(),
                Mockito.mock(org.apache.maven.artifact.handler.ArtifactHandler.class));
        fileArtifact.setFile(featureFile);

        Mockito.when(mockProj.getAttachedArtifacts()).thenReturn(Collections.singletonList(fileArtifact));
        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        fc.classifier = "mynewfeature";
        af.aggregates = Collections.singletonList(fc);
        af.project = mockProj;
        af.mavenSession = Mockito.mock(MavenSession.class);
        af.projectHelper = new DefaultMavenProjectHelper();
        af.artifactHandlerManager = Mockito.mock(ArtifactHandlerManager.class);
        af.features = featureFile.getParentFile();

        af.repoSystem = Mockito.mock(RepositorySystem.class);
        af.execute();

        Feature genFeat = featureMap.get(":aggregate:mynewfeature:T");
        ArtifactId id = genFeat.getId();
        assertEquals("mygroup", id.getGroupId());
        assertEquals("myart", id.getArtifactId());
        assertEquals("42", id.getVersion());
        assertEquals(FeatureConstants.PACKAGING_FEATURE, id.getType());
        assertEquals("mynewfeature", id.getClassifier());

        int numFound = 0;
        for (org.apache.sling.feature.Artifact art : genFeat.getBundles()) {
            numFound++;

            ArtifactId expectedBundleCoords = new ArtifactId("mygroup", "org.apache.aries.util", "1.1.3", null, null);
            assertEquals(expectedBundleCoords, art.getId());
        }
        assertEquals("Expected only one bundle", 1, numFound);
    }

    @Test
    public void testPluginHandling() throws Exception {

        TestContext ctx = prepareTestContext("/aggregate-features/dir3", new String[] {"*.json"}, (agg, mojo) -> {
            Properties p3props = new Properties();
            p3props.put("test3cfg", "myval");
            p3props.put("test3cfg3", "somethingelse");

            mojo.handlerConfiguration = Map.of("TestPlugin3", p3props);
        });

        assertEquals("Precondition", 0, pluginCallbacks.size());
        ctx.getMojo().execute();

        ArtifactId id = new ArtifactId(
                "org.foo", "org.foo.bar", "1.2.3-SNAPSHOT", "aggregated", FeatureConstants.PACKAGING_FEATURE);
        assertEquals(id, pluginCallbacks.get("TestPlugin1 - extension1"));
        assertEquals(id, pluginCallbacks.get("TestPlugin1 - extension2"));
        assertEquals(id, pluginCallbacks.get("TestPlugin1 - extension3"));
        assertEquals(id, pluginCallbacks.get("TestPlugin2 - extension1"));
        assertEquals(id, pluginCallbacks.get("TestPlugin2 - extension2"));
        assertEquals(id, pluginCallbacks.get("TestPlugin2 - extension3"));

        ArtifactId id2 = new ArtifactId("test", "test", "9.9.9", "y", "slingosgifeature");
        assertEquals(id2, pluginCallbacks.get("TestPlugin3 - myval-Hi there"));
    }

    @Test
    public void testHandlerConfiguration() throws Exception {
        File featuresDir =
                new File(getClass().getResource("/aggregate-features/dir3").getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d, f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate ag = new Aggregate();
        ag.setFilesInclude("*.json");
        ag.classifier = "aggregated";

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        List<BuilderContext> capturedBuilderContext = new ArrayList<>();
        AggregateFeaturesMojo afm = new AggregateFeaturesMojo() {
            @Override
            Feature assembleFeature(
                    ArtifactId newFeatureID, BuilderContext builderContext, Map<String, Feature> selection)
                    throws MojoExecutionException {
                capturedBuilderContext.add(builderContext);
                return super.assembleFeature(newFeatureID, builderContext, selection);
            }
        };
        afm.aggregates = Collections.singletonList(ag);
        afm.project = mockProj;
        afm.projectHelper = new DefaultMavenProjectHelper();
        afm.features = featuresDir;
        afm.handlerConfiguration = new HashMap<>();

        assertEquals("Precondition", 0, capturedBuilderContext.size());
        afm.execute();
        assertEquals(1, capturedBuilderContext.size());
        BuilderContext bc = capturedBuilderContext.iterator().next();
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testHandlerConfiguration2() throws Exception {
        File featuresDir =
                new File(getClass().getResource("/aggregate-features/dir3").getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d, f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate ag = new Aggregate();
        ag.setFilesInclude("*.json");
        ag.classifier = "aggregated";

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        List<BuilderContext> capturedBuilderContext = new ArrayList<>();
        AggregateFeaturesMojo afm = new AggregateFeaturesMojo() {
            @Override
            Feature assembleFeature(
                    ArtifactId newFeatureID, BuilderContext builderContext, Map<String, Feature> selection)
                    throws MojoExecutionException {
                capturedBuilderContext.add(builderContext);
                return super.assembleFeature(newFeatureID, builderContext, selection);
            }
        };
        afm.aggregates = Collections.singletonList(ag);
        afm.project = mockProj;
        afm.projectHelper = new DefaultMavenProjectHelper();
        afm.features = featuresDir;
        afm.handlerConfiguration = new HashMap<>();

        Properties allProps = new Properties();
        allProps.put("a", "a aa a");
        afm.handlerConfiguration.put("all", allProps);
        Properties specProps = new Properties();
        specProps.put("zz", "z");
        afm.handlerConfiguration.put("specific", specProps);

        assertEquals("Precondition", 0, capturedBuilderContext.size());
        afm.execute();
        assertEquals(1, capturedBuilderContext.size());
        BuilderContext bc = capturedBuilderContext.iterator().next();

        Map hc = (Map) invokePrivateMethod(bc, "getHandlerConfigurations");
        Map allConfig = (Map) hc.get("all");
        assertEquals("a aa a", allConfig.get("a"));
        assertEquals("z", ((Map) hc.get("specific")).get("zz"));
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testHandlerConfiguration3() throws Exception {
        File featuresDir =
                new File(getClass().getResource("/aggregate-features/dir3").getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d, f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate ag = new Aggregate();
        ag.setFilesInclude("*.json");
        ag.classifier = "aggregated";

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        List<BuilderContext> capturedBuilderContext = new ArrayList<>();
        AggregateFeaturesMojo afm = new AggregateFeaturesMojo() {
            @Override
            Feature assembleFeature(
                    ArtifactId newFeatureID, BuilderContext builderContext, Map<String, Feature> selection)
                    throws MojoExecutionException {
                capturedBuilderContext.add(builderContext);
                return super.assembleFeature(newFeatureID, builderContext, selection);
            }
        };
        afm.aggregates = Collections.singletonList(ag);
        afm.project = mockProj;
        afm.projectHelper = new DefaultMavenProjectHelper();
        afm.features = featuresDir;
        afm.handlerConfiguration = new HashMap<>();

        Properties allProps = new Properties();
        allProps.put("fileStorage", "/somewhere");
        afm.handlerConfiguration.put("all", allProps);

        assertEquals("Precondition", 0, capturedBuilderContext.size());
        afm.execute();
        assertEquals(1, capturedBuilderContext.size());
        BuilderContext bc = capturedBuilderContext.iterator().next();

        Map hc = (Map) invokePrivateMethod(bc, "getHandlerConfigurations");
        Map allConfig = (Map) hc.get("all");
        assertEquals("/somewhere", allConfig.get("fileStorage"));
    }

    private Object invokePrivateMethod(Object obj, String name) throws Exception {
        Method m = obj.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(obj);
    }

    @Test
    public void testOverrides() throws Exception {

        TestContext ctx = prepareTestContext("/aggregate-features/dir5", new String[] {"*.json"}, (agg, mojo) -> {
            agg.classifier = "myagg";
            agg.artifactsOverrides = Arrays.asList(
                    "org.apache.sling:mybundle:HIGHEST",
                    "org.apache.sling:somebundle:1.1.0",
                    "org.apache.sling:somebundle:2.0.0");
        });

        ctx.getMojo().execute();

        Feature genFeat = ctx.getFeatureMap().get(":aggregate:myagg:T");
        Bundles bundles = genFeat.getBundles();
        assertEquals(3, bundles.size());
        assertTrue(bundles.contains(
                new org.apache.sling.feature.Artifact(ArtifactId.fromMvnId("org.apache.sling:mybundle:2"))));
        assertTrue(bundles.contains(
                new org.apache.sling.feature.Artifact(ArtifactId.fromMvnId("org.apache.sling:myotherbundle:3"))));
        assertTrue(bundles.contains(
                new org.apache.sling.feature.Artifact(ArtifactId.fromMvnId("org.apache.sling:somebundle:1.0.0"))));
    }

    @Test
    public void testOverrideWithManualArtifactIDNoOverride() throws Exception {
        TestContext ctx = prepareTestContext("/aggregate-features/dir6", new String[] {"*.json"}, (agg, mojo) -> {
            agg.classifier = "myagg";
        });

        try {
            ctx.getMojo().execute();
            fail("Should have thrown an exception as "
                    + "org.apache.sling:somebundle has as alias org.apache.sling:myotherbundle");
        } catch (Exception e) {
            String msg = e.getMessage();
            assertTrue(msg.contains("override rule required"));
            assertTrue(msg.contains("org.apache.sling:myotherbundle:"));
            assertTrue(msg.contains("org.apache.sling:somebundle:"));
        }
    }

    @Test
    public void testOverrideWithManualArtifactID() throws Exception {
        TestContext ctx = prepareTestContext(
                "/aggregate-features/dir6", new String[] {"test_c.json", "test_d.json"}, (agg, mojo) -> {
                    agg.classifier = "myagg";
                    agg.artifactsOverrides = Arrays.asList("org.apache.sling:myotherbundle:LATEST");
                });

        ctx.getMojo().execute();

        Feature genFeat = ctx.getFeatureMap().get(":aggregate:myagg:T");
        Bundles bundles = genFeat.getBundles();
        assertEquals(1, bundles.size());
        assertTrue(bundles.contains(
                new org.apache.sling.feature.Artifact(ArtifactId.fromMvnId("org.apache.sling:somebundle:1.0.0"))));
    }

    /**
     * Sling-9656 - verify that equals works for two equivalent Aggregate objects
     */
    @Test
    public void testAggregateEquals() throws Exception {
        Aggregate ag = new Aggregate();
        ag.classifier = "myagg";
        ag.attach = true;
        ag.markAsFinal = true;
        ag.markAsComplete = true;
        ag.title = "title";
        ag.description = "description";
        ag.vendor = "vendor";
        ag.artifactsOverrides = Arrays.asList(
                "org.apache.sling:mybundle:HIGHEST",
                "org.apache.sling:somebundle:1.1.0",
                "org.apache.sling:somebundle:2.0.0");
        ag.configurationOverrides = Arrays.asList("one");
        ag.variablesOverrides = Collections.singletonMap("key", "value");
        ag.frameworkPropertiesOverrides = Collections.singletonMap("key", "value");
        ag.setFilesExclude("file1.json");
        ag.setFilesInclude("file2.json");

        Aggregate ag2 = new Aggregate();
        ag2.classifier = "myagg";
        ag2.attach = true;
        ag2.markAsFinal = true;
        ag2.markAsComplete = true;
        ag2.title = "title";
        ag2.description = "description";
        ag2.vendor = "vendor";
        ag2.artifactsOverrides = Arrays.asList(
                "org.apache.sling:mybundle:HIGHEST",
                "org.apache.sling:somebundle:1.1.0",
                "org.apache.sling:somebundle:2.0.0");
        ag2.configurationOverrides = Arrays.asList("one");
        ag2.variablesOverrides = Collections.singletonMap("key", "value");
        ag2.frameworkPropertiesOverrides = Collections.singletonMap("key", "value");
        ag2.setFilesExclude("file1.json");
        ag2.setFilesInclude("file2.json");

        assertEquals(ag, ag2);

        // hashCode should be equal too
        assertEquals(ag.hashCode(), ag2.hashCode());

        Object[][] fieldChanges = new Object[][] {
            {"classifier", "myagg2"},
            {"attach", false},
            {"markAsFinal", false},
            {"markAsComplete", false},
            {"title", "title2"},
            {"description", "description2"},
            {"vendor", "vendor2"},
            {
                "artifactsOverrides",
                Arrays.asList(
                        "org.apache.sling:mybundle:HIGHEST",
                        "org.apache.sling:somebundle:2.2.0",
                        "org.apache.sling:somebundle:3.0.0")
            },
            {"configurationOverrides", Arrays.asList("two")},
            {"variablesOverrides", Collections.singletonMap("key2", "value2")},
            {"frameworkPropertiesOverrides", Collections.singletonMap("key2", "value2")}
        };

        // change something in each field to make them not equal
        for (Object[] objects : fieldChanges) {
            String fieldName = (String) objects[0];
            Field field = ag2.getClass().getField(fieldName);
            Object originalValue = field.get(ag2);
            try {
                field.set(ag2, objects[1]);

                // now the two object should no longer be equal
                assertNotEquals("expected not equal after changing field: " + fieldName, ag, ag2);
                assertNotEquals(
                        "expected hashCode not equal after changing field: " + fieldName,
                        ag.hashCode(),
                        ag2.hashCode());
            } finally {
                // put the old value back
                field.set(ag2, originalValue);
            }
        }

        // also check equals afterchanges to non-field data
        ag2.setFilesExclude("file3.json");
        ag2.setFilesInclude("file4.json");

        // now the two object should no longer be equal
        assertNotEquals("expected not equal after changing included/excluded files", ag, ag2);
        assertNotEquals(
                "expected hashCode not equal after changing included/excluded files", ag.hashCode(), ag2.hashCode());
    }

    /**
     * Sling-9656 - verify gracefully handling of scenarios where the AggregateFeaturesMojo gets invoked
     * more than once with equivalent configuration during the build
     */
    @Test
    public void testAggregateFeaturesInvokedMultipleTimes() throws Exception {
        File featuresDir =
                new File(getClass().getResource("/aggregate-features/dir5").getFile());

        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d, f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate ag = new Aggregate();
        ag.setFilesInclude("*.json");
        ag.classifier = "myagg";
        ag.artifactsOverrides = Arrays.asList(
                "org.apache.sling:mybundle:HIGHEST",
                "org.apache.sling:somebundle:1.1.0",
                "org.apache.sling:somebundle:2.0.0");

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArt = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getId()).thenReturn("test.aggregate.project1");
        Mockito.when(mockProj.getGroupId()).thenReturn("org.apache.sling");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.apache.sling.test");
        Mockito.when(mockProj.getVersion()).thenReturn("1.0.1");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArt);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);
        Map<Aggregate, Feature> handledAggregates = new HashMap<>();
        Mockito.when(mockProj.getContextValue(AggregateFeaturesMojo.class.getName() + "/generated"))
                .thenReturn(handledAggregates);

        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        af.aggregates = Collections.singletonList(ag);
        af.project = mockProj;
        af.projectHelper = new DefaultMavenProjectHelper();
        af.features = featuresDir;
        af.handlerConfiguration = new HashMap<>();

        // execute the first time
        af.execute();

        // and executing again with different (but equal) Aggregate objects should not fail either
        Aggregate ag2 = new Aggregate();
        ag2.setFilesInclude("*.json");
        ag2.classifier = "myagg";
        ag2.artifactsOverrides = Arrays.asList(
                "org.apache.sling:mybundle:HIGHEST",
                "org.apache.sling:somebundle:1.1.0",
                "org.apache.sling:somebundle:2.0.0");
        af.aggregates = Collections.singletonList(ag2);

        af.execute();
    }

    /**
     * Sling-9656 - verify configuration with duplicate Aggregate classifiers fails
     */
    @Test
    public void testAggregateFeaturesDuplicateClassifier() throws Exception {
        File featuresDir =
                new File(getClass().getResource("/aggregate-features/dir5").getFile());

        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d, f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate ag = new Aggregate();
        ag.setFilesInclude("*.json");
        ag.classifier = "myagg";
        ag.artifactsOverrides = Arrays.asList(
                "org.apache.sling:mybundle:HIGHEST",
                "org.apache.sling:somebundle:1.1.0",
                "org.apache.sling:somebundle:2.0.0");

        // a second different aggregate with the same classifier
        Aggregate ag2 = new Aggregate();
        ag2.setFilesInclude("*.json");
        ag2.classifier = "myagg";
        ag2.artifactsOverrides = Arrays.asList(
                "org.apache.sling:mybundle:HIGHEST",
                "org.apache.sling:somebundle:1.1.0",
                "org.apache.sling:somebundle:2.0.2");

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArt = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getId()).thenReturn("test.aggregate.project1");
        Mockito.when(mockProj.getGroupId()).thenReturn("org.apache.sling");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.apache.sling.test");
        Mockito.when(mockProj.getVersion()).thenReturn("1.0.1");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArt);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);
        Map<Aggregate, Feature> handledAggregates = new HashMap<>();
        Mockito.when(mockProj.getContextValue(AggregateFeaturesMojo.class.getName() + "/generated"))
                .thenReturn(handledAggregates);

        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        af.aggregates = Arrays.asList(ag, ag2);
        af.project = mockProj;
        af.projectHelper = new DefaultMavenProjectHelper();
        af.features = featuresDir;
        af.handlerConfiguration = new HashMap<>();

        try {
            af.execute();

            fail("Expected RuntimeException about duplicate aggregate classifier");
        } catch (RuntimeException e) {
            assertEquals(
                    "More than one feature file for classifier myagg in project test.aggregate.project1 : [aggregate myagg, aggregate myagg]",
                    e.getMessage());
        }
    }

    @Test
    public void customAggregate() throws Exception {

        TestContext ctx = prepareTestContext(
                "/aggregate-features/dir2",
                new String[] {"test_w.json", "test_y.json"}, // control the number of aggregations
                (agg, mojo) -> mojo.additionalPostProcessHandlers.add(SpyAggregateHandler.class.getTypeName()));

        ctx.getMojo().execute();

        // we expect 2 invocations because we have 2 feature files as input
        assertEquals("Additional postProcessHandler invocation count", 2, SpyAggregateHandler.invocationCount.get());
    }

    private Artifact createMockArtifact() {
        Artifact parentArtifact = Mockito.mock(Artifact.class);
        Mockito.when(parentArtifact.getGroupId()).thenReturn("gid");
        Mockito.when(parentArtifact.getArtifactId()).thenReturn("aid");
        Mockito.when(parentArtifact.getVersionRange()).thenReturn(VersionRange.createFromVersion("123"));
        Mockito.when(parentArtifact.getType()).thenReturn("foo");
        return parentArtifact;
    }

    private TestContext prepareTestContext(String featuresDirS, String[] filesInclude) throws IOException {
        return prepareTestContext(featuresDirS, filesInclude, (a, m) -> {});
    }

    private TestContext prepareTestContext(
            String featuresDirS, String[] filesInclude, BiConsumer<Aggregate, AggregateFeaturesMojo> customiser)
            throws IOException {
        File featuresDir = new File(getClass().getResource(featuresDirS).getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d, f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate fc = new Aggregate();
        fc.classifier = "aggregated";
        for (String include : filesInclude) fc.setFilesInclude(include);

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
                .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        af.aggregates = Collections.singletonList(fc);
        af.project = mockProj;
        af.projectHelper = new DefaultMavenProjectHelper();
        af.features = featuresDir;

        customiser.accept(fc, af);

        return new TestContext(af, featureMap);
    }

    static class TestContext {
        private final AggregateFeaturesMojo mojo;
        private final Map<String, Feature> featureMap;

        public TestContext(AggregateFeaturesMojo mojo, Map<String, Feature> featureMap) {
            this.mojo = mojo;
            this.featureMap = featureMap;
        }

        public AggregateFeaturesMojo getMojo() {
            return mojo;
        }

        public Map<String, Feature> getFeatureMap() {
            return featureMap;
        }
    }

    public static class SpyAggregateHandler implements PostProcessHandler {

        static final AtomicInteger invocationCount = new AtomicInteger(0);

        @Override
        public void postProcess(HandlerContext context, Feature feature, Extension extension) {
            invocationCount.incrementAndGet();
        }
    }
}
