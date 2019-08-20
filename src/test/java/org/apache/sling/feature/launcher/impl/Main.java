package org.apache.sling.feature.launcher.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class Main {

    public static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // Do nothing for now
        LOGGER.info("main() called with: '{}'", Arrays.asList(args));
        for(String arg: args) {
            if("do-fail".equals(arg)) {
                throw new IllegalArgumentException();
            }
        }
    }
}
