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

import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

class Substitution {
    static File substituteMavenVars(MavenProject project, File f, File processedFeaturesDir) throws IOException {
        File file = new File(processedFeaturesDir, f.getName());

        if (file.exists() && file.lastModified() > f.lastModified()) {
            // The file already exists, so we don't need to write it again
            return file;
        }

        try (FileWriter fw = new FileWriter(file)) {
            for (String s : Files.readAllLines(f.toPath())) {
                fw.write(replaceMavenVars(project, s));
                fw.write(System.getProperty("line.separator"));
            }
        }
        return file;
    }

    static String replaceMavenVars(MavenProject project, String s) {
        // There must be a better way than enumerating all these?
        s = replaceAll(s, "project.groupId", project.getGroupId());
        s = replaceAll(s, "project.artifactId", project.getArtifactId());
        s = replaceAll(s, "project.version", project.getVersion());


        Properties props = project.getProperties();
        if (props != null) {
            for (String key : props.stringPropertyNames()) {
                s = replaceAll(s, key, props.getProperty(key));
            }
        }

        return s;
    }

    private static String replaceAll(String s, String key, String value) {
        return s.replaceAll("\\Q${" + key + "}\\E", value);
    }
}
