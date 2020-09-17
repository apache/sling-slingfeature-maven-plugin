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
package org.apache.sling.feature.maven.mojos.apis;

import java.io.File;
import java.util.List;

import org.apache.sling.feature.maven.mojos.apis.spi.Source;

import edu.emory.mathcs.backport.java.util.Collections;

public class FileSource implements Source {

    private final File directory;

    private final File file;

    public FileSource(final File directory, final File file) {
        this.directory = directory;
        this.file = file;
    }

    @Override
    public File getBaseDirectory() {
        return directory;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<File> getFiles() {
        return Collections.singletonList(this.file);
    }

}
