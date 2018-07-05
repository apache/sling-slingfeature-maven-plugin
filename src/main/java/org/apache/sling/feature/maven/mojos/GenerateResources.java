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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

@Mojo(
        name = "generate-resources",
        defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true)
public class GenerateResources extends AbstractFeatureMojo {
    @Parameter(defaultValue="${basedir}/src/main/features")
    private File featuresDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        File[] files = featuresDirectory.listFiles();
        if (files == null)
            return;

        File processedFeaturesDir = new File(project.getBuild().getDirectory(), "features/processed");
        processedFeaturesDir.mkdirs();

        for (File f : files) {
            if (!f.getName().endsWith(".json")) {
                continue;
            }

            try {
                substituteVars(project, f, processedFeaturesDir);
            } catch (IOException e) {
                throw new MojoExecutionException("Problem processing feature file " + f.getAbsolutePath(), e);
            }
        }
    }

    private static File substituteVars(MavenProject project, File f, File processedFeaturesDir) throws IOException {
        File file = new File(processedFeaturesDir, f.getName());

        if (file.exists() && file.lastModified() > f.lastModified()) {
            // The file already exists, so we don't need to write it again
            return file;
        }

        try (FileWriter fw = new FileWriter(file)) {
            for (String s : Files.readAllLines(f.toPath())) {
                fw.write(replaceVars(project, s));
                fw.write(System.getProperty("line.separator"));
            }
        }
        return file;
    }

    static String replaceVars(MavenProject project, String s) {
        // There must be a better way than enumerating all these?
        s = s.replaceAll("\\Q${project.groupId}\\E", project.getGroupId());
        s = s.replaceAll("\\Q${project.artifactId}\\E", project.getArtifactId());
        s = s.replaceAll("\\Q${project.version}\\E", project.getVersion());
        return s;
    }
}
