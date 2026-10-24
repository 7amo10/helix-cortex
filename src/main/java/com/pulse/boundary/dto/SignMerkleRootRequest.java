package com.pulse.boundary.dto;

import java.io.Serializable;

/**
 * Request payload for signing and publishing a Merkle root checkpoint over a sequence range.
 *
 * @param fromSeq lower sequence bound (inclusive, must be >= 1)
 * @param toSeq   upper sequence bound (inclusive, must be >= fromSeq)
 */
public record SignMerkleRootRequest(
        Long fromSeq,
        Long toSeq
) implements Serializable {}
