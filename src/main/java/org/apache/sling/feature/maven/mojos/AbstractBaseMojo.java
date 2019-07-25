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

import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.sling.feature.maven.ProjectHelper;
import org.codehaus.plexus.logging.Logger;

public abstract class AbstractBaseMojo
    extends AbstractMojo
{

    public static final String PLUGIN_ID = "org.apache.sling:slingfeature-maven-plugin";
    public enum CHECK {generate, handle};

    @Parameter(property = "project", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "session", readonly = true, required = true)
    protected MavenSession mavenSession;

    @Component
    protected MavenProjectHelper projectHelper;

    @Component
    ArtifactHandlerManager artifactHandlerManager;

    @Component
    ArtifactResolver artifactResolver;

    @Component
    Logger logger;

    private static boolean fmGenerated = false;
    private static boolean fmHandlingStarted = false;

    void checkProject(CHECK check) throws MojoExecutionException {
        if(check == CHECK.generate) {
            // Generation cannot be done after the handling of the FM handling was started
            if(fmHandlingStarted) {
                throw new MojoExecutionException("FM Handling already started, cannot generate FMs anymore");
            }
            fmGenerated = true;
        } else if(check == CHECK.handle) {
            // Reparse if the FM Handling was not started yet and FMs were generated
            boolean reparse = !fmHandlingStarted && fmGenerated;
            fmHandlingStarted = true;
            fmGenerated = false;
            if(reparse) {
                ProjectHelper.prepareProject(artifactHandlerManager, artifactResolver, mavenSession, logger);
                ProjectHelper.checkPreprocessorRun(project);
            }
        }
    }
}
