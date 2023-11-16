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
package org.apache.sling.feature.maven.mojos.reports;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;

/**
 * Report to check scripts for package imports
 */
public class ScriptsImportPackagesReporter implements Reporter {

    private static final String PREFIX = "jcr_root/";

    @Override
    public String getName() {
        return "scripts-imported-packages";
    }

    @Override
    public void generateReport(final ReportContext ctx) throws MojoExecutionException {
        final Set<ArtifactId> artifacts = new TreeSet<>();

        for (final Feature feature : ctx.getFeatures()) {
            for (final Extension ext : feature.getExtensions()) {
                if (ext.getType() == ExtensionType.ARTIFACTS && Extension.EXTENSION_NAME_CONTENT_PACKAGES.equals(ext.getName())) {
                    for (final Artifact artifact : ext.getArtifacts()) {
                        if ( ctx.matches(artifact.getId())) {
                            artifacts.add(artifact.getId());
                        }
                    }
                }
            }
        }

        final List<String> report = new ArrayList<>();

        for(final ArtifactId id : artifacts ) {
            final URL url = ctx.getArtifactProvider().provide(id);
            try ( final ZipInputStream zis = new ZipInputStream(url.openStream())) {
                ZipEntry entry = null;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().startsWith(PREFIX)) {
                        final String path = entry.getName().substring(PREFIX.length() - 1);
                        if (path.endsWith(".jsp") ) {
                            final Set<String> imports = getImports(zis);
                            for(final String imp : imports) {
                                report.add(imp.concat("    ").concat(id.toMvnId()).concat("    ").concat(path));
                            }
                        }
                    }
                }
            } catch (final IOException ioe) {
               throw new MojoExecutionException("Unable to read from " + id.toMvnId(), ioe);
            }
        }

        ctx.addReport(this.getName().concat(".txt"), report);
    }

    private String readScript(final ZipInputStream is) throws IOException {
        final StringBuffer buf = new StringBuffer();
        final byte[] buffer = new byte[8192];
        int l = 0;
        while ((l = is.read(buffer)) > 0) {
            buf.append(new String(buffer, 0, l, StandardCharsets.UTF_8));
        }
        return buf.toString();
    }

    private Set<String> getImports(final ZipInputStream is) throws IOException {
        final Set<String> imports = new HashSet<>();
        final String script = readScript(is);
        // this is a very rough search
        int start = 0;
        int sectionStart = -1;
        while ( start < script.length() ) {
            // <%@page session="false" pageEncoding="utf-8" import=""
            if (sectionStart != -1) {
                final int pos = script.indexOf("%>", start);
                if (pos == -1) {
                    start = script.length();
                } else {
                    start = pos + 2;
                    final String section = script.substring(sectionStart, pos);
                    sectionStart = -1;
                    final int pagePos = section.indexOf("page");
                    if (pagePos != -1) {
                        final String page = section.substring(pagePos + 4);
                        if (Character.isWhitespace(page.charAt(0))) {
                            final int importPos = page.indexOf("import");
                            if (importPos != -1) {
                                // searchStart
                                int importStart = importPos + 6;
                                while (importStart < page.length() && page.charAt(importStart) != '=') {
                                    importStart++;
                                }
                                if (importStart < page.length()) {
                                    importStart++;
                                    while (importStart < page.length() && Character.isWhitespace(page.charAt(importStart))) {
                                        importStart++;
                                    }
                                    if (importStart < page.length() && page.charAt(importStart) == '"') {
                                        importStart++;
                                        int importEnd = importStart;
                                        while (importEnd < page.length() && page.charAt(importEnd) != '"') {
                                             importEnd++;
                                        }
                                        if (importEnd < page.length()) {
                                            final String imp = page.substring(importStart, importEnd);
                                            final StringTokenizer st = new StringTokenizer(imp, ",");
                                            while (st.hasMoreTokens()) {
                                                final String statement = st.nextToken().trim();
                                                final int lastDot = statement.lastIndexOf('.');
                                                imports.add(lastDot == -1 ? statement : statement.substring(0, lastDot));
                                            }
                                        }
                                    }
                               }
                           }
                        }
                    }
                }
            } else {
                final int pos = script.indexOf("<%@", start);
                if (pos == -1) {
                    start = script.length();
                } else {
                    sectionStart = start + 3;
                    start = sectionStart;
                }
            }
        }
        return imports;
    }
}
