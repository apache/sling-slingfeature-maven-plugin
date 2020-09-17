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
import java.util.ArrayList;
import java.util.List;

import org.apache.sling.feature.maven.mojos.apis.spi.Source;
import org.codehaus.plexus.archiver.util.DefaultFileSet;
import org.codehaus.plexus.util.DirectoryScanner;

public class DirectorySource implements Source {

    private final DefaultFileSet fileSet;

    public DirectorySource(final DefaultFileSet set) {
        this.fileSet = set;
    }

    @Override
    public File getBaseDirectory() {
        return this.fileSet.getDirectory();
    }

    @Override
    public List<File> getFiles() {
        final DirectoryScanner ds = new DirectoryScanner();
        ds.setBasedir( this.fileSet.getDirectory() );
        final String[] inc = this.fileSet.getIncludes();
        if ( inc != null && inc.length > 0 )
        {
            ds.setIncludes( inc );
        }
        final String[] exc = this.fileSet.getExcludes();
        if ( exc != null && exc.length > 0 )
        {
            ds.setExcludes( exc );
        }
        if ( this.fileSet.isUsingDefaultExcludes() )
        {
            ds.addDefaultExcludes();
        }
        ds.setCaseSensitive( this.fileSet.isCaseSensitive() );
        ds.setFollowSymlinks( false );
        ds.scan();

        final List<File> result = new ArrayList<>();
        for(final String file : ds.getIncludedFiles()) {
            result.add(new File(this.fileSet.getDirectory(), file));
        }
        return result;
    }

}
