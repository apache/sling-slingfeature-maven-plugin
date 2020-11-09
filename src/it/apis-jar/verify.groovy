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

    boolean checkBundle(File file, List<String> exportPackages, List<String> expectedEntries, List<String> providedCapabilities) throws Exception {
        if (!file.exists()) {
            System.out.println("FAILED!");
            System.out.println("File '" + file + "' not found");
            return false;
        }

        JarFile jarFile = new JarFile(file);

        Manifest manifest= jarFile.getManifest();
        if (exportPackages != null) {

            String exportPackageHeader = manifest.getMainAttributes().getValue("Export-Package");
            for (String exportPackage : exportPackages) {
                if (exportPackageHeader.indexOf(exportPackage) < 0) {
                    System.out.println("FAILED!");
                    System.out.println("Export-Package header '" + exportPackageHeader + "' does not contain " + exportPackage + " in bundle " + file);
                    return false;
                }
            }
        }

        if (providedCapabilities != null) {

            String providedCapabilitiesHeader = manifest.getMainAttributes().getValue("Provide-Capability");
            for (String providedCapability : providedCapabilities) {
                if (providedCapabilitiesHeader.indexOf(providedCapability) < 0) {
                    System.out.println("FAILED!");
                    System.out.println("Provide-Capability header '" + providedCapabilitiesHeader + "' does not contain " + providedCapability + " in bundle " + file);
                    return false;
                }
            }
        }
        
        for (String expectedEntry : expectedEntries) {
            if (jarFile.getJarEntry(expectedEntry) == null) {
                System.out.println("FAILED!");
                System.out.println("Entry '" + expectedEntry + "' does not exist in bundle " + file);
                return false;
            }
        }

        jarFile.close();
        return true;
    }

    boolean check() throws Exception {
        File apisJarDir = new File(basedir, "target/apis-jars");

        // base

        File baseApiJar = new File(apisJarDir, "slingfeature-maven-plugin-test-1.0.0-SNAPSHOT-base-apis.jar");
        if (!checkBundle(baseApiJar,
                         [
                             "org.apache.felix.inventory;version=1.0",
                             "org.apache.felix.metatype;uses:=\"org.osgi.framework,org.osgi.service.metatype\";version=1.2.0",
                             "org.osgi.service.metatype;uses:=org.osgi.framework;version=1.4.0"
                         ],
                         [
                             "org/apache/felix/metatype/",
                             "org/osgi/service/metatype/",
                             "org/apache/felix/inventory/"
                         ],
                         [    // clauses keep insertion order, quoted only where necessary
                              "osgi.implementation;uses:=org.osgi.service.metatype;osgi.implementation=osgi.metatype;version:Version=1.4," +
                              "osgi.extender;uses:=org.osgi.service.metatype;osgi.extender=osgi.metatype;version:Version=1.4," +
                              "osgi.service;uses:=org.osgi.service.metatype;objectClass:List<String>=org.osgi.service.metatype.MetaTypeService"
                         ]
                         )) {
            return false;
        }

        File baseSourcesJar = new File(apisJarDir, "slingfeature-maven-plugin-test-1.0.0-SNAPSHOT-base-sources.jar");
        if (!checkBundle(baseSourcesJar,
                         null,
                         [
                             "org/apache/felix/metatype/",
                             "org/apache/felix/inventory/"
                         ],
                         null)) {
            return false;
        }

        // extended

        File extendedApiJar = new File(apisJarDir, "slingfeature-maven-plugin-test-1.0.0-SNAPSHOT-extended-apis.jar");
        if (!checkBundle(extendedApiJar,
                         [
                             "org.apache.felix.inventory;version=1.0",
                             "org.apache.felix.metatype;uses:=\"org.osgi.framework,org.osgi.service.metatype\";version=1.2.0",
                             "org.apache.felix.scr.component;uses:=org.osgi.service.component;version=1.1.0",
                             "org.apache.felix.scr.info;version=1.0.0"
                         ],
                         [
                             "org/apache/felix/metatype/",
                             "org/apache/felix/inventory/",
                             "org/apache/felix/scr/component/",
                             "org/apache/felix/scr/info/"
                         ],
                         null )) {
            return false;
        }

        File extendedSourcesJar = new File(apisJarDir, "slingfeature-maven-plugin-test-1.0.0-SNAPSHOT-extended-sources.jar");
        if (!checkBundle(extendedApiJar,
                         null,
                         [
                             "org/apache/felix/metatype/",
                             "org/apache/felix/inventory/",
                             "org/apache/felix/scr/component/",
                             "org/apache/felix/scr/info/"
                         ],
                         null )) {
            return false;
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
