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
import java.nio.charset.*;
import java.util.*;


    boolean check() {
        File file = new File(basedir, "build.log");
        // On windows + Java 17, the build log has some strange encoding, and as we need the entire log, but only
        // some parts of it, we need to read it in a way, which allows us to check if it contains the
        // message we are looking for.
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPLACE);
        String log = "";
        try (BufferedReader reader = new LineNumberReader(new BufferedReader(new InputStreamReader(new FileInputStream(file), decoder)))) {
            String l;
            while ((l = reader.readLine()) != null) {
                log += l + "\n";
            }
        }

        if (log.indexOf("One or more feature analyser(s) detected feature error(s), please read the plugin log for more details") < 0) {
            System.out.println( "FAILED!" );
            return false;
        }
        return true;
    }

    try {
      return check();
    }
    catch(Throwable t) {
      t.printStackTrace();
      return false;
    }

    return true;
