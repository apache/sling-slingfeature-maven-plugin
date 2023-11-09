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
package org.apache.sling.feature.maven.mojos.apis.spi;

import java.io.File;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.extension.apiregions.api.ApiRegion;

/**
 * The context is used to pass in information into a {@code Processor}
 */
public interface ProcessorContext {

    /**
     * Get the feature
     * @return The feature
     */
    Feature getFeature();

    /**
     * Get the api region
     * @return The api region
     */
    ApiRegion getApiRegion();

    /**
     * Get the project
     * @return The project
     */
    MavenProject getProject();

    /**
     * Get the session
     * @return The session
     */
    MavenSession getSession();

    /**
     * The logger
     * @return The log
     */
    Log getLog();

    /**
     * Add a resource to the binary artifact. This method has only an affect when
     * the binary artifact is processed by the processor.
     * @param name The name of the resource, might contain slashes
     * @param file The file to add
     * @since 1.8.0
     */
    void addBinaryResource(String name, File file);
}
