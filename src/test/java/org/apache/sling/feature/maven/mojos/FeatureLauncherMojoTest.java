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

import edu.emory.mathcs.backport.java.util.Arrays;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.Whitebox;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class FeatureLauncherMojoTest {

    private FeatureLauncherMojo mojo = spy(new FeatureLauncherMojo());
    private Path tempDir;

    @Before
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory(getClass().getSimpleName());
    }

    @Test
    public void testBadFeatureFileNotAFile() throws MojoFailureException, MojoExecutionException, URISyntaxException {
        File mockFeatureFile = mock(File.class);
        Whitebox.setInternalState(mojo, "featureFile", mockFeatureFile);

        Build mockBuild = mock(Build.class);
        when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject project = new MavenProject();
        project.setGroupId("testing");
        project.setArtifactId("test");
        project.setVersion("1.0.1");
        project.setBuild(mockBuild);

        mojo.project = project;
        try {
            when(mockFeatureFile.isFile()).thenReturn(false);
            mojo.execute();
            fail("No feature fail exception (not a file) which should not happen");
        } catch(MojoFailureException e) {
            assertEquals("Wrong Exception Message", "Feature File is not a file: " + mockFeatureFile, e.getMessage());
        }
    }

    @Test
    public void testBadFeatureFileCannotRead() throws MojoFailureException, MojoExecutionException, URISyntaxException {
        File mockFeatureFile = mock(File.class);
        Whitebox.setInternalState(mojo, "featureFile", mockFeatureFile);

        Build mockBuild = mock(Build.class);
        when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject project = new MavenProject();
        project.setGroupId("testing");
        project.setArtifactId("test");
        project.setVersion("1.0.1");
        project.setBuild(mockBuild);

        mojo.project = project;
        try {
            when(mockFeatureFile.isFile()).thenReturn(true);
            when(mockFeatureFile.canRead()).thenReturn(false);
            mojo.execute();
            fail("No feature fail exception (cannot read) which should not happen");
        } catch(MojoFailureException e) {
            assertEquals("Wrong Exception Message", "Feature File is cannot be read: " + mockFeatureFile, e.getMessage());
        }
    }

    @Test
    public void testFullLaunch() throws MojoFailureException, MojoExecutionException, URISyntaxException {
        File featureFile = new File(getClass().getResource("/attach-resources/features/processed/test_a.json").toURI());

        Whitebox.setInternalState(mojo, "artifactClashOverrides", new String[] { "*:*:test" });
        Whitebox.setInternalState(mojo, "repositoryUrl", "~/.m2/repository");
        Whitebox.setInternalState(mojo, "frameworkProperties", new String[] { "one=two", "three=four" });
        Whitebox.setInternalState(mojo, "featureFile", featureFile);
        Whitebox.setInternalState(mojo, "variableValues", new String[] { "a=b" });
        Whitebox.setInternalState(mojo, "verbose", true);
        Whitebox.setInternalState(mojo, "cacheDirectory", new File("./launcher/cache"));
        Whitebox.setInternalState(mojo, "homeDirectory", new File("./launcher"));
        Whitebox.setInternalState(mojo, "extensionConfigurations", new String[] { "whatever" });
        Whitebox.setInternalState(mojo, "frameworkVersion", "1.0.0");
        Whitebox.setInternalState(mojo, "frameworkArtifacts", new String[] { "next-cool-thing" });

        Build mockBuild = mock(Build.class);
        when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        MavenProject project = new MavenProject();
        project.setGroupId("testing");
        project.setArtifactId("test");
        project.setVersion("1.0.1");
        project.setBuild(mockBuild);

        final List<String> arguments = new ArrayList<>();
        doAnswer(
            new Answer() {
                @Override
                public Object answer(InvocationOnMock invocation) throws Throwable {
                    String[] args = (String[]) invocation.getArguments()[0];
                    arguments.addAll(Arrays.asList(args));
                    return null;
                }
            }
        ).when(mojo).launch(any(String[].class));

        mojo.project = project;

        mojo.execute();

        assertFalse("No Launch Arguments", arguments.isEmpty());
    }
}
