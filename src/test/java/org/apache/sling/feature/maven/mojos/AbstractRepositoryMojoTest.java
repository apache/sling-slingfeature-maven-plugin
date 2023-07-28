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

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.sling.feature.ArtifactId;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class AbstractRepositoryMojoTest {
    private AbstractRepositoryMojo arm;
    private StringBuilder recordedOp;

    @Before
    public void setUp() throws ArtifactResolutionException {
        recordedOp = new StringBuilder();
        arm = new AbstractRepositoryMojo() {
            @Override
            public void execute() throws MojoExecutionException, MojoFailureException {
                // not used here
            }

            @Override
            void copyAndDecompressArtifact(File sourceFile, File artifactFile) throws IOException {
                recordedOp.append("copy_decompress");
            }

            @Override
            void copyArtifact(File sourceFile, File artifactFile) throws IOException {
                recordedOp.append("copy");
            }
        };
        arm.project = Mockito.mock(MavenProject.class);
        arm.mavenSession = Mockito.mock(MavenSession.class);
        ArtifactResult artifactResult = Mockito.mock(ArtifactResult.class);
        Mockito.when(artifactResult.getArtifact()).thenReturn(new DefaultArtifact("mygroup:dummyartifact:1.0.0"));
        arm.repoSystem = Mockito.mock(RepositorySystem.class);
        Mockito.when(arm.repoSystem.resolveArtifact(Mockito.any(), Mockito.any())).thenReturn(artifactResult);
        arm.artifactHandlerManager = Mockito.mock(ArtifactHandlerManager.class);
        Mockito.when(arm.artifactHandlerManager.getArtifactHandler(Mockito.anyString()))
                    .thenReturn(Mockito.mock(ArtifactHandler.class));
    }

    @Test
    public void testCopyArtifactToRepository() throws Exception {
        Method m = AbstractRepositoryMojo.class.getDeclaredMethod("copyArtifactToRepository", ArtifactId.class, File.class);
        m.setAccessible(true);

        File td = Files.createTempDirectory(getClass().getSimpleName()).toFile();
        ArtifactId aid = ArtifactId.fromMvnId("foo:bar:123");
        try {
            m.invoke(arm, aid, td);
        } finally {
            deleteDirTree(td);
        }

        assertEquals("copy", recordedOp.toString());
    }

    @Test
    public void testCopyDecompressArtifactToRepository() throws Exception {
        arm.decompress = true;

        Method m = AbstractRepositoryMojo.class.getDeclaredMethod("copyArtifactToRepository", ArtifactId.class, File.class);
        m.setAccessible(true);

        File td = Files.createTempDirectory(getClass().getSimpleName()).toFile();
        ArtifactId aid = ArtifactId.fromMvnId("foo:bar:123");
        try {
            m.invoke(arm, aid, td);
        } finally {
            deleteDirTree(td);
        }

        assertEquals("copy_decompress", recordedOp.toString());
    }


    private void deleteDirTree(File dir) throws IOException {
        Path tempDir = dir.toPath();

        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
    }
}
