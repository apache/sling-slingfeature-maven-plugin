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
package org.apache.sling.feature.maven;

import java.util.Properties;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.osgi.DefaultMaven2OsgiConverter;
import org.apache.maven.shared.osgi.Maven2OsgiConverter;

public class Substitution {
    public static String replaceMavenVars(MavenProject project, String s) {
        // There must be a better way than enumerating all these?
        s = replaceAll(s, "project.groupId", project.getGroupId());
        s = replaceAll(s, "project.artifactId", project.getArtifactId());
        s = replaceAll(s, "project.version", project.getVersion());
        s = replaceAll(s, "project.osgiVersion", getOSGiVersion(project.getVersion()));


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

    /**
     * Remove leading zeros for a version part
     */
    private static String cleanVersionString(final String version) {
        final StringBuilder sb = new StringBuilder();
        boolean afterDot = false;
        for(int i=0;i<version.length(); i++) {
            final char c = version.charAt(i);
            if ( c == '.' ) {
                if (afterDot == true ) {
                    sb.append('0');
                }
                afterDot = true;
                sb.append(c);
            } else if ( afterDot && c == '0' ) {
                // skip
            } else if ( afterDot && c == '-' ) {
                sb.append('0');
                sb.append(c);
                afterDot = false;
            } else {
                afterDot = false;
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String getOSGiVersion(final String version) {
        final DefaultArtifactVersion dav = new DefaultArtifactVersion(cleanVersionString(version));
        final StringBuilder sb = new StringBuilder();
        sb.append(dav.getMajorVersion());
        sb.append('.');
        sb.append(dav.getMinorVersion());
        sb.append('.');
        sb.append(dav.getIncrementalVersion());
        if ( dav.getQualifier() != null ) {
            sb.append('.');
            sb.append(dav.getQualifier());
        }
        final Maven2OsgiConverter converter = new DefaultMaven2OsgiConverter();
        return converter.getVersion(sb.toString());
    }
}
