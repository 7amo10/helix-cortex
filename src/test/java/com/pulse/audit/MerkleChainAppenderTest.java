package com.pulse.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MerkleChainAppender Tests")
class MerkleChainAppenderTest {

    private final MerkleChainAppender appender = new MerkleChainAppender();

    @Test
    @DisplayName("First leaf handles null prevHash cleanly")
    void testFirstLeafHasNullPrevHash() {
        String eventHash = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
        String leafHash = appender.appendLeaf(null, eventHash);
        assertNotNull(leafHash);
        assertEquals(64, leafHash.length());
    }

    @Test
    @DisplayName("Chained leaves produce different hashes")
    void testChainedLeavesProduceDifferentHashes() {
        String leaf1 = appender.appendLeaf(null, "aabb");
        String leaf2 = appender.appendLeaf(leaf1, "ccdd");
        assertNotNull(leaf2);
        assertEquals(64, leaf2.length());
        assertNotEquals(leaf1, leaf2);
    }

    @Test
    @DisplayName("Leaf computation is strictly deterministic")
    void testDeterministicForSameInput() {
        String leaf1a = appender.appendLeaf("prevHash", "eventHash");
        String leaf1b = appender.appendLeaf("prevHash", "eventHash");
        assertEquals(leaf1a, leaf1b);
    }

    @Test
    @DisplayName("Changing eventHash changes leafHash")
    void testCollisionResistanceOnEventHashChange() {
        String leaf1 = appender.appendLeaf("prevHash", "eventHash1");
        String leaf2 = appender.appendLeaf("prevHash", "eventHash2");
        assertNotEquals(leaf1, leaf2);
    }
}
