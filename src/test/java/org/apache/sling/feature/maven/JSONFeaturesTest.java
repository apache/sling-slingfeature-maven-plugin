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
package org.apache.sling.feature.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

import org.apache.sling.feature.Extension;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.junit.Test;

public class JSONFeaturesTest {
    
    @Test public void testFileReferenceInExtension() throws IOException, URISyntaxException {
        final URL url = this.getClass().getResource("/features/myfeature.json");
        final File file = new File(url.toURI());

        try ( final FileReader r = new FileReader(file)) {
            final Feature feature = FeatureJSONReader.read(r, file.getAbsolutePath());
            final Extension ext = feature.getExtensions().getByName("txtext");
            assertNotNull(ext);

            assertEquals("@file", ext.getText());

            JSONFeatures.handleExtensions(feature, file);

            assertEquals("Hello World", ext.getText());
        }
    }

    @Test public void testXMLFileReferenceInExtension() throws IOException, URISyntaxException {
        final URL url = this.getClass().getResource("/features/myfeature2.json");
        final File file = new File(url.toURI());

        try ( final FileReader r = new FileReader(file)) {
            final Feature feature = FeatureJSONReader.read(r, file.getAbsolutePath());
            final Extension ext = feature.getExtensions().getByName("txtxml");
            assertNotNull(ext);

            assertEquals("@file:config.xml", ext.getText());

            JSONFeatures.handleExtensions(feature, file);

            assertEquals("<a>XML</a>", ext.getText());
        }
    }
}
