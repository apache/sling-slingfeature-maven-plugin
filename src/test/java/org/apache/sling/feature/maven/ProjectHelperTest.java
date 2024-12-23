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
package org.apache.sling.feature.maven;

import java.io.IOException;
import java.nio.file.Paths;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class ProjectHelperTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void storeProjectInfoSetsOutputDirectory() throws IOException {

        String buildDirectory = tmp.newFolder().getAbsolutePath();
        String expectedSlingfeatureOutputDirectory =
                Paths.get(buildDirectory, "slingfeature-tmp").toString();

        FeatureProjectInfo info = new FeatureProjectInfo();
        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setDirectory(buildDirectory);
        project.setBuild(build);

        info.project = project;

        ProjectHelper.storeProjectInfo(info);

        assertThat(
                "Slingfeature output directory",
                project.getProperties().get("project.slingfeature.outputDirectory"),
                equalTo(expectedSlingfeatureOutputDirectory));
    }
}
