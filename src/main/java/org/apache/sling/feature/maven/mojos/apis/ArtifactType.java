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
package org.apache.sling.feature.maven.mojos.apis;

public enum ArtifactType {
    APIS("apis", "class", "jar"),
    SOURCES("sources", "java", "jar"),
    JAVADOC("javadoc", "html", "jar"),
    JAVADOC_ALL("javadoc-all", "html", "jar"),
    DEPENDENCIES("apideps", "txt", "ref"),
    CND("cnd", "cnd", "jar"),
    REPORT("report", "txt", "txt"),
    DEPENDENCY_REPORT("dependency-report", "txt", "txt");

    private final String id;

    private final String type;

    private final String extension;

    ArtifactType(final String id, final String type, final String extension) {
        this.id = id;
        this.type = type;
        this.extension = extension;
    }

    public String getId() {
        return this.id;
    }

    public String getContentType() {
        return this.type;
    }

    public String getContentExtension() {
        return ".".concat(this.type);
    }

    public String getExtension() {
        return this.extension;
    }
}
