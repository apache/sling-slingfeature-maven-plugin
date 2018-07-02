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

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.sling.feature.maven.FeatureConstants;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;

public class AttachFeatureTest {
    @Test
    public void testAttachArtifacts() throws Exception {
        File feat_a = new File(getClass().getResource("/attach-resources/features/processed/test_a.json").toURI());
        File feat_c = new File(getClass().getResource("/attach-resources/features/processed/test_c.json").toURI());
        File featuresDir = feat_a.getParentFile().getParentFile().getParentFile();

        Build build = new Build();
        build.setDirectory(featuresDir.getCanonicalPath());

        MavenProject project = new MavenProject();
        project.setBuild(build);

        AttachFeature af = new AttachFeature();
        af.project = project;

        MavenProjectHelper helper = Mockito.mock(MavenProjectHelper.class);
        af.projectHelper = helper;

        af.attachClassifierFeatures();
        Mockito.verify(helper).attachArtifact(project, FeatureConstants.PACKAGING_FEATURE, "testa", feat_a);
        Mockito.verify(helper).attachArtifact(project, FeatureConstants.PACKAGING_FEATURE, "testc", feat_c);
    }
}
