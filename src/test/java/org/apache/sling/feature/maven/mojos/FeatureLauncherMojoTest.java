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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.reflect.FieldUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;


public class FeatureLauncherMojoTest {

    private static final String ASSEMBLED_FEATURE_JSON = Feature.class.getName() + "/assembledmain.json";

    private FeatureLauncherMojo mojo = spy(new FeatureLauncherMojo());
    private Path tempDir;

    @Before
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory(getClass().getSimpleName());
    }

    @After
    public void tearDown() throws Exception {
        // Delete the temp dir again
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }

    @Test
    public void testSimpleFeature() throws MojoExecutionException, IOException, MojoFailureException, IllegalAccessException {
        final FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setIncludeClassifier("example-runtime");
        FieldUtils.getField(FeatureLauncherMojo.class, "selection", true).set(mojo, cfg);
        Map<String, Feature> featureMap = new HashMap<>();
        File featureFile = new File(
            getClass().getResource("/aggregate-features/test-aggregated-feature-example-runtime.json").getFile());
        Feature feature = FeatureJSONReader.read(new FileReader(featureFile), null);
        String cacheKey = ASSEMBLED_FEATURE_JSON + "-cache";
        featureMap.put(feature.getId().toMvnId(), feature);

        Build mockBuild = mock(Build.class);
        when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject project = new MavenProject();
        project.setGroupId("testing");
        project.setArtifactId("test");
        project.setVersion("1.0.1");
        project.setBuild(mockBuild);
        project.setContextValue(cacheKey, featureMap);
        MavenSession session = mock(MavenSession.class);
        ProjectBuildingRequest projectBuildingRequest = mock(ProjectBuildingRequest.class);
        when(session.getProjectBuildingRequest()).thenReturn(projectBuildingRequest);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(projectBuildingRequest.getLocalRepository()).thenReturn(artifactRepository);
        when(artifactRepository.getBasedir()).thenReturn("");
        mojo.project = project;
        mojo.mavenSession = session;

        doNothing().when(mojo).checkPreconditions();
        final List<String> arguments = new ArrayList<>();
        doAnswer(
            new Answer<Object>() {
                @Override
                public Object answer(InvocationOnMock invocation) throws Throwable {
                    String[] args = (String[]) invocation.getArguments()[0];
                    arguments.addAll(Arrays.asList(args));
                    return null;
                }
            }
        ).when(mojo).launch(any(String[].class));

        mojo.execute();

        assertFalse("No Launch Arguments", arguments.isEmpty());
    }

    @Test
    public void testFullLaunch() throws MojoFailureException, MojoExecutionException, IOException, IllegalAccessException {
        final FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setIncludeClassifier("example-runtime");
        FieldUtils.getField(FeatureLauncherMojo.class, "selection", true).set(mojo, cfg);
        Map<String, Feature> featureMap = new HashMap<>();
        File featureFile = new File(
            getClass().getResource("/aggregate-features/test-aggregated-feature-example-runtime.json").getFile());
        Feature feature = FeatureJSONReader.read(new FileReader(featureFile), null);
        String cacheKey = ASSEMBLED_FEATURE_JSON + "-cache";
        featureMap.put(feature.getId().toMvnId(), feature);

        FieldUtils.getField(FeatureLauncherMojo.class, "artifactClashOverrides", true).set(mojo, new String[] { "*:*:test" });
        FieldUtils.getField(FeatureLauncherMojo.class, "repositoryUrl", true).set(mojo, "~/.m2/repository");
        FieldUtils.getField(FeatureLauncherMojo.class, "frameworkProperties", true).set(mojo, new String[] { "one=two", "three=four" });
        FieldUtils.getField(FeatureLauncherMojo.class, "variableValues", true).set(mojo, new String[] { "a=b" });
        FieldUtils.getField(FeatureLauncherMojo.class, "verbose", true).set(mojo, true);
        FieldUtils.getField(FeatureLauncherMojo.class, "cacheDirectory", true).set(mojo, new File("./launcher/cache"));
        FieldUtils.getField(FeatureLauncherMojo.class, "homeDirectory", true).set(mojo, new File("./launcher"));
        FieldUtils.getField(FeatureLauncherMojo.class, "extensionConfigurations", true).set(mojo,  new String[] { "whatever" });
        FieldUtils.getField(FeatureLauncherMojo.class, "frameworkVersion", true).set(mojo,  "1.0.0");
        FieldUtils.getField(FeatureLauncherMojo.class, "frameworkArtifacts", true).set(mojo,  new String[] { "next-cool-thing" } );

        Build mockBuild = mock(Build.class);
        when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject project = new MavenProject();
        project.setGroupId("testing");
        project.setArtifactId("test");
        project.setVersion("1.0.1");
        project.setBuild(mockBuild);
        project.setContextValue(cacheKey, featureMap);
        MavenSession session = mock(MavenSession.class);
        ProjectBuildingRequest projectBuildingRequest = mock(ProjectBuildingRequest.class);
        when(session.getProjectBuildingRequest()).thenReturn(projectBuildingRequest);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(projectBuildingRequest.getLocalRepository()).thenReturn(artifactRepository);
        when(artifactRepository.getBasedir()).thenReturn("");
        mojo.project = project;
        mojo.mavenSession = session;

        doNothing().when(mojo).checkPreconditions();
        final List<String> arguments = new ArrayList<>();
        doAnswer(
            new Answer<Object>() {
                @Override
                public Object answer(InvocationOnMock invocation) throws Throwable {
                    String[] args = (String[]) invocation.getArguments()[0];
                    arguments.addAll(Arrays.asList(args));
                    return null;
                }
            }
        ).when(mojo).launch(any(String[].class));

        mojo.execute();

        assertFalse("No Launch Arguments", arguments.isEmpty());
    }

    @Test
    public void testMainReflection() throws MojoFailureException, MojoExecutionException, IOException, IllegalAccessException {
        final FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setIncludeClassifier("example-runtime");
        FieldUtils.getField(FeatureLauncherMojo.class, "selection", true).set(mojo, cfg);
        Map<String, Feature> featureMap = new HashMap<>();
        File featureFile = new File(
            getClass().getResource("/aggregate-features/test-aggregated-feature-example-runtime.json").getFile());
        Feature feature = FeatureJSONReader.read(new FileReader(featureFile), null);
        String cacheKey = ASSEMBLED_FEATURE_JSON + "-cache";
        featureMap.put(feature.getId().toMvnId(), feature);

        FieldUtils.getField(FeatureLauncherMojo.class, "artifactClashOverrides", true).set(mojo, new String[] { "*:*:test" });
        Build mockBuild = mock(Build.class);
        when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject project = new MavenProject();
        project.setGroupId("testing");
        project.setArtifactId("test");
        project.setVersion("1.0.1");
        project.setBuild(mockBuild);
        project.setContextValue(cacheKey, featureMap);
        MavenSession session = mock(MavenSession.class);
        ProjectBuildingRequest projectBuildingRequest = mock(ProjectBuildingRequest.class);
        when(session.getProjectBuildingRequest()).thenReturn(projectBuildingRequest);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(projectBuildingRequest.getLocalRepository()).thenReturn(artifactRepository);
        when(artifactRepository.getBasedir()).thenReturn("");
        mojo.project = project;
        mojo.mavenSession = session;

        doNothing().when(mojo).checkPreconditions();

        mojo.execute();

        verify(mojo, times(1)).launch(any(String[].class));
    }

    @Test
    public void testMainReflectionFailure() throws MojoFailureException, MojoExecutionException, IOException, IllegalAccessException {
        final FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setIncludeClassifier("example-runtime");
        FieldUtils.getField(FeatureLauncherMojo.class, "selection", true).set(mojo, cfg);
        Map<String, Feature> featureMap = new HashMap<>();
        File featureFile = new File(
            getClass().getResource("/aggregate-features/test-aggregated-feature-example-runtime.json").getFile());
        Feature feature = FeatureJSONReader.read(new FileReader(featureFile), null);
        String cacheKey = ASSEMBLED_FEATURE_JSON + "-cache";
        featureMap.put(feature.getId().toMvnId(), feature);

        FieldUtils.getField(FeatureLauncherMojo.class, "artifactClashOverrides", true).set(mojo, new String[] { "do-fail" });
        Build mockBuild = mock(Build.class);
        when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject project = new MavenProject();
        project.setGroupId("testing");
        project.setArtifactId("test");
        project.setVersion("1.0.1");
        project.setBuild(mockBuild);
        project.setContextValue(cacheKey, featureMap);
        MavenSession session = mock(MavenSession.class);
        ProjectBuildingRequest projectBuildingRequest = mock(ProjectBuildingRequest.class);
        when(session.getProjectBuildingRequest()).thenReturn(projectBuildingRequest);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(projectBuildingRequest.getLocalRepository()).thenReturn(artifactRepository);
        when(artifactRepository.getBasedir()).thenReturn("");
        mojo.project = project;
        mojo.mavenSession = session;

        doNothing().when(mojo).checkPreconditions();

        try {
            mojo.execute();
            fail("Mojo Execution should have failed");
        } catch(MojoExecutionException e) {
            assertTrue("Root Cause Exception was of the wrong type", e.getCause() instanceof IllegalArgumentException);
        }
        verify(mojo, times(1)).launch(any(String[].class));
    }

    @Test
    public void testFeatureFileReadingIssue() throws MojoFailureException, MojoExecutionException, IOException, IllegalAccessException {
        final FeatureSelectionConfig cfg = new FeatureSelectionConfig();
        cfg.setIncludeClassifier("example-runtime");
        FieldUtils.getField(FeatureLauncherMojo.class, "selection", true).set(mojo, cfg);
        Map<String, Feature> featureMap = new HashMap<>();
        File featureFile = new File(
            getClass().getResource("/aggregate-features/test-aggregated-feature-example-runtime.json").getFile());
        Feature feature = FeatureJSONReader.read(new FileReader(featureFile), null);
        String cacheKey = ASSEMBLED_FEATURE_JSON + "-cache";
        featureMap.put(feature.getId().toMvnId(), feature);

        FieldUtils.getField(FeatureLauncherMojo.class, "artifactClashOverrides", true).set(mojo, new String[] { "*:*:test" });
        Build mockBuild = mock(Build.class);
        when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject project = new MavenProject();
        project.setGroupId("testing");
        project.setArtifactId("test");
        project.setVersion("1.0.1");
        project.setBuild(mockBuild);
        project.setContextValue(cacheKey, featureMap);
        MavenSession session = mock(MavenSession.class);
        ProjectBuildingRequest projectBuildingRequest = mock(ProjectBuildingRequest.class);
        when(session.getProjectBuildingRequest()).thenReturn(projectBuildingRequest);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        when(projectBuildingRequest.getLocalRepository()).thenReturn(artifactRepository);
        when(artifactRepository.getBasedir()).thenReturn("");
        mojo.project = project;
        mojo.mavenSession = session;
        doNothing().when(mojo).checkPreconditions();

        mojo.execute();

        verify(mojo, times(1)).launch(any(String[].class));
    }
}
