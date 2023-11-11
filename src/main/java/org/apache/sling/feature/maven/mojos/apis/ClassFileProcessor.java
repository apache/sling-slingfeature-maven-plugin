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
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.sling.feature.maven.mojos.apis.spi.Processor;
import org.apache.sling.feature.maven.mojos.apis.spi.ProcessorContext;
import org.apache.sling.feature.maven.mojos.apis.spi.Source;
import org.osgi.annotation.versioning.ProviderType;

import jakarta.json.Json;
import jakarta.json.stream.JsonGenerator;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.AttributeInfo;
import javassist.bytecode.ClassFile;

public class ClassFileProcessor implements Processor {

    private static final String NAME = "api-info.json";

    @Override
    public void processBinaries(final ProcessorContext ctx, final List<Source> sources) {
        try {
            final String extension = ".class";
            final List<String> providerTypes = new ArrayList<>();
            for(final Source s : sources) {
                for(final File f : s.getFiles()) {
                    final String pckName = getPackageName(s.getBaseDirectory(), f, extension);
                    if (pckName != null) {
                        processClassFile(pckName, s.getBaseDirectory(), f, providerTypes);
                    }
                }
            }
            final File out = new File(ctx.getOutputDirectory(), ctx.getApiRegion().getName().concat("-").concat(NAME));
            if (!providerTypes.isEmpty()) {
                out.getParentFile().mkdirs();
                try (final JsonGenerator gen = Json.createGenerator(Files.newOutputStream(out.toPath()))) {
                    gen.writeStartObject();
                    gen.writeStartArray("providerTypes");
                    for(final String p : providerTypes) {
                        gen.write(p);
                    }
                    gen.writeEnd();
                    gen.writeEnd();
                }
                ctx.addResource("META-INF/".concat(NAME), out);
            } else if (out.exists()) {
                out.delete();
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void processSources(final ProcessorContext ctx, final List<Source> sources) {
        // nothing to do
    }

    private String getPackageName(final File baseDirectory, final File file, final String extension) {
        final String name = file.getName();
        if ( name.endsWith(extension)) {
            final String path = file.getAbsolutePath().substring(baseDirectory.getAbsolutePath().length() + 1);
            final int lastSlash = path.lastIndexOf(File.separatorChar);
            if ( lastSlash != -1 ) {
                final String pckName = path.substring(0, lastSlash).replace(File.separatorChar, '.');
                return pckName;
            }
        }
        return null;
    }

    private void processClassFile(final String pckName,
            final File baseDirectory,
            final File f,
            final List<String> providerTypes) throws Exception {
        final CtClass cc = ClassPool.getDefault().makeClass(new FileInputStream(f));
        try {
            cc.setName(pckName.concat(".").concat(f.getName().substring(0, f.getName().length()-6)));
            final ClassFile cfile = cc.getClassFile();
            for(final AttributeInfo attr : cfile.getAttributes()) {
                if (attr instanceof AnnotationsAttribute) {
                    final AnnotationsAttribute ann = (AnnotationsAttribute)attr;
                    if (ann.getAnnotation(ProviderType.class.getName()) != null ) {
                        providerTypes.add(cc.getName());
                    }
                }
            }
        } finally {
            cc.detach();
        }
    }
}
