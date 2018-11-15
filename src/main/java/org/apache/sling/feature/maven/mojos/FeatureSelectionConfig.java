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

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.model.Dependency;

public class FeatureSelectionConfig {

    private List<String> filesIncludes = new ArrayList<>();

    private List<String> filesExcludes = new ArrayList<>();

    private List<Dependency> includeArtifacts = new ArrayList<>();

    private List<String> includeClassifiers = new ArrayList<>();

    public FeatureSelectionConfig() {
    }

    public void setFilesInclude(final String val) {
        filesIncludes.add(val);
    }

    public void setFilesExclude(final String val) {
        filesExcludes.add(val);
    }

    public void setIncludeArtifact(final Dependency a) {
        includeArtifacts.add(a);
    }

    public void setIncludeClassifier(final String classifier) {
        includeClassifiers.add(classifier);
    }

    public List<String> getFilesIncludes() {
        return this.filesIncludes;
    }

    public List<String> getFilesExcludes() {
        return this.filesExcludes;
    }

    public List<String> getIncludeClassifiers() {
        return this.includeClassifiers;
    }

    public List<Dependency> getIncludeArtifacts() {
        return this.includeArtifacts;
    }

    @Override
    public String toString() {
        return "FeatureSelectionConfig [filesIncludes=" + filesIncludes + ", filesExcludes=" + filesExcludes
                + ", includeArtifacts=" + includeArtifacts + ", includeClassifiers=" + includeClassifiers + "]";
    }
}