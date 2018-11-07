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

    private List<String> includes = new ArrayList<>();

    private List<String> excludes = new ArrayList<>();

    private List<Dependency> artifacts = new ArrayList<>();

    private List<String> featureClassifiers = new ArrayList<>();

    public FeatureSelectionConfig() {
    }

    public void setFilesInclude(final String val) {
        includes.add(val);
    }

    public void setFilesExclude(final String val) {
        excludes.add(val);
    }

    public void setIncludeArtifact(final Dependency a) {
        artifacts.add(a);
    }

    public void setIncludeClassifier(final String classifier) {
        featureClassifiers.add(classifier);
    }

    public List<String> getIncludes() {
        return this.includes;
    }

    public List<String> getExcludes() {
        return this.excludes;
    }

    public List<String> getClassifiers() {
        return this.featureClassifiers;
    }

    public List<Dependency> getArtifacts() {
        return this.artifacts;
    }

    @Override
    public String toString() {
        return "FeatureSelectionConfig [filesIncludes=" + includes + ", filesExcludes=" + excludes
                + ", includeArtifact=" + artifacts + ", includeClassifier=" + featureClassifiers + "]";
    }
}