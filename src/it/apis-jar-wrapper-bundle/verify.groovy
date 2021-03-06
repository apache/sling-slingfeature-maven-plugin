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

import java.io.*;
import java.util.*;
import java.util.jar.*;

import org.codehaus.plexus.util.*;

    boolean check() throws Exception {
        File apisJarDir = new File(basedir, "target/apis-jars");

        // base

        File baseApiJar = new File(apisJarDir, "slingfeature-maven-plugin-test-1.0.0-SNAPSHOT-base-apis.jar");
        if (!baseApiJar.exists()) {
            System.out.println("FAILED!");
            System.out.println("File '" + baseApiJar + "' not found");
            return false;
        }

        JarFile jarFile = null;
        try {
            jarFile = new JarFile(baseApiJar);
            Manifest manifest= jarFile.getManifest();
            String exportPackageHeader = manifest.getMainAttributes().getValue("Export-Package");

            if (exportPackageHeader.indexOf("com.google.gson.stream;version=2.8.4") < 0) {
                System.out.println("FAILED!");
                System.out.println("Export-Package header '" + exportPackageHeader + "' does not contain 'com.google.gson.stream;version=\"2.8.4\"' in bundle " + baseApiJar);
                return false;
            }

            for (String expectedEntry : [
                "com/google/gson/stream/JsonReader.class",
                "com/google/gson/stream/JsonScope.class",
                "com/google/gson/stream/JsonToken.class",
                "com/google/gson/stream/JsonWriter.class",
                "com/google/gson/stream/MalformedJsonException.class"
            ]) {
                if (jarFile.getJarEntry(expectedEntry) == null) {
                    System.out.println("FAILED!");
                    System.out.println("Entry '" + expectedEntry + "' does not exist in bundle " + baseApiJar);
                    return false;
                }
            }
        } finally {
            if (jarFile != null) {
                jarFile.close();
            }
        }

        return true;
    }

    try {
        return check();
    } catch(Throwable t) {
        t.printStackTrace();
        return false;
    }

    return true;
