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
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.mojos.AggregateFeatures.FeatureConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class AggregateFeaturesTest {
    private Path tempDir;

    @Before
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory(getClass().getSimpleName());
    }

    @After
    public void tearDown() throws Exception {
        // Delete the temp dir again
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
    }

    @Test
    public void testFeatureConfig() {
        FeatureConfig fc = new FeatureConfig();

        assertEquals(0, fc.includes.size());
        assertEquals(0, fc.excludes.size());
        assertNull(fc.location);
        assertNull(fc.groupId);
        assertNull(fc.artifactId);
        assertNull(fc.version);
        assertNull(fc.type);
        assertNull(fc.classifier);

        fc.setLocation("loc1");
        fc.setIncludes("i1");
        fc.setIncludes("i2");
        fc.setExcludes("e1");
        fc.setGroupId("gid1");
        fc.setArtifactId("aid1");
        fc.setVersion("1.2.3");
        fc.setType("slingfeature");
        fc.setClassifier("clf1");

        assertEquals(new HashSet<>(Arrays.asList("i1", "i2")), fc.includes);
        assertEquals(Collections.singleton("e1"), fc.excludes);

        assertEquals("loc1", fc.location);
        assertEquals("gid1", fc.groupId);
        assertEquals("aid1", fc.artifactId);
        assertEquals("1.2.3", fc.version);
        assertEquals("slingfeature", fc.type);
        assertEquals("clf1", fc.classifier);
    }

    @Test
    public void testAggregateFeaturesFromDirectory() throws Exception {
        File featuresDir = new File(
                getClass().getResource("/aggregate-features/dir").getFile());

        FeatureConfig fc = new FeatureConfig();
        fc.location = featuresDir.getAbsolutePath();

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");

        AggregateFeatures af = new AggregateFeatures();
        af.classifier = "aggregated";
        af.features = Collections.singletonList(fc);
        af.project = mockProj;

        af.execute();

        File expectedFile = new File(tempDir.toFile(), FeatureConstants.FEATURE_PROCESSED_LOCATION + "/aggregated.json");
        try (Reader fr = new FileReader(expectedFile)) {
            Feature genFeat = FeatureJSONReader.read(fr, null, FeatureJSONReader.SubstituteVariables.NONE);
            ArtifactId id = genFeat.getId();

            assertEquals("org.foo", id.getGroupId());
            assertEquals("org.foo.bar", id.getArtifactId());
            assertEquals("1.2.3-SNAPSHOT", id.getVersion());
            assertEquals("slingfeature", id.getType());
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
            assertEquals(expectedConfigs, actualConfigs);
        }
    }

    @Test
    public void testReadFeatureFromArtifact() throws Exception {
        File featureFile = new File(
                getClass().getResource("/aggregate-features/test_x.json").getFile());

        FeatureConfig fc = new FeatureConfig();
        fc.setGroupId("g1");
        fc.setArtifactId("a1");
        fc.setVersion("9.9.9");
        fc.setType("slingfeature");
        fc.setClassifier("c1");

        RepositorySystem mockRepo = createMockRepo();

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("mygroup");
        Mockito.when(mockProj.getArtifactId()).thenReturn("myart");
        Mockito.when(mockProj.getVersion()).thenReturn("42");

        AggregateFeatures af = new AggregateFeatures();
        af.classifier = "mynewfeature";
        af.features = Collections.singletonList(fc);
        af.repoSystem = mockRepo;
        af.localRepository = Mockito.mock(ArtifactRepository.class);
        af.remoteRepositories = Collections.emptyList();
        af.project = mockProj;

        af.artifactResolver = Mockito.mock(ArtifactResolver.class);
        Mockito.when(af.artifactResolver.resolve(Mockito.isA(ArtifactResolutionRequest.class)))
            .then(new Answer<ArtifactResolutionResult>() {
                @Override
                public ArtifactResolutionResult answer(InvocationOnMock invocation) throws Throwable {
                    ArtifactResolutionRequest arr = (ArtifactResolutionRequest) invocation.getArguments()[0];
                    Artifact a = arr.getArtifact();
                    assertEquals("g1", a.getGroupId());
                    assertEquals("a1", a.getArtifactId());
                    assertEquals("9.9.9", a.getVersion());
                    assertEquals("slingfeature", a.getType());
                    assertEquals("c1", a.getClassifier());

                    assertSame(af.localRepository, arr.getLocalRepository());
                    assertSame(af.remoteRepositories, arr.getRemoteRepositories());

                    // Configure Artifact.getFile()
                    Mockito.when(a.getFile()).thenReturn(featureFile);

                    return null;
                }
            });

        af.execute();

        File expectedFile = new File(tempDir.toFile(), FeatureConstants.FEATURE_PROCESSED_LOCATION + "/mynewfeature.json");
        try (Reader fr = new FileReader(expectedFile)) {
            Feature genFeat = FeatureJSONReader.read(fr, null, FeatureJSONReader.SubstituteVariables.NONE);
            ArtifactId id = genFeat.getId();
            assertEquals("mygroup", id.getGroupId());
            assertEquals("myart", id.getArtifactId());
            assertEquals("42", id.getVersion());
            assertEquals("slingfeature", id.getType());
            assertEquals("mynewfeature", id.getClassifier());

            int numFound = 0;
            for (org.apache.sling.feature.Artifact art : genFeat.getBundles()) {
                numFound++;

                ArtifactId expectedBundleCoords =
                        new ArtifactId("mygroup", "org.apache.aries.util", "1.1.3", null, null);
                assertEquals(expectedBundleCoords, art.getId());
            }
            assertEquals("Expected only one bundle", 1, numFound);
        }
    }

    private RepositorySystem createMockRepo() {
        RepositorySystem repo = Mockito.mock(RepositorySystem.class);
        Mockito.when(repo.createArtifactWithClassifier(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .then(new Answer<Artifact>() {
                @Override
                public Artifact answer(InvocationOnMock inv) throws Throwable {
                    Artifact art = Mockito.mock(Artifact.class);

                    Mockito.when(art.getGroupId()).thenReturn((String) inv.getArguments()[0]);
                    Mockito.when(art.getArtifactId()).thenReturn((String) inv.getArguments()[1]);
                    Mockito.when(art.getVersion()).thenReturn((String) inv.getArguments()[2]);
                    Mockito.when(art.getType()).thenReturn((String) inv.getArguments()[3]);
                    Mockito.when(art.getClassifier()).thenReturn((String) inv.getArguments()[4]);

                    return art;
                }
            });
        return repo;
    }
}
