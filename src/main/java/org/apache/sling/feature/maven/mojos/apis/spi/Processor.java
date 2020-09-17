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

import java.util.List;

/**
 * A processor can be used to process the binaries or sources before they get archived.
 */
public interface Processor {

    /**
     * Process the sources
     * @param ctx The context
     * @param sources The list of sources
     */
    void processSources(ProcessorContext ctx, List<Source> sources);

    /**
     * Process the binaries
     * @param ctx The context
     * @param sources The list of sources
     */
    void processBinaries(ProcessorContext ctx, List<Source> sources);

    /**
     * Unique name identifying the processor
     * @return The name
     */
    default String getName() {
        return this.getClass().getName();
    }
}
