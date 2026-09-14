package com.pulse.control;

/**
 * Constants defining the types of bytecode antipatterns detected during JAR analysis.
 */
public final class AntipatternType {

    public static final String STRING_CONCAT_LOOP = "STRING_CONCAT_LOOP";
    public static final String EXCESSIVE_OBJECT_CREATION = "EXCESSIVE_OBJECT_CREATION";
    public static final String REDUNDANT_INSTANCEOF = "REDUNDANT_INSTANCEOF";

    private AntipatternType() {
        // Prevent instantiation
    }
}
