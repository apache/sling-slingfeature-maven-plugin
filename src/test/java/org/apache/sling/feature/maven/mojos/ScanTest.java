/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.feature.maven.mojos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ScanTest {
    @Test
    public void testTaskConfiguration() {
        Scan scan = new Scan();

        assertEquals("Precondition", 0, scan.getTaskConfiguration().size());

        scan.setTaskConfiguration("task-a", Collections.singletonMap("foo", "bar"));
        Map<String, String> m = new HashMap<>();
        m.put("a", "b");
        m.put("x", "y");
        scan.setTaskConfiguration("task-b", m);

        Map<String, Map<String, String>> c = scan.getTaskConfiguration();
        assertEquals(2, c.size());
        assertEquals(1, c.get("task-a").size());
        assertEquals("bar", c.get("task-a").get("foo"));
        assertEquals(m, c.get("task-b"));
    }
}
