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

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;

public class GenerateResourceTest {
    @Test
    public void testExecute() throws Exception {
        File srcDir = new File(getClass().getResource("/generate-resources/test1.json").toURI()).getParentFile();

        Build build = new Build();

        MavenProject project = new MavenProject();
        project.setBuild(build);
        project.setGroupId("gid");
        project.setArtifactId("aid");
        project.setVersion("1.2.3-SNAPSHOT");

        GenerateResources gr = new GenerateResources();
        setPrivateField(gr, "featuresDirectory", srcDir);
        setPrivateField(gr, "project", project);

        Path tempDir = Files.createTempDirectory("grtest");
        build.setDirectory(tempDir.toString());

        gr.execute();

        try {
            File[] files = new File(tempDir.toFile(), "features/processed")
                    .listFiles((d, n) -> n.endsWith(".json"));
            assertEquals(1, files.length);
            byte[] bytes = Files.readAllBytes(files[0].toPath());
            String s = new String(bytes).trim();
            assertEquals("\"---gid---aid---aid---1.2.3-SNAPSHOT---\"", s);
        } finally {
            Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
        }
    }

    @Test
    public void testExecuteWithoutFeatureFiles() throws Exception {
        GenerateResources gr = new GenerateResources();
        setPrivateField(gr, "featuresDirectory", new File("nonexistent"));

        // Should return gracefully
        gr.execute();
    }

    @Test
    public void testReplaceVars() {
        MavenProject mp = Mockito.mock(MavenProject.class);

        Mockito.when(mp.getGroupId()).thenReturn("abc");
        Mockito.when(mp.getArtifactId()).thenReturn("a.b.c");
        Mockito.when(mp.getVersion()).thenReturn("1.2.3-SNAPSHOT");

        assertEquals("xxxabcyyy", Substitution.replaceMavenVars(mp,
                "xxx${project.groupId}yyy"));
        assertEquals("xxxabcyyya.b.c1.2.3-SNAPSHOT", Substitution.replaceMavenVars(mp,
                "xxx${project.groupId}yyy${project.artifactId}${project.version}"));
    }

    private void setPrivateField(Object obj, String fieldName, Object value) throws Exception {
        Field f;
        try {
            f = obj.getClass().getDeclaredField(fieldName);
        } catch (Exception e) {
            f = obj.getClass().getSuperclass().getDeclaredField(fieldName);
        }
        f.setAccessible(true);
        f.set(obj, value);
    }
}
