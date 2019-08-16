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

import org.junit.Before;
import org.junit.Test;
import org.mockito.internal.util.reflection.Whitebox;

import java.io.File;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

public class FeatureLauncherMojoTest {

    private FeatureLauncherMojo mojo = spy(new FeatureLauncherMojo());

    @Before
    public void setup() {
        doNothing().when(mojo).launch(any(String[].class));
    }

    @Test
    public void testLaunch() {
        Whitebox.setInternalState(mojo, "artifactClashOverrides", new String[] { "*:*:test" });
        Whitebox.setInternalState(mojo, "repositoryUrl", "~/.m2/repository");
        Whitebox.setInternalState(mojo, "frameworkProperties", new String[] { "one=two", "three=four" });
        Whitebox.setInternalState(mojo, "featureFile", new File("./test"));
        Whitebox.setInternalState(mojo, "variableValues", new String[] { "a=b" });
        Whitebox.setInternalState(mojo, "verbose", true);
        Whitebox.setInternalState(mojo, "cacheDirectory", new File("./launcher/cache"));
        Whitebox.setInternalState(mojo, "homeDirectory", new File("./launcher"));
        Whitebox.setInternalState(mojo, "extensionConfigurations", new String[] { "whatever" });
        Whitebox.setInternalState(mojo, "frameworkVersion", "1.0.0");
        Whitebox.setInternalState(mojo, "frameworkArtifacts", new String[] { "next-cool-thing" });
    }
}
